package dev.iskandern.kotin.landxml

import dev.iskandern.kotin.io.ByteInput

/**
 * UTF-16 → UTF-8 на лету: токенизатор дальше работает с байтами как обычно.
 * ASCII — быстрым путём, суррогатные пары — в 4 байта, одиночный суррогат — U+FFFD.
 */
internal class Utf16ToUtf8Input(private val source: ByteInput, private val bigEndian: Boolean) : ByteInput {
  private val raw = ByteArray(64 * 1024)
  private var rawPos = 0
  private var rawLimit = 0
  private var eof = false

  // байты символа, не поместившиеся в прошлое чтение
  private val pending = ByteArray(4)
  private var pendingPos = 0
  private var pendingLimit = 0

  override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
    var out = offset
    val end = offset + length
    while (out < end) {
      if (pendingPos < pendingLimit) {
        buffer[out++] = pending[pendingPos++]
        continue
      }
      val unit = nextUnit()
      if (unit < 0) break
      if (unit < 0x80) {
        buffer[out++] = unit.toByte()
        continue
      }
      encode(codePoint(unit))
    }
    return if (out == offset && length > 0) -1 else out - offset
  }

  override fun close() = source.close()

  private fun codePoint(unit: Int): Int {
    if (unit !in 0xD800..0xDBFF) return if (unit in 0xDC00..0xDFFF) REPLACEMENT else unit
    if (!ensure(2)) return REPLACEMENT
    val low = peekUnit()
    if (low !in 0xDC00..0xDFFF) return REPLACEMENT
    rawPos += 2
    return 0x10000 + ((unit - 0xD800) shl 10) + (low - 0xDC00)
  }

  private fun encode(code: Int) {
    pendingPos = 0
    pendingLimit = when {
      code < 0x800 -> {
        pending[0] = (0xC0 or (code shr 6)).toByte()
        pending[1] = (0x80 or (code and 0x3F)).toByte()
        2
      }
      code < 0x10000 -> {
        pending[0] = (0xE0 or (code shr 12)).toByte()
        pending[1] = (0x80 or ((code shr 6) and 0x3F)).toByte()
        pending[2] = (0x80 or (code and 0x3F)).toByte()
        3
      }
      else -> {
        pending[0] = (0xF0 or (code shr 18)).toByte()
        pending[1] = (0x80 or ((code shr 12) and 0x3F)).toByte()
        pending[2] = (0x80 or ((code shr 6) and 0x3F)).toByte()
        pending[3] = (0x80 or (code and 0x3F)).toByte()
        4
      }
    }
  }

  // следующая кодовая единица или -1 в конце; нечётный последний байт отбрасывается
  private fun nextUnit(): Int {
    if (!ensure(2)) return -1
    val unit = peekUnit()
    rawPos += 2
    return unit
  }

  private fun peekUnit(): Int {
    val a = raw[rawPos].toInt() and 0xFF
    val b = raw[rawPos + 1].toInt() and 0xFF
    return if (bigEndian) (a shl 8) or b else (b shl 8) or a
  }

  private fun ensure(count: Int): Boolean {
    while (rawLimit - rawPos < count) {
      if (eof) return false
      if (rawPos > 0) {
        raw.copyInto(raw, 0, rawPos, rawLimit)
        rawLimit -= rawPos
        rawPos = 0
      }
      val n = source.read(raw, rawLimit, raw.size - rawLimit)
      if (n < 0) eof = true else rawLimit += n
    }
    return true
  }

  private companion object {
    const val REPLACEMENT = 0xFFFD
  }
}
