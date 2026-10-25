package dev.iskandern.kotin.landxml

import dev.iskandern.kotin.io.ByteNumbers
import dev.iskandern.kotin.io.latin1

/**
 * Разбор чисел LandXML прямо из байтов.
 *
 * Civil 3D пишет координаты с 18–19 значащими цифрами (`398273.349980979285`), а быстрый путь [ByteNumbers]
 * кончается на 2^53 (≈ 16 цифр). Здесь первые 18 значащих цифр берутся в `Long`, остальные отбрасываются:
 * погрешность — не больше 1 ulp. Для вершин TIN это не важно: они индексируются по `id`, а не склеиваются
 * по значению. Мантисса до 2^53 — быстрый путь Клингера, результат бит-в-бит как у `toDouble()`.
 * Экспонента, `INF` и мусор — медленный путь через строку, он считается в [slowPathCount].
 *
 * Десятичная запятая (`3394639,42776972`, экспорт в немецкой локали) схемой не допускается, но встречается
 * в реальных файлах: одна запятая без точки читается как точка и считается в [commaCount]. Запятая вместе
 * с точкой или две запятые — не число.
 */
internal object LandXmlNumbers {

  /** Сколько чисел ушло на медленный путь — для тестов и замеров. */
  var slowPathCount = 0L

  /** Сколько чисел прочитано с десятичной запятой вместо точки — для журнала. */
  var commaCount = 0L

  private val POW10 = DoubleArray(MAX_POW10 + 1).also { pows ->
    pows[0] = 1.0
    for (i in 1 until pows.size) pows[i] = pows[i - 1] * 10.0
  }

  private val LONG_POW10 = LongArray(MAX_DIGITS + 1).also { pows ->
    pows[0] = 1L
    for (i in 1 until pows.size) pows[i] = pows[i - 1] * 10
  }

  /** Число или NaN, если это не число: без `Double?`, чтобы не упаковывать каждое число горячего цикла. */
  fun parseDouble(buf: ByteArray, start: Int, end: Int): Double {
    var i = start
    var last = end
    while (i < last && isXmlSpace(buf[i])) i++
    while (last > i && isXmlSpace(buf[last - 1])) last--
    if (i == last) return Double.NaN

    val negative = buf[i] == MINUS
    if (negative || buf[i] == PLUS) i++

    var mantissa = 0L
    var digits = 0
    // цифр после точки, вошедших в мантиссу (с ведущими нулями), и отброшенных цифр целой части
    var fraction = 0
    var dropped = 0
    var hasDigits = false
    var hasDot = false
    var hasComma = false

    while (i < last) {
      val b = buf[i]
      val digit = b - ZERO
      if (digit in 0..9) {
        hasDigits = true
        if (mantissa == 0L && digit == 0) {
          if (hasDot) fraction++
        } else if (digits < MAX_DIGITS) {
          mantissa = mantissa * 10 + digit
          digits++
          if (hasDot) fraction++
        } else if (!hasDot) {
          dropped++
        }
      } else if ((b == DOT || b == COMMA) && !hasDot) {
        hasDot = true
        hasComma = b == COMMA
      } else {
        return parseSlow(buf, start, end)
      }
      i++
    }
    if (!hasDigits) return parseSlow(buf, start, end)
    if (hasComma) commaCount++

    val value = when {
      dropped > 0 -> if (dropped <= MAX_POW10) mantissa.toDouble() * POW10[dropped] else return parseSlow(buf, start, end)
      fraction == 0 -> mantissa.toDouble()
      fraction > MAX_POW10 -> return parseSlow(buf, start, end)
      mantissa <= MAX_EXACT_MANTISSA -> mantissa.toDouble() / POW10[fraction]
      fraction <= MAX_DIGITS -> {
        // целая часть отдельно: она точная, а погрешность дробной меньше ulp результата
        val whole = mantissa / LONG_POW10[fraction]
        val rest = mantissa % LONG_POW10[fraction]
        if (whole != 0L) whole.toDouble() + rest.toDouble() / POW10[fraction] else splitFraction(rest, fraction)
      }
      else -> splitFraction(mantissa, fraction)
    }
    return if (negative) -value else value
  }

  /** Целое или [NOT_INT]: без `Int?`, чтобы не упаковывать каждое число горячего цикла. */
  fun parseInt(buf: ByteArray, start: Int, end: Int): Long {
    var i = start
    var last = end
    while (i < last && isXmlSpace(buf[i])) i++
    while (last > i && isXmlSpace(buf[last - 1])) last--
    if (i == last) return NOT_INT

    val negative = buf[i] == MINUS
    if (negative || buf[i] == PLUS) i++
    if (i == last) return NOT_INT

    var value = 0L
    while (i < last) {
      val digit = buf[i] - ZERO
      if (digit !in 0..9) return NOT_INT
      value = value * 10 + digit
      if (value > Int.MAX_VALUE.toLong() + 1) return NOT_INT
      i++
    }
    val signed = if (negative) -value else value
    return if (signed in Int.MIN_VALUE..Int.MAX_VALUE) signed else NOT_INT
  }

  const val NOT_INT = Long.MIN_VALUE

  // число меньше 1 с мантиссой больше 2^53: старшие и младшие 9 цифр делятся порознь, ошибка — в пределах ulp
  private fun splitFraction(value: Long, fraction: Int): Double {
    val high = value / 1_000_000_000L
    val low = value % 1_000_000_000L
    return high.toDouble() / POW10[fraction - 9] + low.toDouble() / POW10[fraction]
  }

  private fun parseSlow(buf: ByteArray, start: Int, end: Int): Double {
    slowPathCount++
    val text = latin1(buf, start, end).trim()
    // бесконечный радиус в LandXML пишут как INF
    return when (text) {
      "INF", "+INF" -> Double.POSITIVE_INFINITY
      "-INF" -> Double.NEGATIVE_INFINITY
      else -> text.toDoubleOrNull() ?: Double.NaN
    }
  }

  private const val MINUS = '-'.code.toByte()
  private const val PLUS = '+'.code.toByte()
  private const val DOT = '.'.code.toByte()
  private const val COMMA = ','.code.toByte()
  private const val ZERO = '0'.code.toByte()

  private const val MAX_POW10 = 22
  private const val MAX_DIGITS = 18
  private const val MAX_EXACT_MANTISSA = 1L shl 53
}

internal fun isXmlSpace(b: Byte): Boolean = b == SPACE || b == TAB || b == LF || b == CR

private const val SPACE = ' '.code.toByte()
private const val TAB = '\t'.code.toByte()
private const val LF = '\n'.code.toByte()
private const val CR = '\r'.code.toByte()
