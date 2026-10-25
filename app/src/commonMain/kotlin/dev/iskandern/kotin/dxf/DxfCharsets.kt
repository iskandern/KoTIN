package dev.iskandern.kotin.dxf

/** UTF-16 (без BOM) → строка. */
internal fun decodeUtf16(bytes: ByteArray, start: Int, bigEndian: Boolean): String {
  val count = (bytes.size - start) / 2
  val chars = CharArray(count)
  for (i in 0 until count) {
    val a = bytes[start + 2 * i].toInt() and 0xFF
    val b = bytes[start + 2 * i + 1].toInt() and 0xFF
    chars[i] = (if (bigEndian) (a shl 8) or b else (b shl 8) or a).toChar()
  }
  return chars.concatToString()
}
