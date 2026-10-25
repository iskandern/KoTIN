package dev.iskandern.kotin.io

/** Декодер однобайтовой/многобайтовой кодовой страницы по имени (windows-1251, Shift_JIS, …); null — платформа не умеет. */
internal expect fun codePageDecoder(name: String): ((bytes: ByteArray, start: Int, end: Int) -> String)?

/** Байты как ISO-8859-1: каждый байт — символ с тем же кодом. Для ASCII-части DXF и разметки XML. */
internal fun latin1(bytes: ByteArray, start: Int, end: Int): String {
  val chars = CharArray(end - start)
  for (i in chars.indices) chars[i] = (bytes[start + i].toInt() and 0xFF).toChar()
  return chars.concatToString()
}

/** Корректный ли UTF-8 (без обрезанных и лишних продолжений, без суррогатов и overlong). */
internal fun isValidUtf8(bytes: ByteArray, start: Int, end: Int): Boolean {
  var i = start
  while (i < end) {
    val b = bytes[i].toInt() and 0xFF
    val extra: Int
    val min: Int
    when {
      b < 0x80 -> { i++; continue }
      b in 0xC2..0xDF -> { extra = 1; min = 0x80 }
      b in 0xE0..0xEF -> { extra = 2; min = 0x800 }
      b in 0xF0..0xF4 -> { extra = 3; min = 0x10000 }
      else -> return false
    }
    if (i + extra >= end) return false
    var code = b and (0x3F shr extra)
    for (k in 1..extra) {
      val c = bytes[i + k].toInt() and 0xFF
      if (c and 0xC0 != 0x80) return false
      code = (code shl 6) or (c and 0x3F)
    }
    if (code < min || code > 0x10FFFF || code in 0xD800..0xDFFF) return false
    i += extra + 1
  }
  return true
}
