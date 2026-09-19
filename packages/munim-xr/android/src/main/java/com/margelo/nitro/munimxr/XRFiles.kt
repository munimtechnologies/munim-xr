package com.margelo.nitro.munimxr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** File helpers shared by the XR view. Blocking; call off the UI thread where noted. */
internal object XRFiles {
  /**
   * Returns a new file under `cacheDir/munim-xr/<kind>/`, deleting older files so
   * that at most [keep] files of that kind exist once the caller writes it.
   */
  fun nextOutputFile(cacheDir: File, kind: String, extension: String, keep: Int): File {
    val directory = File(File(cacheDir, "munim-xr"), kind).apply { mkdirs() }
    val existing = directory.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
    existing.drop((keep - 1).coerceAtLeast(0)).forEach { it.delete() }
    return File(directory, "${UUID.randomUUID()}.$extension")
  }

  /** Decodes a bitmap from a file path, `file://`, `content://`, or `http(s)://` URI. Blocking. */
  fun loadBitmap(context: Context, uri: String): Bitmap? {
    return open(context, uri).use { BitmapFactory.decodeStream(it) }
  }

  private fun open(context: Context, uri: String): InputStream {
    if (uri.startsWith("/")) return File(uri).inputStream()
    val parsed = Uri.parse(uri)
    return when (parsed.scheme) {
      "file" -> File(requireNotNull(parsed.path) { "Invalid file URI: $uri" }).inputStream()
      "content", "android.resource" -> requireNotNull(context.contentResolver.openInputStream(parsed)) {
        "Could not open $uri"
      }
      "http", "https" -> {
        val connection = URL(uri).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        val status = connection.responseCode
        check(status in 200..299) { "Downloading $uri failed with HTTP status $status." }
        connection.inputStream
      }
      else -> throw IllegalArgumentException(
        "Unsupported URI: $uri. Use file://, an absolute path, content://, or http(s)://.",
      )
    }
  }
}
