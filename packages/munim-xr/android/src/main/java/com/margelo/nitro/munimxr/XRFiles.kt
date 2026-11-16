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

  /** Decodes a bitmap from any URI [open] accepts. Blocking. */
  fun loadBitmap(context: Context, uri: String): Bitmap? {
    return open(context, uri).use { BitmapFactory.decodeStream(it) }
  }

  /**
   * Opens a file path, `file://`, `content://`, `android.resource://`, or
   * `http(s)://` URI, or a bare resource name. Release builds of React Native
   * bundle `require()`d assets as resources and resolve them to bare names such
   * as `assets_cube` (raw) or `assets_xrtarget` (drawable). Blocking.
   */
  fun open(context: Context, uri: String): InputStream {
    if (uri.startsWith("/")) return File(uri).inputStream()
    val parsed = Uri.parse(uri)
    if (parsed.scheme == null) return openResource(context, uri)
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

  @android.annotation.SuppressLint("DiscouragedApi")
  private fun openResource(context: Context, name: String): InputStream {
    val resources = context.resources
    for (type in arrayOf("raw", "drawable")) {
      val id = resources.getIdentifier(name, type, context.packageName)
      if (id != 0) return resources.openRawResource(id)
    }
    throw IllegalArgumentException(
      "Unsupported URI: $name. Use file://, an absolute path, content://, http(s)://, or a bundled asset.",
    )
  }
}
