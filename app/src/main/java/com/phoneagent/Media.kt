package com.phoneagent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Local library of uploaded / captured files under filesDir/uploads. */
object Media {
    private fun dir(ctx: Context): File =
        File(ctx.filesDir, "uploads").also { it.mkdirs() }

    private fun indexFile(ctx: Context) = File(dir(ctx), "index.json")

    private fun loadIndex(ctx: Context): JSONArray {
        val f = indexFile(ctx)
        if (!f.exists()) return JSONArray()
        return try { JSONArray(f.readText()) } catch (e: Exception) { JSONArray() }
    }

    private fun saveIndex(ctx: Context, a: JSONArray) {
        indexFile(ctx).writeText(a.toString())
    }

    fun list(ctx: Context): JSONArray = loadIndex(ctx)

    fun get(ctx: Context, id: String): JSONObject? {
        val a = loadIndex(ctx)
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            if (o.optString("id") == id) return o
        }
        return null
    }

    fun fileFor(ctx: Context, id: String): File? {
        val meta = get(ctx, id) ?: return null
        val f = File(dir(ctx), meta.optString("file"))
        return if (f.exists()) f else null
    }

    fun importUri(ctx: Context, uri: Uri, displayName: String? = null): String {
        return try {
            val cr = ctx.contentResolver
            val type = cr.getType(uri) ?: "application/octet-stream"
            val name = displayName
                ?: cr.query(uri, null, null, null, null)?.use { c ->
                    val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (c.moveToFirst() && idx >= 0) c.getString(idx) else null
                }
                ?: "file_${System.currentTimeMillis()}"
            val id = UUID.randomUUID().toString().take(10)
            val ext = name.substringAfterLast('.', when {
                type.startsWith("image/") -> type.substringAfter('/')
                type.contains("pdf") -> "pdf"
                type.contains("text") -> "txt"
                else -> "bin"
            })
            val destName = "$id.$ext"
            val dest = File(dir(ctx), destName)
            cr.openInputStream(uri)?.use { input ->
                FileOutputStream(dest).use { output -> input.copyTo(output) }
            } ?: return "ERROR: could not read the selected file"
            val kind = when {
                type.startsWith("image/") -> "image"
                type.contains("pdf") -> "pdf"
                type.startsWith("text/") || name.endsWith(".txt") || name.endsWith(".md") || name.endsWith(".csv") -> "text"
                else -> "file"
            }
            val meta = JSONObject()
                .put("id", id)
                .put("name", name)
                .put("file", destName)
                .put("mime", type)
                .put("kind", kind)
                .put("bytes", dest.length())
                .put("added", SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date()))
            val a = loadIndex(ctx)
            a.put(meta)
            saveIndex(ctx, a)
            id
        } catch (e: Exception) {
            "ERROR: ${e.message}"
        }
    }

    fun saveBitmap(ctx: Context, bmp: Bitmap, name: String = "screenshot"): String {
        val id = UUID.randomUUID().toString().take(10)
        val destName = "$id.jpg"
        val dest = File(dir(ctx), destName)
        FileOutputStream(dest).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        val meta = JSONObject()
            .put("id", id)
            .put("name", name)
            .put("file", destName)
            .put("mime", "image/jpeg")
            .put("kind", "image")
            .put("bytes", dest.length())
            .put("added", SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date()))
        val a = loadIndex(ctx)
        a.put(meta)
        saveIndex(ctx, a)
        return id
    }

    fun delete(ctx: Context, id: String): Boolean {
        val a = loadIndex(ctx)
        val next = JSONArray()
        var found = false
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            if (o.optString("id") == id) {
                found = true
                File(dir(ctx), o.optString("file")).delete()
            } else next.put(o)
        }
        if (found) saveIndex(ctx, next)
        return found
    }

    fun imageBase64(ctx: Context, id: String, maxSide: Int = 1280): String? {
        val f = fileFor(ctx, id) ?: return null
        val meta = get(ctx, id) ?: return null
        if (meta.optString("kind") != "image") return null
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, opts)
        var sample = 1
        val maxDim = maxOf(opts.outWidth, opts.outHeight)
        while (maxDim / sample > maxSide) sample *= 2
        val bmp = BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val baos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 80, baos)
        bmp.recycle()
        return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
    }

    fun textContent(ctx: Context, id: String, maxChars: Int = 50_000): String? {
        val f = fileFor(ctx, id) ?: return null
        val meta = get(ctx, id) ?: return null
        if (meta.optString("kind") != "text") return null
        return f.readText().take(maxChars)
    }
}
