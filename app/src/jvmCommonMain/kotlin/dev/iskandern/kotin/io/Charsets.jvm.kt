package dev.iskandern.kotin.io

import java.nio.charset.Charset

internal actual fun codePageDecoder(name: String): ((bytes: ByteArray, start: Int, end: Int) -> String)? {
  val charset = try {
    Charset.forName(name)
  } catch (e: Exception) {
    return null
  }
  return { bytes, start, end -> String(bytes, start, end - start, charset) }
}
