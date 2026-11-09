package com.phoneagent

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL

/**
 * Discover optional on-device / local LLM endpoints for fallback when cloud APIs fail.
 * Probe requires a JSON response (not HTML) so random local web servers are ignored.
 */
object LocalModels {
    data class Endpoint(val name: String, val baseUrl: String, val model: String)

    private val KNOWN_PACKAGES = listOf(
        "com.google.android.apps.aicore" to "Google AICore",
        "com.google.android.as" to "Android System Intelligence",
        "com.samsung.android.rubin.app" to "Samsung Customization",
        "com.miui.voiceassist" to "Xiaomi voice",
        "com.oplus.aiunit" to "OPPO AI"
    )

    fun discoverPackages(ctx: Context): List<String> {
        val pm = ctx.packageManager
        val found = ArrayList<String>()
        for ((pkg, label) in KNOWN_PACKAGES) {
            try {
                pm.getPackageInfo(pkg, 0)
                found.add("$label ($pkg)")
            } catch (_: Exception) { }
        }
        return found
    }

    fun candidateEndpoints(ctx: Context): List<Endpoint> {
        val sp = ctx.getSharedPreferences("agent", Context.MODE_PRIVATE)
        val out = ArrayList<Endpoint>()
        val custom = sp.getString("local_llm_base", "")!!.trim()
        val customModel = sp.getString("local_llm_model", "")!!.trim().ifEmpty { "local" }
        if (custom.isNotEmpty()) {
            out.add(Endpoint("Custom local", custom.trimEnd('/'), customModel))
        }
        // Only probe Ollama default — 8080/1234 often serve HTML and caused false positives
        val probes = listOf(
            "http://127.0.0.1:11434/v1" to "llama3.2"
        )
        for ((base, model) in probes) {
            if (out.none { it.baseUrl == base }) out.add(Endpoint("Probe $base", base, model))
        }
        return out
    }

    fun firstReachable(ctx: Context, timeoutMs: Int = 1200): Endpoint? {
        for (ep in candidateEndpoints(ctx)) {
            if (probe(ep.baseUrl, timeoutMs)) return ep
        }
        return null
    }

    private fun probe(base: String, timeoutMs: Int): Boolean {
        return try {
            val c = URL("$base/models").openConnection() as HttpURLConnection
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.requestMethod = "GET"
            c.setRequestProperty("Accept", "application/json")
            val code = c.responseCode
            if (code !in 200..299 && code != 401) return false
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }?.trim().orEmpty()
            if (body.isEmpty()) return code == 401
            val head = body.take(32).lowercase()
            if (head.startsWith("<!doctype") || head.startsWith("<html") || head.startsWith("<")) return false
            body.startsWith("{") || body.startsWith("[")
        } catch (_: Exception) {
            false
        }
    }

    fun statusText(ctx: Context): String {
        val pkgs = discoverPackages(ctx)
        val ep = firstReachable(ctx)
        val sb = StringBuilder()
        sb.append("On-device packages: ")
        sb.append(if (pkgs.isEmpty()) "none detected" else pkgs.joinToString(", "))
        sb.append("\nLocal HTTP: ")
        sb.append(if (ep != null) "${ep.name} @ ${ep.baseUrl}" else "none reachable")
        return sb.toString()
    }
}
