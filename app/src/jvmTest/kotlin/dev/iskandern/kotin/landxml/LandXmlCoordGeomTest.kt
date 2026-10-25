package dev.iskandern.kotin.landxml

import org.junit.Test
import dev.iskandern.kotin.io.ByteArrayInput
import dev.iskandern.kotin.io.DrawingFormat
import dev.iskandern.kotin.model.MAX_SCENE_SEGMENTS
import dev.iskandern.kotin.model.SceneGeometry
import dev.iskandern.kotin.scene.buildRenderScene
import java.io.ByteArrayInputStream
import java.io.File
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LandXmlCoordGeomTest {

  private val fixture = File("src/jvmTest/resources/landxml/coordgeom.xml")
  private class Result(val section: AlignmentsSection, val geometry: SceneGeometry)

  /**
   * Раздел трасс отдельно от читателя: точки `CgPoint` кладутся в таблицу имён накопителя заранее (раздела точек
   * ещё нет — KoTIN-62), [maxSegments] — лимит накопителя.
   */
  private fun read(bytes: ByteArray, maxSegments: Int = MAX_SCENE_SEGMENTS): Result {
    val section = AlignmentsSection(log = {})
    val scene = LandXmlSceneBuilder(maxSegments)
    for ((name, p) in staxCgPoints(bytes)) scene.pointNames.add(name, p[1], p[0], p.getOrElse(2) { 0.0 })
    val xml = XmlTokenizer(ByteArrayInput(bytes), section.names)
    while (xml.next() != XmlTokenizer.EOF) {
      if (xml.event == XmlTokenizer.START && xml.depth == 2 && xml.localName() == "Alignments") section.read(xml, scene)
    }
    return Result(section, scene.finish())
  }

  private fun geometry(result: Result, name: String): AlignmentGeometry =
    result.section.geometries[result.section.alignmentNames.indexOf(name)]

  // все отрезки трассы [objectId] подряд: x1 y1 x2 y2
  private fun segments(geometry: SceneGeometry, objectId: Int): List<DoubleArray> {
    val batch = geometry.lines.single { it.layer == AlignmentsSection.LAYER }
    val ids = batch.objectIds!!
    return (0 until batch.segmentCount).filter { ids[it] == objectId }.map {
      val c = batch.coords
      doubleArrayOf(c[it * 6], c[it * 6 + 1], c[it * 6 + 3], c[it * 6 + 4])
    }
  }

  @Test
  fun `фикстура - прямая, дуги, ломаные, ссылки, уравнения пикетажа`() {
    val result = read(fixture.readBytes())
    val geometry = result.geometry
    val names = result.section.alignmentNames
    assertEquals(listOf("Прямая", "Дуга ccw", "Дуга cw", "Без центра", "Ломаные", "Уравнения", "Спираль"), names)
    // один пакет трасс на слой и цвет, объект на трассу, подпись с именем у начала; пикетаж — на своём слое
    val lines = geometry.lines.filter { it.layer == AlignmentsSection.LAYER }
    assertEquals(1, lines.size)
    assertTrue(lines[0].flat)
    assertTrue(geometry.lines.all { it.layer == AlignmentsSection.LAYER || it.layer == StationLabels.LAYER })
    assertEquals(names, geometry.objects.map { it.name })
    assertTrue(geometry.objects.all { it.kind == "Alignment" })
    // имя трассы — один раз, курсивом посередине на слое трасс; на концах пикетажа — только пикет
    assertEquals(names, geometry.labels.filter { it.layer == AlignmentsSection.LAYER }.map { it.text })
    val starts = names.indices.map { id -> geometry.labels.first { it.layer == StationLabels.LAYER && it.objectId == id } }
    assertTrue(starts.all { it.text.startsWith("ПК") || it.text.startsWith("-ПК") })
    // подпись пикетажа стоит над точкой трассы (подъём — на экране)
    assertEquals(0.0, starts[0].x, 10.0)
    assertEquals(350.0, starts[1].x, 10.0)

    val straight = geometry(result, "Прямая")
    assertEquals(100.0, straight.length, 1e-9)
    assertEquals(listOf("100", "100", "1"), geometry.objects[0].attributes.take(3).map { it.value })
    assertEquals("только Line", geometry.objects[0].attributes.last().value)
    val mid = assertNotNull(straight.at(150.0))
    assertEquals(50.0, mid.x, 1e-9)
    assertEquals(0.0, mid.y, 1e-9)
    assertEquals(0.0, mid.direction, 1e-12)
    assertNull(straight.at(99.0))
    assertNull(straight.at(201.0))

    // дуги: концы ровно на End, длина — R·угол
    val quarter = 50 * PI / 2
    for ((index, name) in listOf(1 to "Дуга ccw", 2 to "Дуга cw")) {
      val arc = geometry(result, name)
      assertEquals(quarter, arc.length, 1e-9, name)
      val segs = segments(geometry, index)
      assertTrue(segs.size >= 4, name)
      for (k in 1 until segs.size) {
        assertEquals(segs[k - 1][2], segs[k][0], 0.0)
        assertEquals(segs[k - 1][3], segs[k][1], 0.0)
      }
      val end = if (name == "Дуга ccw") doubleArrayOf(300.0, 50.0) else doubleArrayOf(550.0, 0.0)
      assertEquals(end[0], segs.last()[2], 1e-6, name)
      assertEquals(end[1], segs.last()[3], 1e-6, name)
      // вершины — на окружности
      val cx = if (name == "Дуга ccw") 300.0 else 500.0
      for (s in segs) assertEquals(50.0, hypot(s[2] - cx, s[3]), 1e-6, name)
    }
    // середина дуги и направление касательной
    val ccwMid = assertNotNull(geometry(result, "Дуга ccw").at(quarter / 2))
    assertEquals(300 + 50 * kotlin.math.cos(PI / 4), ccwMid.x, 1e-9)
    assertEquals(50 * kotlin.math.sin(PI / 4), ccwMid.y, 1e-9)
    assertEquals(3 * PI / 4, ccwMid.direction, 1e-9)
    val cwMid = assertNotNull(geometry(result, "Дуга cw").at(quarter / 2))
    assertEquals(500 + 50 * kotlin.math.cos(PI / 4), cwMid.x, 1e-9)
    assertEquals(-PI / 4, cwMid.direction, 1e-9)

    // без центра: обе дуги — четверти R 50, третья — хорда
    val noCenter = geometry(result, "Без центра")
    assertEquals(3, noCenter.elementCount)
    assertEquals(quarter, noCenter.elementLength(0), 1e-9)
    assertEquals(quarter, noCenter.elementLength(1), 1e-9)
    assertEquals(50 * kotlin.math.sqrt(2.0), noCenter.elementLength(2), 1e-9)
    assertEquals(CoordGeom.KIND_CHORD, noCenter.elementKind(2))
    for (s in segments(geometry, 3).take(segments(geometry, 3).size - 1)) {
      val cx = if (s[0] > 675) 700.0 else 600.0
      assertEquals(50.0, hypot(s[2] - cx, s[3]), 1e-6)
    }

    // IrregularLine — 2 отрезка (концы не дублируются), Chain A–B–C — 2, Line C–A — 1
    val polylines = geometry(result, "Ломаные")
    assertEquals(5, segments(geometry, 4).size)
    assertEquals(4 * hypot(10.0, 10.0) + 20.0, polylines.length, 1e-9)
    val chainStart = assertNotNull(polylines.atDistance(2 * hypot(10.0, 10.0)))
    assertEquals(900.0, chainStart.x, 1e-9)

    // StaEquation: с 100 м пикеты от 1000 вверх, с 150 м — от 2000 вниз
    val equations = geometry(result, "Уравнения").stationing
    assertEquals(50.0, equations.station(50.0), 1e-9)
    assertEquals(1020.0, equations.station(120.0), 1e-9)
    assertEquals(1970.0, equations.station(180.0), 1e-9)
    assertEquals(50.0, equations.distance(50.0), 1e-9)
    assertEquals(120.0, equations.distance(1020.0), 1e-9)
    assertEquals(180.0, equations.distance(1970.0), 1e-9)
    assertTrue(equations.distance(500.0).isNaN())
    assertEquals(170.0, assertNotNull(geometry(result, "Уравнения").at(1980.0)).x, 1e-9)

    // спираль — длина из атрибута, конец на End (KoTIN-64; кривая — LandXmlSpiralTest)
    val spiral = geometry(result, "Спираль")
    assertEquals(50.0, spiral.length, 0.0)
    assertEquals(49.0, assertNotNull(spiral.atDistance(50.0)).x, 1e-9)
    assertEquals(-190.0, assertNotNull(spiral.atDistance(50.0)).y, 1e-9)
    buildRenderScene(geometry)
  }

  @Test
  fun `число сегментов дуги растёт с радиусом и убывает с размером сцены`() {
    val quarter = PI / 2
    val small = CoordGeom.arcSteps(quarter, 10.0, 1000.0)
    val big = CoordGeom.arcSteps(quarter, 1000.0, 1000.0)
    assertTrue(big > small, "$small → $big")
    val inSmallScene = CoordGeom.arcSteps(quarter, 100.0, 200.0)
    val inBigScene = CoordGeom.arcSteps(quarter, 100.0, 50_000.0)
    assertTrue(inSmallScene > inBigScene, "$inSmallScene → $inBigScene")
    assertEquals(16, CoordGeom.arcSteps(quarter, 100.0, 0.0))
    assertTrue(CoordGeom.arcSteps(1e-6, 100.0, 200.0) >= 2)
  }

  @Test
  fun `формат чисел атрибутов`() {
    assertEquals("0", AlignmentsSection.format(0.0))
    assertEquals("4028.545", AlignmentsSection.format(4028.544831))
    assertEquals("-1.5", AlignmentsSection.format(-1.5))
    assertEquals("12000000", AlignmentsSection.format(1.2e7))
  }

  // --- независимый расчёт по StAX: только формулы, без кода читателя ---

  private class ReferenceAlignment(val name: String, val length: Double, val elements: List<Double>)

  private fun stax(bytes: ByteArray) = XMLInputFactory.newInstance().apply {
    setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
  }.createXMLStreamReader(ByteArrayInputStream(bytes))

  private fun numbers(text: String) = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.map { it.toDouble() }

  private fun staxCgPoints(bytes: ByteArray): Map<String, List<Double>> {
    val reader = stax(bytes)
    val result = LinkedHashMap<String, List<Double>>()
    while (reader.hasNext()) {
      if (reader.next() == XMLStreamConstants.START_ELEMENT && reader.localName == "CgPoint") {
        val name = reader.getAttributeValue(null, "name")
        result[name] = numbers(reader.elementText)
      }
    }
    return result
  }

  /**
   * Трассы файла: длина элемента по независимым формулам — прямая по Start/End, дуга — радиус |Start − Center|,
   * угол между лучами на Start и End с учётом rot.
   */
  private fun staxAlignments(bytes: ByteArray): List<ReferenceAlignment> {
    val reader = stax(bytes)
    val result = ArrayList<ReferenceAlignment>()
    var name = ""
    var length = 0.0
    var elements = ArrayList<Double>()
    var element = ""
    var rot = ""
    val points = HashMap<String, List<Double>>()
    var inCoordGeom = false
    while (reader.hasNext()) {
      when (reader.next()) {
        XMLStreamConstants.START_ELEMENT -> when (val local = reader.localName) {
          "Alignment" -> {
            name = reader.getAttributeValue(null, "name")
            length = reader.getAttributeValue(null, "length").toDouble()
            elements = ArrayList()
          }
          "CoordGeom" -> inCoordGeom = true
          "Line", "Curve" -> if (inCoordGeom) {
            element = local
            rot = reader.getAttributeValue(null, "rot").orEmpty()
            points.clear()
          }
          "Start", "End", "Center" -> if (inCoordGeom && element.isNotEmpty()) points[local] = numbers(reader.elementText)
        }
        XMLStreamConstants.END_ELEMENT -> when (reader.localName) {
          "Alignment" -> result.add(ReferenceAlignment(name, length, elements))
          "CoordGeom" -> inCoordGeom = false
          "Line" -> if (inCoordGeom) {
            val s = points.getValue("Start")
            val e = points.getValue("End")
            elements.add(hypot(e[0] - s[0], e[1] - s[1]))
            element = ""
          }
          "Curve" -> if (inCoordGeom) {
            // север, восток → угол в плане «восток — север»
            val s = points.getValue("Start")
            val c = points.getValue("Center")
            val e = points.getValue("End")
            val r = hypot(s[0] - c[0], s[1] - c[1])
            var angle = atan2(e[0] - c[0], e[1] - c[1]) - atan2(s[0] - c[0], s[1] - c[1])
            if (rot == "cw") angle = -angle
            while (angle <= 0) angle += 2 * PI
            elements.add(r * angle)
            element = ""
          }
        }
      }
    }
    return result
  }

  @Test
  fun `независимый расчёт проверяет сам себя на фикстуре`() {
    val quarter = 50 * PI / 2
    // только элементы, которые эталон умеет: Line и Curve с Center
    val text = fixture.readText().replace(Regex("(?s)<Alignment name=\"Без центра\".*</Alignments>"), "</Alignments>")
    val reference = staxAlignments(text.encodeToByteArray()).associateBy { it.name }
    assertEquals(listOf(quarter), reference.getValue("Дуга ccw").elements)
    assertEquals(quarter, reference.getValue("Дуга cw").elements.single(), 1e-12)
    assertTrue(abs(reference.getValue("Прямая").elements.single() - 100.0) < 1e-12)
  }
}
