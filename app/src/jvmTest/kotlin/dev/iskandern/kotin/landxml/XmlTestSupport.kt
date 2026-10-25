package dev.iskandern.kotin.landxml

import dev.iskandern.kotin.io.ByteInput
import dev.iskandern.kotin.io.latin1
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStreamReader
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamReader
import kotlin.test.assertTrue

/** Отдаёт байты кусками по [chunk] — границы буфера в любом месте. */
class ChunkedInput(private val bytes: ByteArray, private val chunk: Int) : ByteInput {
  private var position = 0

  override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
    if (position >= bytes.size) return -1
    val n = minOf(chunk, length, bytes.size - position)
    bytes.copyInto(buffer, offset, position, position + n)
    position += n
    return n
  }
}

val TIN_NAMES = XmlNames("LandXML", "Surfaces", "Surface", "Definition", "Pnts", "P", "Faces", "F")
const val PNTS = 4
const val P = 5
const val FACES = 6
const val F = 7

private val ID = "id".encodeToByteArray()
private val I = "i".encodeToByteArray()
private val ONE = "1".encodeToByteArray()

data class TinStats(
  var points: Int = 0,
  var faces: Int = 0,
  var invisible: Int = 0,
  var idSum: Long = 0,
  var coordSum: Double = 0.0,
  var vertexSum: Long = 0,
)

/** Читает TIN так, как будет читать раздел: вложенные элементы — сами, неизвестное — skipElement. */
fun readTin(xml: XmlTokenizer): TinStats {
  val stats = TinStats()
  while (xml.next() != XmlTokenizer.EOF) {
    if (xml.event != XmlTokenizer.START) continue
    when (xml.nameId) {
      PNTS -> {
        val depth = xml.depth
        while (xml.nextChild(depth)) {
          if (xml.nameId != P) {
            xml.skipElement()
            continue
          }
          stats.idSum += xml.attrInt(ID, -1)
          repeat(3) {
            assertTrue(xml.nextNumber(), "у P меньше трёх чисел")
            stats.coordSum += xml.double()
          }
          stats.points++
        }
      }
      FACES -> {
        val depth = xml.depth
        while (xml.nextChild(depth)) {
          if (xml.nameId != F) {
            xml.skipElement()
            continue
          }
          if (xml.attrEquals(I, ONE)) stats.invisible++
          repeat(3) {
            assertTrue(xml.nextNumber(), "у F меньше трёх вершин")
            stats.vertexSum += xml.int()
          }
          stats.faces++
        }
      }
    }
  }
  return stats
}

/** Все события текстом: имена, атрибуты, текст; у P — числа как есть (проверка склейки на границах буфера). */
fun transcript(xml: XmlTokenizer): String = buildString {
  while (true) {
    when (xml.next()) {
      XmlTokenizer.START -> {
        append('<').append(xml.localName())
        for ((name, value) in xml.attributes()) append(' ').append(name).append("=[").append(value).append(']')
        append(if (xml.isEmptyElement) "/>" else ">")
        if (xml.nameId == P) {
          append('{')
          while (xml.nextNumber()) append(latin1(xml.buffer, xml.numberStart, xml.numberEnd)).append(';')
          append('}')
        }
      }
      XmlTokenizer.TEXT -> {
        xml.readText()
        append('\'').append(xml.textString()).append('\'')
      }
      XmlTokenizer.END -> append("</").append(xml.localName()).append('>')
      XmlTokenizer.EOF -> break
    }
  }
}

/** Элементы с атрибутами по порядку — по токенизатору. */
fun tokenizerElements(xml: XmlTokenizer): List<String> {
  val result = ArrayList<String>()
  while (xml.next() != XmlTokenizer.EOF) {
    if (xml.event != XmlTokenizer.START) continue
    result.add(xml.localName() + xml.attributes().joinToString("") { (n, v) -> " $n=[$v]" })
  }
  return result
}

/** То же по StAX из JDK. Файл не UTF-8 без объявления (выгрузка «ANSI») StAX сам не читает — ему дают cp1251. */
fun staxElements(bytes: ByteArray): List<String> = try {
  staxElements(newStax().createXMLStreamReader(ByteArrayInputStream(bytes)))
} catch (e: Exception) {
  staxElements(newStax().createXMLStreamReader(InputStreamReader(ByteArrayInputStream(bytes), "windows-1251")))
}

private fun newStax(): XMLInputFactory = XMLInputFactory.newInstance().apply {
  setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
}

private fun staxElements(reader: XMLStreamReader): List<String> {
  val result = ArrayList<String>()
  while (reader.hasNext()) {
    if (reader.next() != XMLStreamConstants.START_ELEMENT) continue
    val sb = StringBuilder(reader.localName)
    for (k in 0 until reader.attributeCount) {
      val prefix = reader.getAttributePrefix(k)
      val name = if (prefix.isNullOrEmpty()) reader.getAttributeLocalName(k) else "$prefix:${reader.getAttributeLocalName(k)}"
      sb.append(' ').append(name).append("=[").append(reader.getAttributeValue(k)).append(']')
    }
    result.add(sb.toString())
  }
  reader.close()
  return result
}
