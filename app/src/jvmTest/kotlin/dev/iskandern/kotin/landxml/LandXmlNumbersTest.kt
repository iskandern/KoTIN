package dev.iskandern.kotin.landxml

import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LandXmlNumbersTest {

  private fun parse(text: String): Double? {
    val bytes = text.toByteArray(Charsets.ISO_8859_1)
    return LandXmlNumbers.parseDouble(bytes, 0, bytes.size).takeUnless { it.isNaN() }
  }

  // не дальше 1 ulp от toDouble()
  private fun assertNear(text: String) {
    val expected = text.trim().toDouble()
    val actual = parse(text)
    assertTrue(
      actual != null && abs(expected.toBits() - actual.toBits()) <= 1,
      "'$text': ожидалось $expected, получено $actual",
    )
  }

  @Test
  fun `типичные значения`() {
    listOf(
      "0", "0.0", "-0.0", "1", "-1", "  10 ", "\n\t5.5\n", ".5", "5.", "+3.25", "-.75", "0.000001", "123456789012345.6",
      "398273.349980979285", "2498791.366516809911", "52.783999999985", "13144.398000000001",
      "1234567890123456789", "12345678901234567890123.5", "0.1234567890123456789", "0.000000000012345678901234567",
    ).forEach(::assertNear)
  }

  @Test
  fun `до 16 цифр — бит-в-бит как toDouble`() {
    val random = Random(7)
    repeat(100_000) {
      val digits = random.nextInt(1, 16)
      val text = randomNumber(random, digits)
      assertEquals(text.toDouble().toBits(), parse(text)?.toBits(), text)
    }
  }

  @Test
  fun `1-19 значащих цифр в пределах 1 ulp`() {
    val random = Random(42)
    repeat(300_000) {
      assertNear(randomNumber(random, random.nextInt(1, 20)))
    }
  }

  @Test
  fun `18-19 значащих цифр без медленного пути`() {
    val random = Random(1)
    val before = LandXmlNumbers.slowPathCount
    repeat(100_000) {
      assertNear(randomNumber(random, random.nextInt(18, 20)))
    }
    assertEquals(0, LandXmlNumbers.slowPathCount - before)
  }

  @Test
  fun `экспонента, INF и мусор — медленный путь`() {
    val before = LandXmlNumbers.slowPathCount
    assertEquals(1000.0, parse("1e3"))
    assertEquals(-0.0025, parse("-2.5E-3"))
    assertEquals(Double.POSITIVE_INFINITY, parse("INF"))
    assertEquals(Double.NEGATIVE_INFINITY, parse("-INF"))
    assertNull(parse("abc"))
    assertNull(parse("1.2.3"))
    assertNull(parse("-"))
    assertNull(parse("   "))
    assertEquals(7, LandXmlNumbers.slowPathCount - before)
  }

  @Test
  fun `десятичная запятая без точки — как точка, со счётчиком`() {
    val before = LandXmlNumbers.commaCount
    assertEquals(parse("3394639.42776972"), parse("3394639,42776972"))
    assertEquals(parse("-184.95"), parse("-184,95"))
    assertEquals(parse("398273.349980979285"), parse("398273,349980979285"))
    assertEquals(3, LandXmlNumbers.commaCount - before)
    // разделитель тысяч, две запятые, запятая без цифр — не число
    assertNull(parse("1,234.5"))
    assertNull(parse("1.234,5"))
    assertNull(parse("1,2,3"))
    assertNull(parse(","))
    assertEquals(3, LandXmlNumbers.commaCount - before)
  }

  @Test
  fun `целые`() {
    fun int(text: String): Int? {
      val bytes = text.toByteArray(Charsets.ISO_8859_1)
      return LandXmlNumbers.parseInt(bytes, 0, bytes.size).takeUnless { it == LandXmlNumbers.NOT_INT }?.toInt()
    }
    assertEquals(0, int("0"))
    assertEquals(42, int(" 42\n"))
    assertEquals(-7, int("-7"))
    assertEquals(Int.MAX_VALUE, int("2147483647"))
    assertEquals(Int.MIN_VALUE, int("-2147483648"))
    assertNull(int("2147483648"))
    assertNull(int("1.5"))
    assertNull(int("П_2"))
    assertNull(int(""))
  }

  // число из [digits] значащих цифр со случайным положением точки, иногда с ведущими нулями после неё
  private fun randomNumber(random: Random, digits: Int): String {
    val sb = StringBuilder()
    if (random.nextInt(4) == 0) sb.append('-')
    val body = StringBuilder()
    body.append('1' + random.nextInt(9))
    repeat(digits - 1) { body.append('0' + random.nextInt(10)) }
    when (val dot = random.nextInt(-3, digits + 1)) {
      in Int.MIN_VALUE..-1 -> sb.append("0.").append("0".repeat(-dot - 1)).append(body)
      0 -> sb.append('.').append(body)
      digits -> sb.append(body)
      else -> sb.append(body, 0, dot).append('.').append(body, dot, body.length)
    }
    return sb.toString()
  }
}
