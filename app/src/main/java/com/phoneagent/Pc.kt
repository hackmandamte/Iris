package com.phoneagent

import android.content.Context
import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArraySet
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

data class PcDevice(val id: String, val name: String, val host: String, val port: Int, val key: ByteArray)

object Pc {
    data class Link(val host: String, val port: Int, val keyB64: String, val pcName: String)

    val inboxListeners = CopyOnWriteArraySet<() -> Unit>()
    @Volatile private var polling = false

    fun parseLink(s: String): Link? {
        return try {
            val u = Uri.parse(s)
            if (u.scheme != "iris" || u.host != "pair") return null
            Link(
                host = u.getQueryParameter("h") ?: return null,
                port = u.getQueryParameter("p")?.toIntOrNull() ?: return null,
                keyB64 = u.getQueryParameter("k") ?: return null,
                pcName = u.getQueryParameter("n") ?: "PC"
            )
        } catch (e: Exception) { null }
    }

    private fun sp(ctx: Context) = ctx.getSharedPreferences("agent_pc", Context.MODE_PRIVATE)

    fun devices(ctx: Context): List<PcDevice> {
        val raw = sp(ctx).getString("devices", "[]") ?: "[]"
        val a = try { JSONArray(raw) } catch (e: Exception) { return emptyList() }
        val out = ArrayList<PcDevice>()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            out.add(PcDevice(
                o.optString("id"),
                o.optString("name"),
                o.optString("host"),
                o.optInt("port"),
                Base64.decode(o.optString("key"), Base64.DEFAULT)
            ))
        }
        return out
    }

    fun primary(ctx: Context): PcDevice? = devices(ctx).firstOrNull()

    fun pair(ctx: Context, link: Link, phoneName: String, importSettings: Boolean): String? {
        return try {
            val key = Base64.decode(link.keyB64, Base64.DEFAULT)
            val body = JSONObject()
                .put("name", phoneName)
                .put("importSettings", importSettings)
                .toString()
            val resp = post(link.host, link.port, "/pair", body, key)
            if (resp == null) return "Could not reach the PC"
            val o = JSONObject(resp)
            if (o.optString("error").isNotEmpty()) return o.optString("error")
            val id = o.optString("id", link.pcName)
            val arr = JSONArray(sp(ctx).getString("devices", "[]"))
            // replace existing with same host
            val next = JSONArray()
            for (i in 0 until arr.length()) {
                val d = arr.getJSONObject(i)
                if (d.optString("host") != link.host) next.put(d)
            }
            next.put(JSONObject()
                .put("id", id)
                .put("name", link.pcName)
                .put("host", link.host)
                .put("port", link.port)
                .put("key", link.keyB64))
            sp(ctx).edit().putString("devices", next.toString()).apply()
            if (importSettings) {
                o.optJSONObject("settings")?.let { s ->
                    ctx.getSharedPreferences("agent", Context.MODE_PRIVATE).edit()
                        .putString("api_key", s.optString("api_key"))
                        .putString("model", s.optString("model", "gemini-2.0-flash"))
                        .apply()
                }
            }
            null
        } catch (e: Exception) { e.message ?: "Pairing failed" }
    }

    private fun encrypt(data: ByteArray, key: ByteArray): ByteArray {
        val iv = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.copyOf(16), "AES"), IvParameterSpec(iv))
        return iv + c.doFinal(data)
    }

    private fun decrypt(data: ByteArray, key: ByteArray): ByteArray {
        val iv = data.copyOfRange(0, 16)
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key.copyOf(16), "AES"), IvParameterSpec(iv))
        return c.doFinal(data, 16, data.size - 16)
    }

    private fun post(host: String, port: Int, path: String, body: String, key: ByteArray): String? {
        return try {
            val c = URL("http://$host:$port$path").openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 5000
            c.readTimeout = 15000
            c.setRequestProperty("Content-Type", "application/octet-stream")
            val enc = encrypt(body.toByteArray(Charsets.UTF_8), key)
            c.outputStream.use { it.write(enc) }
            if (c.responseCode !in 200..299) return null
            val raw = c.inputStream.readBytes()
            String(decrypt(raw, key), Charsets.UTF_8)
        } catch (e: Exception) { null }
    }

    fun startPolling(ctx: Context) {
        if (polling) return
        polling = true
        thread {
            while (polling) {
                try { pollOnce(ctx) } catch (e: Exception) { }
                Thread.sleep(2500)
            }
        }
    }

    fun stopPolling() { polling = false }

    fun pollOnce(ctx: Context) {
        val devs = devices(ctx)
        if (devs.isEmpty()) return
        for (d in devs) {
            val resp = post(d.host, d.port, "/poll", "{}", d.key) ?: continue
            val o = JSONObject(resp)
            val inbox = o.optJSONArray("inbox") ?: continue
            if (inbox.length() > 0) {
                // stash and notify
                sp(ctx).edit().putString("inbox", inbox.toString()).apply()
                inboxListeners.forEach { it() }
            }
        }
    }
}
