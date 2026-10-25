package dev.iskandern.kotin.landxml

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Кодировки: UTF-16 с символами вне BMP. */
class XmlEncodingTest {

  private fun read(bytes: ByteArray, chunk: Int = Int.MAX_VALUE): Pair<List<String>, TinStats> {
    val elements = tokenizerElements(XmlTokenizer(ChunkedInput(bytes, chunk), TIN_NAMES))
    val stats = readTin(XmlTokenizer(ChunkedInput(bytes, chunk), TIN_NAMES))
    return elements to stats
  }

  @Test
  fun `UTF-16 с символами вне BMP`() {
    val text = "<LandXML><Surface name=\"Рельеф 😀\"/></LandXML>"
    for (bytes in listOf(text.toByteArray(Charsets.UTF_16), text.toByteArray(Charsets.UTF_16LE))) {
      val xml = XmlTokenizer(ChunkedInput(bytes, 3), TIN_NAMES)
      xml.next()
      xml.next()
      assertEquals("Рельеф 😀", xml.attrString("name"))
    }
  }
}
