package dev.iskandern.kotin

import kotlin.test.Test
import kotlin.test.assertEquals

/** Строка под именем файла в карточке: формат по расширению и числа с разрядами. */
class FileMetaTest {

  @Test
  fun `формат по расширению`() {
    assertEquals("LandXML", fileFormat("Карьер_Северный.XML"))
    assertEquals("DXF", fileFormat("план.dxf"))
    assertEquals("", fileFormat("без_расширения"))
  }

  @Test
  fun `разряды через узкий пробел`() {
    assertEquals("504", groupDigits(504))
    assertEquals("1 248", groupDigits(1248))
    assertEquals("12 345 678", groupDigits(12_345_678))
    assertEquals("-1 000", groupDigits(-1000))
  }
}
