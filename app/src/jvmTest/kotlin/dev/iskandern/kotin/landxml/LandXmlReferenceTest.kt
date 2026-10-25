package dev.iskandern.kotin.landxml

import org.junit.Test
import org.xml.sax.ErrorHandler
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import dev.iskandern.kotin.io.ByteArrayInput
import dev.iskandern.kotin.model.LineBatch
import dev.iskandern.kotin.model.SceneGeometry
import dev.iskandern.kotin.model.SceneMesh
import dev.iskandern.kotin.io.DrawingFormat
import java.io.ByteArrayInputStream
import java.io.File
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.transform.stream.StreamSource
import javax.xml.validation.Schema
import javax.xml.validation.SchemaFactory
import kotlin.math.sqrt
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Эталонная сверка чтения LandXML (KoTIN-75): эталон [LandXmlReference] на мини-фикстурах с числами «руками»,
 * сводка сцены [summaryOf], контрольные числа Civil 3D и XSD.
 */
class LandXmlReferenceTest {

  private val fixtures = File("src/jvmTest/resources/landxml")
  private val referenceFixtures = File(fixtures, "reference")
  /** Разделы, которые читатель уже выдаёт в сцену, — с ними сверяется его сводка. */
  private val readerSections = setOf(SURFACES, CONTOURS, CROSS_SECTS)

  private fun reference(name: String) = LandXmlReference.summarize(File(referenceFixtures, name).readBytes())

  private fun readerGeometry(bytes: ByteArray): SceneGeometry {
    val detected = DrawingFormat.detect(ByteArrayInput(bytes))
    assertEquals(DrawingFormat.LANDXML, detected.format)
    return LandXmlReader(detected.input, LandXmlReader.SECTIONS.map { it() }, log = {}).read()
  }

  private fun assertClose(expected: Double, actual: Double, what: String) =
    assertTrue(kotlin.math.abs(expected - actual) <= 1e-9 * maxOf(1.0, kotlin.math.abs(expected)), "$what: $expected ≠ $actual")

  @Test
  fun `эталон считает мини-фикстуру как руками`() {
    val summary = reference("two-surfaces.xml")
    assertEquals(listOf("Квадрат", "Скат"), summary.surfaces.map { it.name })

    val square = summary.surfaces[0]
    assertEquals(5, square.points)
    assertEquals(4, square.faces)
    assertEquals(2, square.visibleFaces)
    assertEquals(1, square.badFaces)
    // точка 12 — только в невидимой грани
    assertEquals(4, square.usedPoints)
    assertClose(100.0, square.area2D, "площадь 2D")
    assertClose(100 * sqrt(2.0), square.area3D, "площадь 3D")
    // первое число P — север, второе — восток
    assertEquals(listOf(1000.0, 2000.0, 0.0, 1010.0, 2010.0, 10.0), square.box.run { listOf(minE, minN, minZ, maxE, maxN, maxZ) })
    assertEquals(listOf(4020.0, 8020.0, 20.0), square.box.run { listOf(sumE, sumN, sumZ) })
    assertEquals(listOf(100.0, 141.4213562373095, 0.0, 10.0), listOf(square.fileArea2D, square.fileArea3D, square.fileElevMin, square.fileElevMax))
    assertTrue(controlMismatches(square).isEmpty(), controlMismatches(square).toString())

    val slope = summary.surfaces[1]
    assertEquals(listOf(3, 1, 1, 0, 3), listOf(slope.points, slope.faces, slope.visibleFaces, slope.badFaces, slope.usedPoints))
    assertClose(6.0, slope.area2D, "площадь 2D")
    assertClose(6 * sqrt(5.0), slope.area3D, "площадь 3D")
    assertNull(slope.fileArea2D)
    assertEquals(listOf(4.0, 3.0, 6.0), slope.box.run { listOf(sumE, sumN, sumZ) })
  }

  @Test
  fun `неверные контрольные числа файла дают расхождение`() {
    val slope = reference("bad-control-numbers.xml").surfaces.single()
    val mismatches = controlMismatches(slope)
    assertEquals(1, mismatches.size, mismatches.toString())
    assertTrue("area2DSurf" in mismatches[0])
  }

  @Test
  fun `кодировка без объявления — UTF-8, если корректен, иначе windows-1251`() {
    val text = "<a>Поверхность</a>"
    assertEquals(text, LandXmlReference.decodeXml(text.toByteArray(Charsets.UTF_8)))
    assertEquals(text, LandXmlReference.decodeXml(text.toByteArray(charset("windows-1251"))))
    assertEquals(text, LandXmlReference.decodeXml(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + text.toByteArray(Charsets.UTF_8)))
    assertEquals(text, LandXmlReference.decodeXml(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)))
    assertEquals(text, LandXmlReference.decodeXml(byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + text.toByteArray(Charsets.UTF_16BE)))
  }

  /** Сцена, которую должен дать читатель на `two-surfaces.xml`: x — восток, y — север, порядок обхода любой. */
  private fun handScene(slopeTop: Double = 6.0) = SceneGeometry(
    meshes = listOf(
      mesh("Квадрат", doubleArrayOf(1000.0, 2000.0, 0.0, 1010.0, 2000.0, 0.0, 1010.0, 2010.0, 10.0, 1000.0, 2010.0, 10.0), intArrayOf(0, 2, 1, 0, 3, 2)),
      mesh("Скат", doubleArrayOf(0.0, 0.0, 0.0, 4.0, 0.0, 0.0, 0.0, 3.0, slopeTop), intArrayOf(0, 2, 1)),
    ),
    lines = emptyList<LineBatch>(), fills = emptyList(), labels = emptyList(), points = emptyList(), layers = emptyList(), truncated = false,
  )

  private fun mesh(layer: String, coords: DoubleArray, triangles: IntArray): SceneMesh {
    val box = Box().apply { for (v in 0 until coords.size / 3) add(coords[v * 3], coords[v * 3 + 1], coords[v * 3 + 2]) }
    return SceneMesh(layer, coords, triangles, box.minE, box.minN, box.minZ, box.maxE, box.maxN, box.maxZ)
  }

  @Test
  fun `сводка сцены, собранной вручную, совпадает с эталоном`() {
    val scene = summaryOf(handScene())
    assertEquals(listOf("Квадрат", "Скат"), scene.surfaces.map { it.name })
    assertNull(scene.surfaces[0].points)
    assertSummaryMatches(reference("two-surfaces.xml"), scene)
  }

  @Test
  fun `подмена одного числа даёт расхождение`() {
    val expected = reference("two-surfaces.xml")
    // отметка одной вершины в сцене: площадь 3D, габариты и сумма отметок
    val wrongZ = summaryMismatches(expected, summaryOf(handScene(slopeTop = 6.5)))
    assertTrue(wrongZ.any { "площадь 3D" in it } && wrongZ.any { "сумма отметок" in it }, wrongZ.toString())
    // одно число в сводке эталона
    val golden = parseSummaryJson(expected.toJson().replace("\"badFaces\": 1", "\"badFaces\": 2"))
    assertEquals(listOf("«Квадрат»: граней с неверной ссылкой 2 ≠ 1"), summaryMismatches(golden, expected))
    assertFailsWith<AssertionError> { assertSummaryMatches(golden, expected) }
    // восток и север перепутаны
    val swapped = handScene().meshes.map { m ->
      val c = m.coords.copyOf()
      for (v in 0 until m.vertexCount) c[v * 3] = m.coords[v * 3 + 1].also { c[v * 3 + 1] = m.coords[v * 3] }
      mesh(m.layer, c, m.triangles)
    }
    val swappedScene = SceneGeometry(swapped, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), false)
    assertTrue(summaryMismatches(expected, summaryOf(swappedScene)).any { "мин. восток" in it })
    // поверхности нет в сцене
    assertTrue(summaryMismatches(expected, summaryOf(SceneGeometry(handScene().meshes.take(1), emptyList(), emptyList(),
      emptyList(), emptyList(), emptyList(), false))).single().startsWith("поверхности:"))
  }

  @Test
  fun `пустая сцена читателя сходится с эталоном пустой фикстуры`() {
    for (name in listOf("minimal-1.2.xml", "minimal-1.0.xml")) {
      val bytes = File(fixtures, name).readBytes()
      val expected = LandXmlReference.summarize(bytes)
      assertTrue(expected.surfaces.isEmpty())
      assertSummaryMatches(expected, summaryOf(readerGeometry(bytes)), setOf(SURFACES), name)
    }
  }

  @Test
  fun `JSON сводки читается обратно без потерь`() {
    val summary = reference("two-surfaces.xml")
    val json = summary.toJson()
    assertEquals(json, parseSummaryJson(json).toJson())
    assertEquals("{\n  \"surfaces\": []\n}\n", LandXmlSummary(emptyList()).toJson())
    assertTrue(parseSummaryJson(LandXmlSummary(emptyList()).toJson()).surfaces.isEmpty())
  }

  @Test
  fun `фикстуры валидны по XSD`() {
    if (schemas.isEmpty()) return
    // пустые Alignments и Surfaces (KoTIN-59) схема не разрешает: в них нужен хотя бы один элемент;
    // в cgpoints.xml Survey внутри CgPoints — нарочно, проверка, что раздел его пропускает
    val knownInvalid = setOf("minimal-1.2.xml", "cgpoints.xml")
    val files = (fixtures.listFiles().orEmpty().toList() + referenceFixtures.listFiles().orEmpty())
      .filter { it.extension == "xml" }.sortedBy { it.name }
    assertTrue(files.size >= 4)
    for (file in files) {
      val errors = xsdErrors(file.readBytes())
      when {
        errors == null -> println("${file.name}: схемы для этого пространства имён нет")
        file.name in knownInvalid -> {
          assertTrue(errors.isNotEmpty(), "${file.name}: стал валиден — убрать из knownInvalid")
          println("${file.name}: не валиден, известно — ${errors.joinToString("; ")}")
        }
        else -> assertTrue(errors.isEmpty(), "${file.name}:\n  " + errors.joinToString("\n  "))
      }
    }
  }

  /**
   * Нарушения схемы по пространству имён корня (1.2 или 2.0); null — схемы нет (1.0, 1.1). Файл отдаётся валидатору
   * байтами: кодировку он определяет сам, поэтому cp1251 без объявления — нарушение.
   */
  private fun xsdErrors(bytes: ByteArray): List<String>? {
    val schema = schemas[rootNamespace(bytes)] ?: return null
    val errors = ArrayList<String>()
    val validator = schema.newValidator().apply {
      setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "")
      setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
      errorHandler = object : ErrorHandler {
        override fun warning(e: SAXParseException) {}
        override fun error(e: SAXParseException) {
          errors.add("строка ${e.lineNumber}: ${e.message}")
        }
        override fun fatalError(e: SAXParseException) = throw e
      }
    }
    try {
      validator.validate(StreamSource(ByteArrayInputStream(bytes)))
    } catch (e: SAXException) {
      errors.add("не разбирается как XML: ${e.message}")
    }
    return errors
  }

  private fun rootNamespace(bytes: ByteArray): String? {
    val xml = XMLInputFactory.newInstance().apply { setProperty(XMLInputFactory.SUPPORT_DTD, false) }
      .createXMLStreamReader(StringReader(LandXmlReference.decodeXml(bytes)))
    try {
      while (xml.hasNext()) if (xml.next() == XMLStreamConstants.START_ELEMENT) return xml.namespaceURI
      return null
    } finally {
      xml.close()
    }
  }

  companion object {
    // нет XSD в third_party — проверка по схеме пропускается
    private val schemas: Map<String, Schema> by lazy {
      val factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI)
      val dir = File("../third_party/landxml")
      listOf("1.2", "2.0").mapNotNull { version ->
        val xsd = File(dir, "LandXML-$version.xsd")
        if (xsd.exists()) "http://www.landxml.org/schema/LandXML-$version" to factory.newSchema(xsd) else null
      }.toMap()
    }
  }
}
