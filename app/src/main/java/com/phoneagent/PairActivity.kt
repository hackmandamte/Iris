package com.phoneagent

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.Toast
import kotlin.concurrent.thread

/** Opened by scanning the QR code on the PC (iris://pair?...) or by pasting the link in the app. */
class PairActivity : Activity() {
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val link = Pc.parseLink(intent?.data?.toString() ?: "")
        if (link == null) {
            Toast.makeText(this, "That is not an Iris pairing link", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val box = CheckBox(this).apply {
            text = "Also import the API key and settings from the PC"
            isChecked = true
        }
        val wrap = LinearLayout(this).apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(box)
        }
        AlertDialog.Builder(this)
            .setTitle("Pair with ${link.pcName}?")
            .setMessage("PC address: ${link.host}\n\nThis phone will be able to send tasks to that PC and hand tasks back and forth over Wi-Fi. Only continue if you started pairing on your own PC.")
            .setView(wrap)
            .setPositiveButton("Pair") { _, _ -> doPair(link, box.isChecked) }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun doPair(link: Pc.Link, settings: Boolean) {
        Toast.makeText(this, "Pairing…", Toast.LENGTH_SHORT).show()
        val app = applicationContext
        thread {
            val err = Pc.pair(app, link, Build.MODEL ?: "Phone", settings)
            runOnUiThread {
                if (err != null) {
                    Toast.makeText(this, err, Toast.LENGTH_LONG).show()
                    finish()
                } else {
                    Toast.makeText(this, "Paired with ${link.pcName}", Toast.LENGTH_LONG).show()
                    Pc.startPolling(app)
                    thread { try { Pc.pollOnce(app) } catch (e: Exception) { } }
                    startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    finish()
                }
            }
        }
    }
}
