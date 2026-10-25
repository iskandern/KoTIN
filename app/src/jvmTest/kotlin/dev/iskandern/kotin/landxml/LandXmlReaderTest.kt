package dev.iskandern.kotin.landxml

import org.junit.Test
import dev.iskandern.kotin.io.ByteArrayInput
import dev.iskandern.kotin.dxf.DxfDocumentReader
import dev.iskandern.kotin.dxf.DxfFlattener
import dev.iskandern.kotin.model.GrowingIntArray
import dev.iskandern.kotin.model.PointMark
import dev.iskandern.kotin.model.SceneGeometry
import dev.iskandern.kotin.model.SceneObject
import dev.iskandern.kotin.model.UiText
import dev.iskandern.kotin.ru
import dev.iskandern.kotin.scene.buildRenderScene
import dev.iskandern.kotin.io.DrawingFormat
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LandXmlReaderTest {

  private val fixtures = File("src/jvmTest/resources/landxml")

  private fun reader(bytes: ByteArray): LandXmlReader {
    val detected = DrawingFormat.detect(ByteArrayInput(bytes))
    assertEquals(DrawingFormat.LANDXML, detected.format)
    return LandXmlReader(detected.input, LandXmlReader.SECTIONS.map { it() }, log = {})
  }

  private fun read(bytes: ByteArray): SceneGeometry = reader(bytes).read()

  private fun assertEmpty(geometry: SceneGeometry) {
    assertTrue(geometry.meshes.isEmpty())
    assertTrue(geometry.lines.isEmpty())
    assertTrue(geometry.labels.isEmpty())
    assertTrue(geometry.points.isEmpty())
    assertTrue(geometry.objects.isEmpty())
    assertFalse(geometry.truncated)
  }

  @Test
  fun `минимальные фикстуры 1_2 и 1_0 — пустая сцена без исключений`() {
    val expected = mapOf(
      "minimal-1.2.xml" to "LandXML 1.2: единицы Metric (meter), проект «Минимальный проект», программа «KoTIN 1.0»",
      "minimal-1.0.xml" to "LandXML 1.0: единицы Imperial (foot), проект «Minimal 1.0», программа «KoTIN 1.0»",
    )
    for ((name, summary) in expected) {
      val reader = reader(File(fixtures, name).readBytes())
      val geometry = reader.read()
      assertEquals(summary, ru(reader.summary))
      assertEmpty(geometry)
      // пустую сцену можно отдать на отрисовку, как пустой DXF
      buildRenderScene(geometry)
    }
  }

  @Test
  fun `корень не LandXML и оборванный файл — ошибка`() {
    assertFailsWith<XmlFormatException> { LandXmlReader(ByteArrayInput("<svg/>".encodeToByteArray())).read() }
    assertFailsWith<XmlFormatException> { read("<LandXML version=\"1.2\"><Units>".encodeToByteArray()) }
  }

  @Test
  fun `пустая фикстура читается быстрее пустого DXF`() {
    val landXml = File(fixtures, "minimal-1.2.xml").readBytes()
    val bareLandXml = "<LandXML version=\"1.2\"/>".encodeToByteArray()
    val dxf = "  0\nSECTION\n  2\nHEADER\n  0\nENDSEC\n  0\nSECTION\n  2\nENTITIES\n  0\nENDSEC\n  0\nEOF\n".encodeToByteArray()
    // оба — как в приложении: определение формата, затем свой читатель
    fun landXmlNs(bytes: ByteArray) = measure { read(bytes) }
    fun dxfNs() = measure {
      DxfFlattener(DxfDocumentReader(DrawingFormat.detect(ByteArrayInput(dxf)).input).read()).flatten()
    }
    repeat(3000) {
      landXmlNs(landXml)
      landXmlNs(bareLandXml)
      dxfNs()
    }
    val landXmlUs = (0 until 1000).minOf { landXmlNs(landXml) } / 1000.0
    val bareUs = (0 until 1000).minOf { landXmlNs(bareLandXml) } / 1000.0
    val dxfUs = (0 until 1000).minOf { dxfNs() } / 1000.0
    // время не проверяется — шумит; печатается для отчёта
    println("пустой LandXML: фикстура (${landXml.size} Б) %.1f мкс, один корень (${bareLandXml.size} Б) %.1f мкс; пустой DXF (${dxf.size} Б) %.1f мкс"
      .format(landXmlUs, bareUs, dxfUs))
  }

  private inline fun measure(block: () -> Unit): Long {
    val start = System.nanoTime()
    block()
    return System.nanoTime() - start
  }

  @Test
  fun `раздел из реестра читает свой элемент, порядок разделов любой`() {
    val names = XmlNames("Item")
    val section = object : LandXmlSection("Things", names) {
      var items = 0
      override fun read(xml: XmlTokenizer, scene: LandXmlSceneBuilder) {
        val depth = xml.depth
        while (xml.nextChild(depth)) {
          if (xml.nameId == 0) items++
          xml.skipElement()
        }
      }
    }
    // Item внутри неизвестного раздела и вложенный глубже — не его; Units после раздела читается именами корня
    val text = """<LandXML version="1.2"><Things><Item/><Other><Item/></Other><Item>1</Item></Things>
      |<Surfaces><Item/></Surfaces><Things><Item/></Things><Units><Metric linearUnit="meter"/></Units></LandXML>""".trimMargin()
    assertEmpty(LandXmlReader(ByteArrayInput(text.encodeToByteArray()), listOf(section)).read())
    assertEquals(3, section.items)
  }

  @Test
  fun `накопитель - отложенные шаги по стадиям, лимит отрезков, высота подписей`() {
    val scene = LandXmlSceneBuilder(maxSegments = 2)
    val order = ArrayList<String>()
    scene.defer(LandXmlSceneBuilder.STAGE_DRAPE) { order.add("посадка") }
    scene.defer(LandXmlSceneBuilder.STAGE_CURVES) {
      order.add("кривые, размер ${scene.size}")
      scene.defer(LandXmlSceneBuilder.STAGE_CURVES) { order.add("кривые ещё") }
    }
    scene.defer(LandXmlSceneBuilder.STAGE_REFERENCES) { order.add("ссылки") }

    val layer = scene.layer("Трасса A", LandXmlPalette.ALIGNMENT, LandXmlGroup.ALIGNMENTS)
    assertEquals(layer, scene.layer("Трасса A", LandXmlPalette.ALIGNMENT, LandXmlGroup.ALIGNMENTS))
    val id = scene.addObject(SceneObject("Alignment", "A", layer))
    val lines = scene.lines(layer, LandXmlPalette.ALIGNMENT, flat = true)
    lines.add(0.0, 0.0, 0.0, 100.0, 0.0, 0.0, id)
    lines.add(100.0, 0.0, 0.0, 100.0, 50.0, 0.0, id)
    lines.add(100.0, 50.0, 0.0, 0.0, 50.0, 0.0, id)
    scene.addPoint(PointMark(layer, LandXmlPalette.POINT, 10.0, 10.0, 0.0))
    scene.addLabel(layer, LandXmlPalette.LABEL, 0.0, 0.0, 0.0, "ПК0")

    val geometry = scene.finish()
    assertEquals(listOf("ссылки", "кривые, размер 100.0", "кривые ещё", "посадка"), order)
    assertTrue(geometry.truncated)
    assertEquals(1, geometry.lines.size)
    assertEquals(2, geometry.lines[0].segmentCount)
    assertEquals(listOf(0, 0), geometry.lines[0].objectIds!!.toList())
    // подписи просмотрщика — постоянного экранного размера (dp), а не доля сцены: при зуме не растут
    assertEquals(LabelScale.height(LabelScale.TEXT_DP), geometry.labels[0].height)
    assertEquals(LandXmlPalette.ALIGNMENT, geometry.layers.single().trueColor)
    assertEquals(1, geometry.objects.size)
  }

  @Test
  fun `точка - север восток отметка в X восток, Y север`() {
    val xml = XmlTokenizer(ByteArrayInput("<P>100.5 200.25 3</P><Q>7 8</Q>".encodeToByteArray()), XmlNames("P"))
    val out = DoubleArray(6)
    xml.next()
    assertEquals(3, LandXmlCoord.readPoint(xml, out))
    xml.next()
    xml.next()
    assertEquals(2, LandXmlCoord.readPoint(xml, out, 3))
    assertEquals(listOf(200.25, 100.5, 3.0, 8.0, 7.0, 0.0), out.toList())
  }

  // нормаль треугольника сцены (вершины a, b, c из coords) по z
  private fun normalZ(coords: DoubleArray, a: Int, b: Int, c: Int): Double {
    val ux = coords[b * 3] - coords[a * 3]
    val uy = coords[b * 3 + 1] - coords[a * 3 + 1]
    val vx = coords[c * 3] - coords[a * 3]
    val vy = coords[c * 3 + 1] - coords[a * 3 + 1]
    return ux * vy - uy * vx
  }

  // TIN из текста: вершины по id через LandXmlCoord, грани — через addTriangle
  private fun readTin(bytes: ByteArray): Pair<DoubleArray, IntArray> {
    val xml = XmlTokenizer(ByteArrayInput(bytes), TIN_NAMES)
    val byId = HashMap<Int, Int>()
    var coords = DoubleArray(0)
    val triangles = GrowingIntArray()
    val point = DoubleArray(3)
    while (xml.next() != XmlTokenizer.EOF) {
      if (xml.event != XmlTokenizer.START) continue
      when (xml.nameId) {
        P -> {
          val id = xml.attrInt("id".encodeToByteArray(), -1)
          LandXmlCoord.readPoint(xml, point)
          byId[id] = coords.size / 3
          coords += point
        }
        F -> {
          if (xml.attrEquals("i".encodeToByteArray(), "1".encodeToByteArray())) continue
          val ids = IntArray(3) { xml.nextNumber(); xml.int() }
          LandXmlCoord.addTriangle(triangles, byId.getValue(ids[0]), byId.getValue(ids[1]), byId.getValue(ids[2]))
        }
      }
    }
    return coords to triangles.toArray()
  }

  @Test
  fun `нормаль горизонтальной грани смотрит в +Z`() {
    // грань — против часовой стрелки в плане «восток — север», как пишут Civil 3D и 3D-Office:
    // 2 (В 0, С 0) → 5 (В 10, С 0) → 3 (В 0, С 10)
    val text = """<LandXML><Surfaces><Surface name="s"><Definition surfType="TIN"><Pnts>
      |<P id="2">0 0 5</P><P id="3">10 0 5</P><P id="5">0 10 5</P></Pnts>
      |<Faces><F>2 5 3</F></Faces></Definition></Surface></Surfaces></LandXML>""".trimMargin()
    val (coords, triangles) = readTin(text.encodeToByteArray())
    assertEquals(listOf(0.0, 0.0, 5.0, 0.0, 10.0, 5.0, 10.0, 0.0, 5.0), coords.toList())
    assertTrue(normalZ(coords, triangles[0], triangles[1], triangles[2]) > 0)
  }

  @Test
  fun `координаты с десятичной запятой читаются, в журнале — сколько таких чисел`() {
    // так пишет Vestra Cad Civil в немецкой локали (образец landxml.org «Cross_Section_Points.xml»)
    val text = """<?xml version="1.0"?>
      <LandXML xmlns="http://www.landxml.org/schema/LandXML-1.1" version="1.1">
        <CgPoints><CgPoint name="1">3394639,42776972 5580312,09152585 184,95</CgPoint><CgPoint name="2">10.5 20.5 3.5</CgPoint></CgPoints>
      </LandXML>"""
    val journal = ArrayList<String>()
    val geometry = LandXmlReader(ByteArrayInput(text.encodeToByteArray()), LandXmlReader.SECTIONS.map { it() }, log = { journal.add(it) }).read()
    assertEquals(2, geometry.points.size)
    val p = geometry.points.first()
    assertEquals(5580312.09152585, p.x, 1e-6)
    assertEquals(3394639.42776972, p.y, 1e-6)
    assertEquals(184.95, p.z, 1e-9)
    assertTrue(journal.any { it == UiText.key("log_landxml_decimal_comma", 3L) }, journal.joinToString("\n"))
  }

  @Test
  fun `версия без атрибута — по пространству имён`() {
    assertNull(LandXmlReader.namespaceVersion(emptyList()))
    val reader = reader("""<LandXML xmlns="http://www.landxml.org/schema/LandXML-1.1"><Units/></LandXML>""".encodeToByteArray())
    assertEmpty(reader.read())
    assertEquals("LandXML 1.1: единицы не указаны, проект «», программа «»", ru(reader.summary))
  }
}
