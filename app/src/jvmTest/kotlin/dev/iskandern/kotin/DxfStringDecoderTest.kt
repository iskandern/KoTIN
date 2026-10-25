package dev.iskandern.kotin

import dev.iskandern.kotin.dxf.DxfStringDecoder
import kotlin.test.Test
import kotlin.test.assertEquals

class DxfStringDecoderTest {

  private fun decode(text: ByteArray, codePage: String? = null): String {
    val decoder = DxfStringDecoder()
    if (codePage != null) decoder.setCodePage(codePage)
    return decoder.decode(text, 0, text.size)
  }

  private fun decode(text: String, codePage: String? = null) = decode(text.toByteArray(Charsets.ISO_8859_1), codePage)

  @Test
  fun mifShiftJis() = assertEquals("あ", decode("\\M+182A0"))

  @Test
  fun mifOtherPages() {
    assertEquals("中", decode("\\M+5D6D0"))
    assertEquals("한", decode("\\M+3C7D1"))
  }

  @Test
  fun mifInsideText() = assertEquals("a あ b", decode("a \\M+182A0 b"))

  @Test
  fun mifInvalidStaysAsIs() = assertEquals("\\M+1ZZZZ", decode("\\M+1ZZZZ"))

  @Test
  fun dos866() = assertEquals("Привет", decode("Привет".toByteArray(charset("IBM866")), "DOS866"))

  @Test
  fun iso8859() = assertEquals("Привет", decode("Привет".toByteArray(charset("ISO-8859-5")), "ISO8859-5"))

  @Test
  fun unknownCodePageKeepsDefault() = assertEquals("Привет", decode("Привет".toByteArray(charset("windows-1251")), "NO_SUCH"))

  @Test
  fun decimalCode() {
    assertEquals("45°", decode("45%%176"))
    assertEquals("░", decode("%%176", "DOS866"))
  }

  @Test
  fun decimalCodeDoesNotTouchOtherCodes() {
    assertEquals("%%c10", decode("%%c10"))
    assertEquals("%%%100", decode("%%%100"))
    assertEquals("%%12", decode("%%12"))
  }
}
