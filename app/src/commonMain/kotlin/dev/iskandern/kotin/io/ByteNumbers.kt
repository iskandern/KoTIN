package dev.iskandern.kotin.io

/**
 * Разбор чисел прямо из байтов, без создания String.
 *
 * Для double используется быстрый путь Клингера: если мантисса (все значащие цифры без точки) ≤ 2^53,
 * а знаков после точки ≤ 22, то mantissa / 10^k — одно деление точных double, и результат IEEE
 * округлён корректно, т.е. бит-в-бит совпадает с Double.parseDouble. Это важно: вершины поверхности
 * склеиваются по точному равенству координат.
 *
 * Мантисса больше 2^53 (17–18 цифр, так пишет ezdxf) уже не точна в double. Тогда частное — приближение
 * в пределах пары ulp, и оно доводится до верного double точным сравнением с серединами соседних double
 * в 128-битных целых. Остальное (экспонента, >18 цифр, >22 знаков после точки) — через parseDouble.
 */
internal object ByteNumbers {

  private val POW10 = DoubleArray(MAX_EXACT_POW10 + 1).also { pows ->
    pows[0] = 1.0
    for (i in 1 until pows.size) pows[i] = pows[i - 1] * 10.0
  }

  // 5^22 < 2^52: умещается в Long
  private val POW5 = LongArray(MAX_EXACT_POW10 + 1).also { pows ->
    pows[0] = 1L
    for (i in 1 until pows.size) pows[i] = pows[i - 1] * 5
  }

  fun parseDouble(buf: ByteArray, start: Int, end: Int): Double? {
    var i = start
    var last = end
    while (i < last && buf[i].isSpace()) i++
    while (last > i && buf[last - 1].isSpace()) last--
    if (i == last) return null

    val negative = buf[i] == MINUS
    if (negative || buf[i] == PLUS) i++

    var mantissa = 0L
    var significantDigits = 0
    var fractionDigits = 0
    var hasDigits = false
    var hasDot = false

    while (i < last) {
      val digit = buf[i] - ZERO
      when {
        digit in 0..9 -> {
          hasDigits = true
          if (hasDot) fractionDigits++
          if (mantissa != 0L || digit != 0) significantDigits++
          if (significantDigits > MAX_LONG_DIGITS) return parseSlow(buf, start, end)
          mantissa = mantissa * 10 + digit
        }

        buf[i] == DOT && !hasDot -> hasDot = true

        // экспонента и прочие редкие формы
        else -> return parseSlow(buf, start, end)
      }
      i++
    }

    if (!hasDigits) return null
    if (fractionDigits > MAX_EXACT_POW10) return parseSlow(buf, start, end)

    val value = when {
      mantissa > MAX_EXACT_MANTISSA -> roundExact(mantissa, fractionDigits) ?: return parseSlow(buf, start, end)
      fractionDigits == 0 -> mantissa.toDouble()
      else -> mantissa.toDouble() / POW10[fractionDigits]
    }
    return if (negative) -value else value
  }

  /**
   * Верно округлённое w / 10^k при w > 2^53, k ≤ 22. Приближение отличается от ответа на пару ulp; двигаемся
   * по соседним double, пока x = w / 10^k не окажется между серединами, при равенстве — к чётной мантиссе.
   */
  private fun roundExact(w: Long, k: Int): Double? {
    var bits = (w.toDouble() / POW10[k]).toRawBits()
    repeat(4) {
      val up = compareWithUpperMid(w, k, bits)
      if (up > 0 || up == 0 && bits and 1L != 0L) {
        bits++
        return@repeat
      }
      val down = compareWithUpperMid(w, k, bits - 1)
      if (down < 0 || down == 0 && bits and 1L != 0L) {
        bits--
        return@repeat
      }
      return Double.fromBits(bits)
    }
    return null
  }

  /**
   * Знак x − mid, где x = w / 10^k, а mid = (2m + 1)·2^(e−1) — середина между положительным нормальным double
   * [bits] = m·2^e и следующим. Сравниваются целые: w против (2m + 1)·5^k·2^s, s = e − 1 + k.
   */
  private fun compareWithUpperMid(w: Long, k: Int, bits: Long): Int {
    val m = (bits and MANTISSA_MASK) or HIDDEN_BIT
    val e = (bits ushr MANTISSA_BITS).toInt() - EXPONENT_BIAS
    val b = 2 * m + 1
    val hi = multiplyHigh(b, POW5[k])
    val lo = b * POW5[k]
    val s = e - 1 + k
    return if (s >= 0) -compareShifted(hi, lo, s, 0L, w) else compareShifted(0L, w, -s, hi, lo)
  }

  /** Сравнивает без знака 128-битные (aHi:aLo)·2^shift и (bHi:bLo); a ≠ 0. */
  private fun compareShifted(aHi: Long, aLo: Long, shift: Int, bHi: Long, bLo: Long): Int {
    val aBits = if (aHi != 0L) 128 - aHi.countLeadingZeroBits() else 64 - aLo.countLeadingZeroBits()
    if (aBits + shift > 128) return 1
    val hi = when {
      shift == 0 -> aHi
      shift < 64 -> (aHi shl shift) or (aLo ushr (64 - shift))
      else -> aLo shl (shift - 64)
    }
    val lo = if (shift < 64) aLo shl shift else 0L
    return if (hi != bHi) compareUnsigned(hi, bHi) else compareUnsigned(lo, bLo)
  }

  /** Старшие 64 бита беззнакового произведения a·b. */
  private fun multiplyHigh(a: Long, b: Long): Long {
    val aLo = a and LOW_32
    val aHi = a ushr 32
    val bLo = b and LOW_32
    val bHi = b ushr 32
    val low = aLo * bLo
    val mid1 = aHi * bLo + (low ushr 32)
    val mid2 = aLo * bHi + (mid1 and LOW_32)
    return aHi * bHi + (mid1 ushr 32) + (mid2 ushr 32)
  }

  private fun compareUnsigned(a: Long, b: Long): Int = (a xor Long.MIN_VALUE).compareTo(b xor Long.MIN_VALUE)

  fun parseInt(buf: ByteArray, start: Int, end: Int): Int? {
    var i = start
    var last = end
    while (i < last && buf[i].isSpace()) i++
    while (last > i && buf[last - 1].isSpace()) last--
    if (i == last) return null

    val negative = buf[i] == MINUS
    if (negative || buf[i] == PLUS) i++
    if (i == last) return null

    var value = 0L
    while (i < last) {
      val digit = buf[i] - ZERO
      if (digit !in 0..9) return null
      value = value * 10 + digit
      if (value > Int.MAX_VALUE.toLong() + 1) return null
      i++
    }

    val signed = if (negative) -value else value
    if (signed !in Int.MIN_VALUE..Int.MAX_VALUE) return null
    return signed.toInt()
  }

  private fun parseSlow(buf: ByteArray, start: Int, end: Int): Double? = try {
    latin1(buf, start, end).trim().toDouble()
  } catch (e: NumberFormatException) {
    null
  }

  private const val MINUS = '-'.code.toByte()
  private const val PLUS = '+'.code.toByte()
  private const val DOT = '.'.code.toByte()
  private const val ZERO = '0'.code.toByte()

  private const val MAX_EXACT_POW10 = 22
  private const val MAX_EXACT_MANTISSA = 1L shl 53
  private const val MAX_LONG_DIGITS = 18

  private const val MANTISSA_BITS = 52
  private const val MANTISSA_MASK = (1L shl MANTISSA_BITS) - 1
  private const val HIDDEN_BIT = 1L shl MANTISSA_BITS
  private const val EXPONENT_BIAS = 1075
  private const val LOW_32 = 0xFFFFFFFFL
}

/** Пробел внутри строки DXF: пробел, табуляция, CR. */
internal fun Byte.isSpace(): Boolean = this == ' '.code.toByte() || this == '\t'.code.toByte() || this == '\r'.code.toByte()
