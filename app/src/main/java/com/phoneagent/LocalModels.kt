package com.phoneagent

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL

/**
 * Discover optional on-device / local LLM endpoints for fallback when cloud APIs fail.
 * Does not bypass Android security — only probes common local HTTP APIs and known packages.
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
        val probes = listOf(
            "http://127.0.0.1:11434/v1" to "llama3.2",
            "http://127.0.0.1:8080/v1" to "local",
            "http://127.0.0.1:1234/v1" to "local"
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
            val code = c.responseCode
            code in 200..499
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
