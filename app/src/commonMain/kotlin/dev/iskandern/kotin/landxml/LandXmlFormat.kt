package dev.iskandern.kotin.landxml

import dev.iskandern.kotin.io.ByteArrayInput
import dev.iskandern.kotin.io.DrawingFormat

/**
 * Признак LandXML по началу файла ([DrawingFormat.detect]): после BOM и пробелов есть корень `<LandXML`
 * (в том числе с префиксом, `<lx:LandXML`).
 */
object LandXmlFormat {
  /** Начало файла [head] (первые [size] байтов) — LandXML. */
  fun isLandXml(head: ByteArray, size: Int): Boolean {
    fun at(i: Int) = if (i < size) head[i].toInt() and 0xFF else -1
    return when {
      // UTF-16: корень ищем в перекодированном начале
      at(0) == 0xFF && at(1) == 0xFE -> hasRoot(utf16Head(head, 2, size, bigEndian = false))
      at(0) == 0xFE && at(1) == 0xFF -> hasRoot(utf16Head(head, 2, size, bigEndian = true))
      at(0) == 0x3C && at(1) == 0 -> hasRoot(utf16Head(head, 0, size, bigEndian = false))
      at(0) == 0 && at(1) == 0x3C -> hasRoot(utf16Head(head, 0, size, bigEndian = true))
      at(0) == 0xEF && at(1) == 0xBB && at(2) == 0xBF -> hasRoot(head, 3, size)
      else -> hasRoot(head, 0, size)
    }
  }

  private fun utf16Head(head: ByteArray, from: Int, size: Int, bigEndian: Boolean): ByteArray {
    val input = Utf16ToUtf8Input(ByteArrayInput(head.copyOf(size), from), bigEndian)
    // символ UTF-16 даёт не больше 3 байтов UTF-8 на 2 байта
    val out = ByteArray(size * 3 / 2 + 4)
    var n = 0
    while (n < out.size) {
      val read = input.read(out, n, out.size - n)
      if (read < 0) break
      n += read
    }
    return out.copyOf(n)
  }

  private fun hasRoot(buf: ByteArray, from: Int = 0, to: Int = buf.size): Boolean {
    var i = from
    while (i < to && isSpace(buf[i])) i++
    // XML начинается с '<': DXF (ASCII и бинарный) и пустой файл отсеиваются сразу
    if (i >= to || buf[i] != LT) return false
    var j = i
    while (true) {
      j = indexOf(buf, ROOT, j + 1, to)
      if (j < 0) return false
      val after = j + ROOT.size
      if (after < to && !isSpace(buf[after]) && buf[after] != GT && buf[after] != SLASH) continue
      if (buf[j - 1] == LT) return true
      if (buf[j - 1] != COLON) continue
      // префикс: <lx:LandXML
      var k = j - 2
      while (k > i && isNameByte(buf[k])) k--
      if (k < j - 2 && buf[k] == LT) return true
    }
  }

  private fun indexOf(buf: ByteArray, pattern: ByteArray, from: Int, to: Int): Int {
    var i = from
    while (i + pattern.size <= to) {
      var k = 0
      while (k < pattern.size && buf[i + k] == pattern[k]) k++
      if (k == pattern.size) return i
      i++
    }
    return -1
  }

  private fun isSpace(b: Byte) = b == SPACE || b == TAB || b == CR || b == LF

  private fun isNameByte(b: Byte): Boolean {
    val c = b.toInt().toChar()
    return c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '-' || c == '.'
  }

  private val ROOT = "LandXML".encodeToByteArray()
  private const val LT = '<'.code.toByte()
  private const val GT = '>'.code.toByte()
  private const val SLASH = '/'.code.toByte()
  private const val COLON = ':'.code.toByte()
  private const val SPACE = ' '.code.toByte()
  private const val TAB = '\t'.code.toByte()
  private const val CR = '\r'.code.toByte()
  private const val LF = '\n'.code.toByte()
}
