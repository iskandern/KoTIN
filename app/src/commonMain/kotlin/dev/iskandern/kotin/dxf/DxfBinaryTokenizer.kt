package dev.iskandern.kotin.dxf

import dev.iskandern.kotin.io.ByteInput
import dev.iskandern.kotin.io.ByteNumbers
import dev.iskandern.kotin.io.isSpace
import dev.iskandern.kotin.io.latin1
import dev.iskandern.kotin.model.UiText

/**
 * Читает бинарный DXF (сигнатура `AutoCAD Binary DXF\r\n\x1a\0`) с тем же интерфейсом, что [DxfTokenizer].
 *
 * Код группы — 2 байта (R13+) или 1 байт (старше; 255 — признак 2-байтового кода следом). Тип значения
 * определяется кодом: строка с нулём на конце, int16/int32/int64, double, байт или binary-chunk (1 байт длины).
 * Строки остаются в [buffer], числа читаются сразу, поэтому [valueStart]/[valueEnd] осмысленны только для строк.
 */
class DxfBinaryTokenizer(
  private val input: ByteInput,
  initialBufferSize: Int = DEFAULT_BUFFER_SIZE,
) : DxfTokenSource {
  override var buffer = ByteArray(initialBufferSize)
    private set
  private var pos = 0
  private var limit = 0
  private var eof = false

  override var code: Int = -1
    private set
  override var valueStart: Int = 0
    private set
  override var valueEnd: Int = 0
    private set
  override var lineNumber: Int = 0
    private set
  override var bytesRead: Long = 0
    private set

  private var longCodes = true
  private var numeric = 0L
  private var real = 0.0
  private var isReal = false
  private var isString = false

  init {
    if (!require(SIGNATURE.size)) throw DxfFormatException("@error_dxfb_short")
    for (i in SIGNATURE.indices) {
      if (buffer[pos + i] != SIGNATURE[i]) throw DxfFormatException("@error_dxfb_signature")
    }
    pos += SIGNATURE.size
    // первая пара — 0 / SECTION: при 1-байтовом коде сразу за нулём идёт 'S', при 2-байтовом — ещё нулевой байт
    if (require(2)) longCodes = buffer[pos + 1] != 'S'.code.toByte()
  }

  override fun next(): Boolean {
    if (!require(1)) return false
    val first = buffer[pos].toInt() and 0xFF
    if (longCodes || first == 255) {
      if (!require(if (longCodes) 2 else 3)) throw DxfFormatException("@error_dxfb_code")
      val at = if (longCodes) pos else pos + 1
      code = (buffer[at].toInt() and 0xFF) or ((buffer[at + 1].toInt() and 0xFF) shl 8)
      pos = at + 2
    } else {
      code = first
      pos++
    }
    lineNumber++
    readValue()
    return true
  }

  private fun readValue() {
    isString = false
    isReal = false
    when (valueKind(code)) {
      KIND_STRING -> {
        readString()
      }
      KIND_DOUBLE -> {
        numeric = readLong(8)
        real = Double.fromBits(numeric)
        isReal = true
      }
      KIND_INT16 -> numeric = readLong(2).toShort().toLong()
      KIND_INT32 -> numeric = readLong(4).toInt().toLong()
      KIND_INT64 -> numeric = readLong(8)
      KIND_BYTE -> numeric = readLong(1)
      else -> { // binary chunk: байт длины и данные; содержимое сущностям не нужно
        if (!require(1)) throw DxfFormatException(UiText.key("error_dxfb_chunk", code))
        val length = buffer[pos].toInt() and 0xFF
        if (!require(1 + length)) throw DxfFormatException(UiText.key("error_dxfb_chunk", code))
        valueStart = pos + 1
        valueEnd = pos + 1 + length
        pos = valueEnd
        isString = true
      }
    }
  }

  private fun readString() {
    var scanFrom = pos
    while (true) {
      var i = scanFrom
      while (i < limit && buffer[i] != 0.toByte()) i++
      if (i < limit) {
        valueStart = pos
        valueEnd = i
        pos = i + 1
        isString = true
        return
      }
      val scanned = limit - pos
      if (!fill()) throw DxfFormatException(UiText.key("error_dxfb_string", code))
      scanFrom = pos + scanned
    }
  }

  private fun readLong(size: Int): Long {
    if (!require(size)) throw DxfFormatException(UiText.key("error_dxfb_value", code))
    var v = 0L
    for (i in size - 1 downTo 0) v = (v shl 8) or (buffer[pos + i].toLong() and 0xFF)
    pos += size
    return v
  }

  override fun double(): Double = when {
    isReal -> real
    !isString -> numeric.toDouble()
    else -> ByteNumbers.parseDouble(buffer, valueStart, valueEnd)
      ?: throw DxfFormatException(UiText.key("error_dxfb_not_number", lineNumber, valueAsAscii(), code))
  }

  override fun intOrNull(): Int? = when {
    isReal -> real.toInt()
    !isString -> numeric.toInt()
    else -> ByteNumbers.parseInt(buffer, valueStart, valueEnd)
  }

  override fun int(): Int = intOrNull()
    ?: throw DxfFormatException(UiText.key("error_dxfb_not_int", lineNumber, valueAsAscii(), code))

  override fun valueEquals(expected: ByteArray): Boolean {
    if (!isString) return false
    var start = valueStart
    var end = valueEnd
    while (start < end && buffer[start].isSpace()) start++
    while (end > start && buffer[end - 1].isSpace()) end--
    if (end - start != expected.size) return false
    for (i in expected.indices) {
      if (buffer[start + i] != expected[i]) return false
    }
    return true
  }

  override fun valueAsAscii(): String = when {
    isString -> latin1(buffer, valueStart, valueEnd)
    isReal -> real.toString()
    else -> numeric.toString()
  }

  // гарантирует n непрочитанных байтов в буфере с позиции pos
  private fun require(n: Int): Boolean {
    while (limit - pos < n) {
      if (!fill()) return false
    }
    return true
  }

  // сдвигает хвост в начало, дочитывает; буфер растёт, если полон
  private fun fill(): Boolean {
    if (eof) return false
    if (pos > 0) {
      buffer.copyInto(buffer, destinationOffset = 0, startIndex = pos, endIndex = limit)
      limit -= pos
      pos = 0
    }
    if (limit == buffer.size) buffer = buffer.copyOf(buffer.size * 2)
    val read = input.read(buffer, limit, buffer.size - limit)
    if (read < 0) {
      eof = true
      return false
    }
    limit += read
    bytesRead += read
    return true
  }

  private companion object {
    const val DEFAULT_BUFFER_SIZE = 256 * 1024
    val SIGNATURE = byteArrayOf(
      *"AutoCAD Binary DXF".encodeToByteArray(), '\r'.code.toByte(), '\n'.code.toByte(), 0x1A, 0,
    )

    const val KIND_STRING = 0
    const val KIND_DOUBLE = 1
    const val KIND_INT16 = 2
    const val KIND_INT32 = 3
    const val KIND_INT64 = 4
    const val KIND_BYTE = 5
    const val KIND_CHUNK = 6

    fun valueKind(code: Int): Int = when (code) {
      in 10..59, in 110..149, in 210..239, in 460..469, in 1010..1059 -> KIND_DOUBLE
      in 60..79, in 170..179, in 270..289, in 370..389, in 400..409, in 1060..1070 -> KIND_INT16
      in 90..99, in 420..429, in 440..459, 1071 -> KIND_INT32
      in 160..169 -> KIND_INT64
      in 290..299 -> KIND_BYTE
      in 310..319, 1004 -> KIND_CHUNK
      else -> KIND_STRING
    }
  }
}
