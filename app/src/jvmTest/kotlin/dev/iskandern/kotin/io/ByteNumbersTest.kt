package dev.iskandern.kotin.io

import java.math.BigDecimal
import java.math.MathContext
import java.util.Locale
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ByteNumbersTest {

  private fun parse(text: String): Double? {
    val bytes = text.toByteArray(Charsets.ISO_8859_1)
    return ByteNumbers.parseDouble(bytes, 0, bytes.size)
  }

  private fun assertSameAsJava(text: String) {
    val expected = text.trim().toDouble()
    val actual = parse(text)
    assertEquals(expected.toBits(), actual?.toBits(), "'$text': ожидалось $expected, получено $actual")
  }

  @Test
  fun `типичные значения DXF совпадают с parseDouble бит-в-бит`() {
    listOf(
      "0", "0.0", "-0.0", "1", "-1", "  10", "2278500.21500000", "351654.61950000", "182.066500",
      "2278501.65966667", "-351661.57466667", ".5", "5.", "+3.25", "0.000001", "123456789012345.6",
      "1e10", "-2.5E-3", "1.0e+2", "12345678901234567890.5", "0.1234567890123456789012345",
    ).forEach(::assertSameAsJava)
  }

  @Test
  fun `случайные координаты совпадают с parseDouble бит-в-бит`() {
    val random = Random(42)
    repeat(200_000) {
      val value = (random.nextDouble() - 0.5) * 2.0 * 10.0.pow(random.nextInt(0, 9))
      val digits = random.nextInt(0, 12)
      assertSameAsJava(String.format(Locale.US, "%.${digits}f", value))
    }
  }

  @Test
  fun `17 и 18 цифр совпадают с parseDouble бит-в-бит`() {
    listOf(
      "2431517.0212789243", "-2431517.0212789243", "487203.31415926535", "100.00013436424412",
      "0.12345678901234567", "-0.000012345678901234567", "9007199254740993", "-9007199254740993",
      "9007199254740992.5", "4503599627370497.5", "2251799813685248.25", "123456789012345678",
      "999999999999999999", "0.999999999999999999", "1.0000000000000001", "1.00000000000000011",
      "18014398509481985", "18014398509481987", "2431517.021278924300", "0000002431517.0212789243",
    ).forEach(::assertSameAsJava)
  }

  @Test
  fun `случайные числа с 16–18 значащими цифрами совпадают с parseDouble бит-в-бит`() {
    val random = Random(17)
    repeat(300_000) {
      val value = (random.nextDouble() - 0.5) * 2.0 * 10.0.pow(random.nextInt(-6, 16))
      val digits = random.nextInt(16, 19)
      assertSameAsJava(BigDecimal(value).round(MathContext(digits)).toPlainString())
    }
  }

  @Test
  fun `середины между соседними double округляются к чётному`() {
    val random = Random(5)
    repeat(100_000) {
      // в [2^52, 2^53) шаг 1, середины — n + 0.5; в [2^53, 2^54) шаг 2, середины — нечётные целые
      val n = (1L shl 52) + random.nextLong(1L shl 52)
      assertSameAsJava("$n.5")
      assertSameAsJava("${(n shl 1) or 1L}")
      assertSameAsJava("-${n shr 1}.25")
      assertSameAsJava("${n shr 1}.75")
    }
  }

  @Test
  fun `экспонента и длинные числа идут медленным путём и тоже совпадают`() {
    listOf(
      "2.431517021278924E6", "-2.4315170212789243e+06", "1.2345678901234567e-05", "2.220446049250313e-16",
      "1e308", "4.9e-324", "1234567890123456789", "12345678901234567890123", "0.00000000000000000000001234567",
      "2431517.02127892431234", "-2431517.02127892431234",
    ).forEach(::assertSameAsJava)
  }

  @Test
  fun `мусор не разбирается`() {
    listOf("", "   ", "-", ".", "abc", "1.2.3", "1,5", "--1").forEach { assertNull(parse(it), "'$it'") }
  }

  @Test
  fun `целые`() {
    fun int(text: String): Int? = text.toByteArray().let { ByteNumbers.parseInt(it, 0, it.size) }
    assertEquals(8, int("  8"))
    assertEquals(-3, int("-3"))
    assertEquals(Int.MAX_VALUE, int("2147483647"))
    assertEquals(Int.MIN_VALUE, int("-2147483648"))
    assertNull(int("2147483648"))
    assertNull(int("1.0"))
    assertNull(int(""))
  }

  private fun Double.pow(n: Int): Double = Math.pow(this, n.toDouble())
}
