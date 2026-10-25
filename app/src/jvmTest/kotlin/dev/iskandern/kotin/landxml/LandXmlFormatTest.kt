package dev.iskandern.kotin.landxml

import kotlinx.coroutines.runBlocking
import org.junit.Test
import dev.iskandern.kotin.DrawingSource
import dev.iskandern.kotin.io.ByteArrayInput
import dev.iskandern.kotin.dxf.DxfDocumentReader
import dev.iskandern.kotin.dxf.DxfFlattener
import dev.iskandern.kotin.parseGeometryInline
import dev.iskandern.kotin.io.DrawingFormat
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LandXmlFormatTest {

  private fun isLandXml(bytes: ByteArray) = DrawingFormat.detect(ChunkedInput(bytes, 7)).format == DrawingFormat.LANDXML

  private fun isLandXml(text: String) = isLandXml(text.encodeToByteArray())

  private val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

  private fun utf16(text: String, bigEndian: Boolean): ByteArray {
    val units = text.encodeToByteArray().decodeToString().toCharArray()
    val out = ByteArray(2 + units.size * 2)
    out[0] = if (bigEndian) 0xFE.toByte() else 0xFF.toByte()
    out[1] = if (bigEndian) 0xFF.toByte() else 0xFE.toByte()
    for ((i, c) in units.withIndex()) {
      val hi = (c.code shr 8).toByte()
      val lo = c.code.toByte()
      out[2 + i * 2] = if (bigEndian) hi else lo
      out[3 + i * 2] = if (bigEndian) lo else hi
    }
    return out
  }

  private val landXml12 = """<?xml version="1.0" encoding="UTF-8"?>
    |<LandXML xmlns="http://www.landxml.org/schema/LandXML-1.2" date="2026-01-01" time="00:00:00" version="1.2"></LandXML>
    |""".trimMargin()

  @Test
  fun `LandXML с BOM и без, с префиксом, версии 1_0 и 1_2`() {
    assertTrue(isLandXml(landXml12))
    assertTrue(isLandXml(bom + landXml12.encodeToByteArray()))
    assertTrue(isLandXml("<LandXML version=\"1.0\"/>"))
    assertTrue(isLandXml("\n\r\t <LandXML>"))
    assertTrue(isLandXml("""<?xml version="1.0"?><lx:LandXML xmlns:lx="http://www.landxml.org/schema/LandXML-1.0" version="1.0"/>"""))
    assertTrue(isLandXml(utf16(landXml12, bigEndian = false)))
    assertTrue(isLandXml(utf16(landXml12, bigEndian = true)))
    // комментарий и DOCTYPE перед корнем
    assertTrue(isLandXml("<?xml version=\"1.0\"?>\n<!-- выгрузка -->\n<LandXML\nversion=\"1.2\">"))
  }

  @Test
  fun `не LandXML — DXF`() {
    assertFalse(isLandXml(ByteArray(0)))
    assertFalse(isLandXml("  0\nSECTION\n  2\nHEADER\n  0\nENDSEC\n  0\nEOF\n"))
    assertFalse(isLandXml("AutoCAD Binary DXF\r\n".encodeToByteArray() + byteArrayOf(0x1A, 0) + "<LandXML>".encodeToByteArray()))
    assertFalse(isLandXml("""<?xml version="1.0"?><svg xmlns="http://www.w3.org/2000/svg"/>"""))
    // имя только начинается с LandXML или это атрибут
    assertFalse(isLandXml("<LandXMLx/>"))
    assertFalse(isLandXml("<Root note=\"LandXML\"/>"))
    // DXF, в тексте которого встречается <LandXML: начало файла — не XML
    assertFalse(isLandXml("  0\nSECTION\n  1\n<LandXML>\n"))
  }

  @Test
  fun `корень дальше 4 КБ — DXF`() {
    val padding = "<!--" + "x".repeat(DrawingFormat.HEAD_SIZE) + "-->"
    assertFalse(isLandXml("<?xml version=\"1.0\"?>$padding<LandXML version=\"1.2\"/>"))
    val near = "<!--" + "x".repeat(DrawingFormat.HEAD_SIZE - 40) + "-->"
    assertTrue(isLandXml("<?xml version=\"1.0\"?>$near<LandXML>"))
  }

  @Test
  fun `после определения поток отдаётся с начала`() {
    val bytes = ByteArray(3 * DrawingFormat.HEAD_SIZE + 17) { (it * 31).toByte() }
    val input = DrawingFormat.detect(ChunkedInput(bytes, 1000)).input
    val out = ByteArray(bytes.size + 10)
    var n = 0
    while (true) {
      val read = input.read(out, n, out.size - n)
      if (read < 0) break
      n += read
    }
    assertContentEquals(bytes, out.copyOf(n))
  }

  private fun parse(bytes: ByteArray): Result<String> = runCatching {
    val geometry = runBlocking {
      parseGeometryInline(DrawingSource("файл", bytes.size.toLong()) { ByteArrayInput(bytes) }) {}
    }
    "сетки ${geometry.meshes.size}, линии ${geometry.lines.size}, подписи ${geometry.labels.size}"
  }

  private fun parseAsDxf(bytes: ByteArray): Result<String> = runCatching {
    val geometry = DxfFlattener(DxfDocumentReader(ByteArrayInput(bytes)).read()).flatten()
    "сетки ${geometry.meshes.size}, линии ${geometry.lines.size}, подписи ${geometry.labels.size}"
  }

  @Test
  fun `пустой файл и чужой XML читаются как DXF, как раньше`() {
    val samples = listOf(
      ByteArray(0),
      """<?xml version="1.0"?><svg xmlns="http://www.w3.org/2000/svg"><g/></svg>""".encodeToByteArray(),
      "мусор".encodeToByteArray(),
    )
    for (bytes in samples) {
      val before = parseAsDxf(bytes)
      val after = parse(bytes)
      assertEquals(before.isSuccess, after.isSuccess)
      assertEquals(before.getOrNull(), after.getOrNull())
      assertEquals(before.exceptionOrNull()?.message, after.exceptionOrNull()?.message)
    }
  }

  @Test
  fun `версия по пространству имён`() {
    assertEquals("1.2", LandXmlReader.namespaceVersion(listOf("http://www.landxml.org/schema/LandXML-1.2")))
    assertEquals("1.0", LandXmlReader.namespaceVersion(listOf("http://www.w3.org/2001/XMLSchema-instance", "http://www.landxml.org/schema/LandXML-1.0")))
    assertEquals(null, LandXmlReader.namespaceVersion(listOf("http://example.com")))
  }
}
