package com.phoneagent

import android.content.Context
import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArraySet
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

class PcDevice(val id: String, val name: String, val host: String, val port: Int, val key: ByteArray)
class PcApproval(val id: String, val dev: PcDevice, val taskId: Int, val kind: String, val text: String)

object Pc {
    private const val AAD_PAIR = "iris-pair"
    private const val AAD_API = "iris-api"
    private val rnd = SecureRandom()
    val inboxListeners = CopyOnWriteArraySet<() -> Unit>()
    @Volatile private var polling = false

    class Link(val host: String, val port: Int, val token: String, val pcName: String)

    private fun sp(ctx: Context) = ctx.getSharedPreferences("agent_pc", Context.MODE_PRIVATE)
    private fun agentSp(ctx: Context) = ctx.getSharedPreferences("agent", Context.MODE_PRIVATE)
    private fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String): ByteArray = Base64.decode(s, Base64.DEFAULT)
    private fun sha256(s: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())

    private fun seal(key: ByteArray, obj: JSONObject, aad: String): JSONObject {
        val iv = ByteArray(12)
        rnd.nextBytes(iv)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        c.updateAAD(aad.toByteArray())
        return JSONObject().put("n", b64(iv)).put("c", b64(c.doFinal(obj.toString().toByteArray())))
    }

    private fun unseal(key: ByteArray, env: JSONObject, aad: String): JSONObject {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, unb64(env.getString("n"))))
        c.updateAAD(aad.toByteArray())
        return JSONObject(String(c.doFinal(unb64(env.getString("c")))))
    }

    private fun http(host: String, port: Int, path: String, body: JSONObject): Pair<Int, JSONObject> {
        val c = URL("http://$host:$port$path").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 6000
        c.readTimeout = 20000
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: "{}"
        val json = try { JSONObject(text) } catch (e: Exception) { JSONObject() }
        return Pair(code, json)
    }

    fun devices(ctx: Context): List<PcDevice> {
        val arr = JSONArray(sp(ctx).getString("devices", "[]"))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            PcDevice(o.getString("id"), o.getString("name"), o.getString("host"), o.getInt("port"), unb64(o.getString("key")))
        }
    }

    fun primary(ctx: Context): PcDevice? = devices(ctx).firstOrNull()

    private fun save(ctx: Context, list: List<PcDevice>) {
        val arr = JSONArray()
        for (d in list) {
            arr.put(JSONObject().put("id", d.id).put("name", d.name).put("host", d.host)
                .put("port", d.port).put("key", b64(d.key)))
        }
        sp(ctx).edit().putString("devices", arr.toString()).apply()
    }

    fun remove(ctx: Context, id: String) = save(ctx, devices(ctx).filter { it.id != id })

    fun parseLink(s: String): Link? {
        val u = try { Uri.parse(s.trim()) } catch (e: Exception) { return null }
        if (u.scheme != "iris" || u.host != "pair") return null
        val h = u.getQueryParameter("h") ?: return null
        val p = u.getQueryParameter("p")?.toIntOrNull() ?: return null
        val t = u.getQueryParameter("t") ?: return null
        val n = u.getQueryParameter("n") ?: "PC"
        return Link(h, p, t, n)
    }

    private fun exportSettings(ctx: Context): JSONObject {
        val p = agentSp(ctx)
        return JSONObject()
            .put("api_key", p.getString("api_key", "") ?: "")
            .put("model", p.getString("model", "") ?: "")
            .put("provider", p.getString("provider", "auto") ?: "auto")
            .put("openai_key", p.getString("openai_key", "") ?: "")
            .put("openai_base", p.getString("openai_base", "") ?: "")
            .put("openai_model", p.getString("openai_model", "") ?: "")
    }

    /** Pair with PC. Always sends phone API keys to the PC. */
    fun pair(ctx: Context, link: Link, phoneName: String, importSettings: Boolean = true): String? {
        return try {
            val phoneKey = ByteArray(32).also { rnd.nextBytes(it) }
            val pairKey = sha256(link.token)
            val req = seal(pairKey, JSONObject()
                .put("name", phoneName)
                .put("key", b64(phoneKey))
                .put("importSettings", importSettings)
                .put("settings", exportSettings(ctx)), AAD_PAIR)
            val (code, resp) = http(link.host, link.port, "/pair", req)
            if (code !in 200..299) return "HTTP $code"
            val plain = unseal(pairKey, resp, AAD_PAIR)
            if (plain.has("error")) return plain.getString("error")
            val id = plain.optString("device", link.pcName)
            val list = devices(ctx).filter { it.host != link.host }.toMutableList()
            list.add(PcDevice(id, link.pcName, link.host, link.port, phoneKey))
            save(ctx, list)
            if (importSettings) {
                plain.optJSONObject("settings")?.let { s ->
                    val ed = agentSp(ctx).edit()
                    val cur = agentSp(ctx)
                    fun fill(key: String, value: String) {
                        if (value.isNotBlank() && cur.getString(key, "").isNullOrBlank()) {
                            ed.putString(key, value)
                        }
                    }
                    fill("api_key", s.optString("api_key"))
                    fill("model", s.optString("model"))
                    fill("provider", s.optString("provider"))
                    fill("openai_key", s.optString("openai_key"))
                    fill("openai_base", s.optString("openai_base"))
                    fill("openai_model", s.optString("openai_model"))
                    ed.apply()
                }
            }
            null
        } catch (e: Exception) { e.message ?: "Pairing failed" }
    }

    fun call(dev: PcDevice, op: String, extra: JSONObject = JSONObject()): JSONObject {
        val payload = JSONObject().put("op", op)
        for (k in extra.keys()) payload.put(k, extra.get(k))
        val env = seal(dev.key, payload, AAD_API)
        val (code, resp) = http(dev.host, dev.port, "/api", env)
        if (code !in 200..299) throw RuntimeException("PC HTTP $code")
        return unseal(dev.key, resp, AAD_API)
    }

    fun inbox(ctx: Context): List<JSONObject> {
        val arr = JSONArray(sp(ctx).getString("inbox", "[]"))
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    fun removeInbox(ctx: Context, id: String) {
        val next = JSONArray()
        for (o in inbox(ctx)) if (o.optString("id") != id) next.put(o)
        sp(ctx).edit().putString("inbox", next.toString()).apply()
    }

    fun pollOnce(ctx: Context) {
        for (d in devices(ctx)) {
            try {
                val r = call(d, "inbox")
                val arr = r.optJSONArray("items") ?: continue
                if (arr.length() == 0) continue
                val existing = inbox(ctx).map { it.optString("id") }.toSet()
                val cur = JSONArray(sp(ctx).getString("inbox", "[]"))
                var changed = false
                for (i in 0 until arr.length()) {
                    val item = arr.getJSONObject(i)
                    if (item.optString("id") !in existing) {
                        cur.put(item)
                        changed = true
                    }
                }
                if (changed) {
                    sp(ctx).edit().putString("inbox", cur.toString()).apply()
                    inboxListeners.forEach { it() }
                }
            } catch (_: Exception) { }
        }
    }

    fun startPolling(ctx: Context) {
        if (polling) return
        polling = true
        val app = ctx.applicationContext
        thread(name = "pc-poll") {
            while (polling) {
                try { pollOnce(app) } catch (_: Exception) { }
                Thread.sleep(2500)
            }
        }
    }

    fun stopPolling() { polling = false }
}
