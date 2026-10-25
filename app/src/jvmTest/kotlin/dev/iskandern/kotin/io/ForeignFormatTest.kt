package dev.iskandern.kotin.io

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ForeignFormatTest {

  private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

  private fun reason(head: ByteArray) =
    assertFailsWith<ForeignFormatException> { DrawingFormat.detect(ByteArrayInput(head)) }.message.orEmpty()

  @Test
  fun `архив, DWG, PDF и картинка узнаются по началу файла`() {
    assertEquals("@error_format_zip", reason("PK".encodeToByteArray() + bytes(3, 4, 20, 0)))
    assertEquals("@error_format_dwg", reason("AC1032\u0000\u0000".encodeToByteArray()))
    assertEquals("@error_format_pdf", reason("%PDF-1.7\n".encodeToByteArray()))
    assertEquals("@error_format_image", reason(bytes(0x89) + "PNG\r\n".encodeToByteArray()))
    assertEquals("@error_format_archive", reason(bytes(0x1F, 0x8B, 8, 0)))
    assertTrue(isForeignFormat(reason("Rar!".encodeToByteArray() + bytes(0x1A, 7))))
  }

  @Test
  fun `DXF, двоичный DXF и LandXML не принимаются за чужой формат`() {
    for (text in listOf("  0\nSECTION\n", "999\nAC1032 comment\n", "AutoCAD Binary DXF\r\n\u001A\u0000", "<?xml version=\"1.0\"?><LandXML/>", "", "P")) {
      val head = text.encodeToByteArray()
      assertNull(foreignFormat(head, head.size), text)
    }
    assertEquals(false, isForeignFormat("@error_dxf_no_code|1|PK"))
  }
}
