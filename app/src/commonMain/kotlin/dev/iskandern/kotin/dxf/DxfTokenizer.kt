package dev.iskandern.kotin.dxf

import dev.iskandern.kotin.io.ByteInput
import dev.iskandern.kotin.io.ByteNumbers
import dev.iskandern.kotin.io.isSpace
import dev.iskandern.kotin.io.latin1
import dev.iskandern.kotin.model.UiText

class DxfFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Поток пар «код группы / значение»; реализации — ASCII ([DxfTokenizer]) и бинарный ([DxfBinaryTokenizer]) DXF. */
interface DxfTokenSource {
  /** Буфер со строковыми значениями; меняется только при [next]. */
  val buffer: ByteArray
  val code: Int
  val valueStart: Int
  val valueEnd: Int
  val lineNumber: Int
  val bytesRead: Long

  /** Переходит к следующей паре. false — данные закончились. */
  fun next(): Boolean
  fun double(): Double
  fun int(): Int

  /** null — значение пустое или не число; для необязательных атрибутов (цвет, толщина), которые можно пропустить. */
  fun intOrNull(): Int?

  /** Значение равно ASCII-строке [expected] (без учёта пробелов по краям). */
  fun valueEquals(expected: ByteArray): Boolean
  fun valueAsAscii(): String
}

/**
 * Читает ASCII DXF парами «код группы / значение» прямо из байтового буфера.
 *
 * Строки не создаются: значение доступно как диапазон [valueStart, valueEnd) в [buffer] до следующего
 * вызова [next]. Числа разбираются из байтов, строки декодируются только по запросу.
 * Служебная часть DXF (коды, числа, имена сущностей) в ASCII, поэтому кодировка файла тут не важна.
 */
class DxfTokenizer(
  private val input: ByteInput,
  initialBufferSize: Int = DEFAULT_BUFFER_SIZE,
) : DxfTokenSource {
  override var buffer = ByteArray(initialBufferSize)
    private set
  private var pos = 0
  private var limit = 0
  private var eof = false

  private var lineStart = 0
  private var lineEnd = 0

  override var code: Int = -1
    private set
  override var valueStart: Int = 0
    private set
  override var valueEnd: Int = 0
    private set

  // номер строки с кодом текущей пары, для сообщений об ошибках
  override var lineNumber: Int = 0
    private set
  private var linesRead = 0
  override var bytesRead: Long = 0
    private set

  /** Переходит к следующей паре. false — данные закончились. */
  override fun next(): Boolean {
    if (!readLine()) return false
    if (isBlank(lineStart, lineEnd) && !hasMoreData()) return false

    lineNumber = linesRead
    code = parseCode(lineStart, lineEnd)
    if (!readLine()) throw DxfFormatException(UiText.key("error_dxf_no_value", lineNumber, code))

    valueStart = lineStart
    valueEnd = lineEnd
    return true
  }

  override fun double(): Double = ByteNumbers.parseDouble(buffer, valueStart, valueEnd)
    ?: throw DxfFormatException(UiText.key("error_dxf_not_number", lineNumber + 1, valueAsAscii(), code))

  /** null — значение пустое или не число; для необязательных атрибутов (цвет, толщина), которые можно пропустить. */
  override fun intOrNull(): Int? = ByteNumbers.parseInt(buffer, valueStart, valueEnd)

  override fun int(): Int = ByteNumbers.parseInt(buffer, valueStart, valueEnd)
    ?: throw DxfFormatException(UiText.key("error_dxf_not_int", lineNumber + 1, valueAsAscii(), code))

  /** Значение равно ASCII-строке [expected] (без учёта пробелов по краям). */
  override fun valueEquals(expected: ByteArray): Boolean {
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

  override fun valueAsAscii(): String = latin1(buffer, valueStart, valueEnd)

  private fun readLine(): Boolean {
    var scanFrom = pos
    while (true) {
      val newline = indexOfNewline(scanFrom)
      if (newline >= 0) {
        setLine(pos, newline)
        pos = newline + 1
        return true
      }
      if (eof) {
        if (pos >= limit) return false
        setLine(pos, limit)
        pos = limit
        return true
      }
      val scanned = limit - pos
      fill()
      scanFrom = pos + scanned
    }
  }

  private fun setLine(start: Int, newlineIndex: Int) {
    lineStart = start
    lineEnd = if (newlineIndex > start && buffer[newlineIndex - 1] == CR) newlineIndex - 1 else newlineIndex
    linesRead++
  }

  private fun indexOfNewline(from: Int): Int {
    val buf = buffer
    for (i in from until limit) {
      if (buf[i] == LF) return i
    }
    return -1
  }

  private fun hasMoreData(): Boolean {
    if (pos < limit) return true
    if (!eof) fill()
    return pos < limit
  }

  // сдвигает непрочитанный хвост в начало буфера и дочитывает; строку длиннее буфера не теряем — растим буфер
  private fun fill() {
    if (pos > 0) {
      buffer.copyInto(buffer, destinationOffset = 0, startIndex = pos, endIndex = limit)
      limit -= pos
      pos = 0
    }
    if (limit == buffer.size) buffer = buffer.copyOf(buffer.size * 2)

    val read = input.read(buffer, limit, buffer.size - limit)
    if (read < 0) {
      eof = true
      return
    }
    limit += read
    bytesRead += read
  }

  private fun parseCode(start: Int, end: Int): Int =
    ByteNumbers.parseInt(buffer, start, end)
      ?: throw DxfFormatException(UiText.key("error_dxf_no_code", linesRead, latin1(buffer, start, end)))

  private fun isBlank(start: Int, end: Int): Boolean {
    for (i in start until end) {
      if (!buffer[i].isSpace()) return false
    }
    return true
  }

  private companion object {
    const val DEFAULT_BUFFER_SIZE = 256 * 1024
    const val LF = '\n'.code.toByte()
    const val CR = '\r'.code.toByte()
  }
}

