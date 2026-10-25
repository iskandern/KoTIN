package dev.iskandern.kotin.landxml

import org.junit.Test
import dev.iskandern.kotin.io.ByteArrayInput
import dev.iskandern.kotin.model.MAX_SCENE_SEGMENTS
import dev.iskandern.kotin.model.SceneGeometry
import java.io.File
import kotlin.math.hypot
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import dev.iskandern.kotin.ru

/** Поперечники `CrossSects` (KoTIN-69): положение точек, разрывы, пропуски, сверка с эталоном. */
class LandXmlCrossSectTest {

  private val fixture = File("src/jvmTest/resources/landxml/crosssects.xml")

  private class Result(val geometry: SceneGeometry, val log: List<String>)

  private fun read(bytes: ByteArray, maxSegments: Int = MAX_SCENE_SEGMENTS): Result {
    val log = ArrayList<String>()
    val section = AlignmentsSection(log = { log.add(ru(it)) })
    val scene = LandXmlSceneBuilder(maxSegments)
    val xml = XmlTokenizer(ByteArrayInput(bytes), section.names)
    while (xml.next() != XmlTokenizer.EOF) {
      if (xml.event == XmlTokenizer.START && xml.depth == 2 && xml.localName() == "Alignments") section.read(xml, scene)
    }
    return Result(scene.finish(), log)
  }

  // трасса из геометрии [coordGeom] с поперечниками [sects]
  private fun document(coordGeom: String, sects: String, staStart: Double = 0.0): ByteArray = (
    "<?xml version=\"1.0\"?><LandXML xmlns=\"http://www.landxml.org/schema/LandXML-1.2\" version=\"1.2\"><Alignments>" +
      "<Alignment name=\"A\" length=\"0\" staStart=\"$staStart\"><CoordGeom>$coordGeom</CoordGeom>" +
      "<CrossSects>$sects</CrossSects></Alignment></Alignments></LandXML>"
    ).toByteArray()

  private fun ground(sta: Double, vararg lists: String) =
    "<CrossSect sta=\"$sta\"><CrossSectSurf name=\"Земля\">" + lists.joinToString("") { "<PntList2D>$it</PntList2D>" } +
      "</CrossSectSurf></CrossSect>"

  // вершины ломаных слоя: (x, y, z) концов отрезков
  private fun points(geometry: SceneGeometry, layer: String): List<DoubleArray> {
    val batch = geometry.lines.single { it.layer == layer }
    return (0 until batch.segmentCount * 2).map { doubleArrayOf(batch.coords[it * 3], batch.coords[it * 3 + 1], batch.coords[it * 3 + 2]) }
  }

  private fun assertPoint(x: Double, y: Double, z: Double, p: DoubleArray) {
    assertEquals(x, p[0], 1e-9)
    assertEquals(y, p[1], 1e-9)
    assertEquals(z, p[2], 1e-9)
  }

  @Test
  fun `трасса на восток - смещение +5 уходит на юг`() {
    val r = read(document("<Line><Start>0 0</Start><End>0 100</End></Line>", ground(30.0, "-5 1 5 2")))
    val p = points(r.geometry, CrossSects.GROUND_LAYER)
    assertPoint(30.0, 5.0, 1.0, p[0])
    assertPoint(30.0, -5.0, 2.0, p[1])
  }

  @Test
  fun `трасса на север - смещение +5 уходит на восток`() {
    val r = read(document("<Line><Start>0 0</Start><End>100 0</End></Line>", ground(1040.0, "-5 1 5 2"), staStart = 1000.0))
    val p = points(r.geometry, CrossSects.GROUND_LAYER)
    assertPoint(-5.0, 40.0, 1.0, p[0])
    assertPoint(5.0, 40.0, 2.0, p[1])
  }

  @Test
  fun `на дуге поперечник идёт по радиусу`() {
    // против часовой: центр (0, 0), R 100, от (100, 0) к (0, 100); справа от трассы — наружу
    val curve = "<Curve rot=\"ccw\"><Start>0 100</Start><Center>0 0</Center><End>100 0</End></Curve>"
    for (sta in listOf(10.0, 50.0, 120.0)) {
      val p = points(read(document(curve, ground(sta, "-20 0 0 0 20 0"))).geometry, CrossSects.GROUND_LAYER)
      // точки 0, 1 (=2), 3: смещения −20, 0, 20
      val axis = p[1]
      assertEquals(100.0, hypot(axis[0], axis[1]), 1e-9)
      assertEquals(80.0, hypot(p[0][0], p[0][1]), 1e-9)
      assertEquals(120.0, hypot(p[3][0], p[3][1]), 1e-9)
      // на одном луче из центра
      for (q in listOf(p[0], p[3])) assertEquals(0.0, axis[0] * q[1] - axis[1] * q[0], 1e-6)
      // и дальше по дуге с пикетом
      assertEquals(sta / 100.0, kotlin.math.atan2(axis[1], axis[0]), 1e-9)
    }
  }

  @Test
  fun `два PntList2D - две ломаные, пикет вне трассы пропущен`() {
    val r = read(document("<Line><Start>0 0</Start><End>0 100</End></Line>",
      ground(10.0, "-20 1 -5 2", "5 3 12 4 20 5") + ground(150.0, "-5 1 5 1") + ground(-1.0, "-5 1 5 1")))
    val sects = summaryOf(r.geometry).crossSects
    assertEquals(1, sects.size)
    assertEquals(listOf(2, 3), sects[0].ground.map { it.size })
    assertTrue(sects[0].design.isEmpty())
    assertTrue(r.log.any { "2 поперечников с пикетом вне трассы" in it }, r.log.toString())
    // одна ломаная — 1 + 2 отрезка
    assertEquals(3, r.geometry.lines.single { it.layer == CrossSects.GROUND_LAYER }.segmentCount)
  }

  @Test
  fun `фикстура - три поперечника, слои, объекты, эталон сходится с читателем`() {
    val bytes = fixture.readBytes()
    val r = read(bytes)
    val reference = LandXmlReference.summarize(bytes)
    assertEquals(listOf("Ось" to 1050.0, "Ось" to 1130.0, "Ось" to 2010.0, "Север" to 40.0),
      reference.crossSects.map { it.alignment to it.station })
    assertSummaryMatches(reference, summaryOf(r.geometry), setOf(CROSS_SECTS), fixture.name)

    // числа «руками»: на прямой P = (50, 0), вправо — на юг; проект без точки «уклон–расстояние»
    val first = reference.crossSects[0]
    assertEquals(listOf(2, 3), first.ground.map { it.size })
    assertPoint(50.0, 20.0, 10.5, first.ground[0][0])
    assertPoint(50.0, -20.0, 10.6, first.ground[1][2])
    assertEquals(listOf(5), first.design.map { it.size })
    // после уравнения: расстояние 160, на дуге угол 60/50 рад от (100, 0) вокруг (100, 50); вправо — наружу
    val third = reference.crossSects[2]
    val axis = third.design[0][0]
    assertEquals(50.0, hypot(axis[0] - 100.0, axis[1] - 50.0), 1e-9)
    assertEquals(58.0, hypot(third.design[0][1][0] - 100.0, third.design[0][1][1] - 50.0), 1e-9)
    // трасса «Север»: точка по ссылке пропущена
    assertPoint(294.0, 40.0, 5.0, reference.crossSects[3].design[0][0])
    assertEquals(3, reference.crossSects[3].design[0].size)

    // по пакету на слой, объект на поперечник
    val ground = r.geometry.lines.filter { it.layer == CrossSects.GROUND_LAYER }
    val design = r.geometry.lines.filter { it.layer == CrossSects.DESIGN_LAYER }
    assertEquals(1, ground.size)
    assertEquals(1, design.size)
    assertFalse(ground[0].flat)
    assertEquals(LandXmlPalette.CROSS_SECTION, ground[0].color)
    assertEquals(LandXmlPalette.CROSS_SECTION_DESIGN, design[0].color)
    assertTrue(r.geometry.layers.any { it.name == CrossSects.GROUND_LAYER } && r.geometry.layers.any { it.name == CrossSects.DESIGN_LAYER })
    val objects = r.geometry.objects.filter { it.kind == CrossSects.KIND }
    assertEquals(listOf("Ось ПК 1050", "Ось ПК 1130", "Ось ПК 2010", "Север ПК 40"), objects.map { ru(it.name) })
    assertEquals(listOf("Трасса" to "Ось", "Пикет" to "1050", "Точек" to "10"), objects[0].attributes.map { ru(it.name) to ru(it.value) })
    // только проект — объект на слое проекта
    assertEquals(CrossSects.DESIGN_LAYER, objects[3].layer)

    // пропуски — по сообщению на файл
    assertEquals(1, r.log.count { "уклон–расстояние" in it }, r.log.toString())
    assertEquals(1, r.log.count { "по ссылкам" in it }, r.log.toString())
    // 1500 — между участками пикетажа, 500 — за концом трассы «Север»
    assertTrue(r.log.any { "2 поперечников с пикетом вне трассы" in it }, r.log.toString())
  }

  @Test
  fun `тысяча поперечников - по пакету на слой, лимит отрезков`() {
    val sects = (0 until 1000).joinToString("") { ground(it * 0.1, "-20 1 -10 2 0 3 10 2 20 1") }
    val bytes = document("<Line><Start>0 0</Start><End>0 100</End></Line>", sects)
    val full = read(bytes)
    // трасса и поперечники; засечки пикетажа — у подписей, не отрезками
    assertEquals(listOf(AlignmentsSection.LAYER, CrossSects.GROUND_LAYER), full.geometry.lines.map { it.layer })
    assertEquals(4000, full.geometry.lines.single { it.layer == CrossSects.GROUND_LAYER }.segmentCount)
    assertEquals(1000, full.geometry.objects.count { it.kind == CrossSects.KIND })
    assertFalse(full.geometry.truncated)

    // отрезок трассы + 1000 отрезков поперечников, остальное отброшено
    val limited = read(bytes, maxSegments = 1001)
    assertTrue(limited.geometry.truncated)
    assertEquals(1000, limited.geometry.lines.single { it.layer == CrossSects.GROUND_LAYER }.segmentCount)
  }
}
