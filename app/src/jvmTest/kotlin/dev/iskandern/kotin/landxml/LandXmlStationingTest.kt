package dev.iskandern.kotin.landxml

import dev.iskandern.kotin.dxf.FACE_MONO_BOLD
import dev.iskandern.kotin.dxf.FACE_MONO_ITALIC
import org.junit.Test
import dev.iskandern.kotin.io.ByteArrayInput
import dev.iskandern.kotin.model.NO_TICK
import dev.iskandern.kotin.model.SceneGeometry
import dev.iskandern.kotin.model.TICK_DP
import dev.iskandern.kotin.scene.FACE_CAP_HEIGHT
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Пикетаж трассы: подписи «ПК» и засечки (KoTIN-68). */
class LandXmlStationingTest {

  private fun alignment(elements: String, attrs: String = "", equations: String = "") =
    """<?xml version="1.0"?>
<LandXML xmlns="http://www.landxml.org/schema/LandXML-1.2" version="1.2">
<Alignments><Alignment name="Т1" $attrs><CoordGeom>$elements</CoordGeom>$equations</Alignment></Alignments>
</LandXML>""".encodeToByteArray()

  private fun read(bytes: ByteArray): SceneGeometry =
    LandXmlReader(ByteArrayInput(bytes), listOf(AlignmentsSection(log = {}, stationing = true)), log = {}).read()

  private val line1000 =
    """<Line><Start>0 0</Start><End>0 1000</End></Line>"""

  // строки пикетов: концы — на слое пикетажа, промежуточные — на своём; по порядку вдоль трассы
  private fun labels(g: SceneGeometry) =
    g.labels.filter { (it.layer == StationLabels.LAYER || it.layer == StationLabels.MID_LAYER) && it.text.isNotEmpty() }

  @Test
  fun `высота заглавных моноширинного - как в атласе`() {
    for (face in listOf(FACE_MONO_BOLD, FACE_MONO_ITALIC)) assertEquals(FACE_CAP_HEIGHT[face].toDouble(), LabelScale.MONO_CAP_HEIGHT, 1e-6)
  }

  @Test
  fun `короткая запись - на круглой сотне только номер`() {
    assertEquals("ПК3", StationLabels.short(300.0))
    assertEquals("ПК0", StationLabels.short(0.0))
    assertEquals("ПК50+10", StationLabels.short(5010.0))
    assertEquals("-ПК2", StationLabels.short(-200.0))
  }

  @Test
  fun `имя трассы - курсивом вдоль линии посередине`() {
    val g = read(alignment(line1000))
    val name = g.labels.single { it.text == "Т1" }
    assertEquals(FACE_MONO_ITALIC, name.font.face)
    assertFalse(name.screenHorizontal)
    assertTrue(name.height < 0)
    assertEquals(500.0, name.x, 1e-9)
    assertTrue(name.layer != StationLabels.LAYER)
    // в ряд с подписями пикетов, над засечками
    assertEquals(labels(g).first().liftDp, name.liftDp)
    assertTrue(name.liftDp > TICK_DP)
  }

  @Test
  fun `концы - на слое пикетажа, промежуточные - на своём`() {
    val all = labels(read(alignment(line1000)))
    assertEquals(listOf(all.first(), all.last()), all.filter { it.layer == StationLabels.LAYER })
    assertEquals(all.size - 2, all.count { it.layer == StationLabels.MID_LAYER })
  }

  @Test
  fun `засечки промежуточных пикетов - на слое пикетажа`() {
    val g = read(alignment(line1000))
    val mids = g.labels.filter { it.layer == StationLabels.MID_LAYER }
    assertTrue(mids.all { it.tickRgb == NO_TICK })
    // у каждой подписи промежуточного пикета — засечка без строки в той же точке на слое пикетажа
    val ticks = g.labels.filter { it.layer == StationLabels.LAYER && it.text.isEmpty() }
    assertEquals(mids.map { it.x to it.y }, ticks.map { it.x to it.y })
    assertTrue(ticks.all { it.tickRgb != NO_TICK })
  }

  @Test
  fun `частичный профиль - концы и у нарисованной части`() {
    val profile = """<Profile><ProfAlign name="V"><PVI>100 100</PVI><PVI>400 130</PVI></ProfAlign></Profile>"""
    val g = read(alignment("""<Line><Start>0 0</Start><End>0 500</End></Line>""", equations = profile))
    val ends = labels(g).filter { it.layer == StationLabels.LAYER }.map { it.text }
    assertEquals(listOf("ПК0", "ПК1", "ПК4", "ПК5"), ends)
    // пикет у края профиля — один, без подписи промежуточного
    assertFalse(labels(g).any { it.layer == StationLabels.MID_LAYER && (it.text == "ПК1" || it.text == "ПК4") })
  }

  @Test
  fun `формат подписи`() {
    assertEquals("ПК0+00", StationLabels.format(0.0))
    assertEquals("ПК12+34,56", StationLabels.format(1234.56))
    assertEquals("ПК10+00,5", StationLabels.format(1000.5))
    assertEquals("ПК1+00", StationLabels.format(99.999))
    assertEquals("-ПК0+50", StationLabels.format(-50.0))
  }

  @Test
  fun `английская запись пикета — STA и точка`() {
    val en = StationNotation("STA %1\$s", '.')
    assertEquals("STA 12+34.56", StationLabels.format(1234.56, en))
    assertEquals("STA 0+40", StationLabels.short(40.0, en))
    assertEquals("STA 3", StationLabels.short(300.0, en))
  }

  @Test
  fun `шаг подписей и засечек`() {
    assertEquals(20.0, StationLabels.labelStep(1000.0))
    assertEquals(100.0, StationLabels.labelStep(5000.0))
  }

  @Test
  fun `трасса 1000 м - подписи ПК0 - ПК10 при шаге 100`() {
    val stationing = Stationing(0.0, 1000.0)
    val got = ArrayList<String>()
    stationing.forEachMultiple(100.0) { _, s -> got.add(StationLabels.format(s)) }
    assertEquals((0..10).map { "ПК$it+00" }, got)
  }

  @Test
  fun `подписи пикетажа, начало и конец без имени`() {
    val g = read(alignment(line1000))
    val labels = labels(g)
    // шаг 20 м: 50 промежутков → 49 внутренних + начало и конец
    assertEquals(51, labels.size)
    assertEquals("ПК0", labels.first().text)
    assertEquals("ПК10", labels.last().text)
    // на круглой сотне — только номер пикета, между сотнями — полностью
    assertTrue(labels.any { it.text == "ПК5" })
    assertTrue(labels.any { it.text == "ПК5+20" })
    assertTrue(g.layers.any { it.name == StationLabels.LAYER })
    // засечка — экранная, у самой подписи: отрезков в мире нет; кегль в dp, жирный моноширинный, по центру над точкой
    assertTrue(g.lines.none { it.layer == StationLabels.LAYER })
    for (l in labels) {
      // у промежуточных засечка — отдельно, на слое пикетажа
      assertEquals(if (l.layer == StationLabels.LAYER) LandXmlPalette.ALIGNMENT else NO_TICK, l.tickRgb)
      assertEquals(LandXmlPalette.STATIONING, l.color)
      assertTrue(l.height < 0 && l.liftDp > 0)
      assertEquals(FACE_MONO_BOLD, l.font.face)
      assertEquals(1, l.alignH)
      assertTrue(l.screenHorizontal)
    }
  }

  @Test
  fun `staStart сдвигает номера`() {
    val g = read(alignment(line1000, """staStart="1250.5""""))
    val texts = labels(g).map { it.text }
    assertEquals("ПК12+50,5", texts.first())
    assertEquals("ПК22+50,5", texts.last())
    assertTrue("ПК13" in texts)
  }

  @Test
  fun `StaEquation - номера перескакивают`() {
    val stationing = Stationing(0.0, 1000.0, doubleArrayOf(500.0, 2000.0, 1.0))
    val got = ArrayList<Pair<Double, String>>()
    stationing.forEachMultiple(250.0) { d, s -> got.add(d to StationLabels.format(s)) }
    assertEquals(
      listOf(0.0 to "ПК0+00", 250.0 to "ПК2+50", 500.0 to "ПК20+00", 750.0 to "ПК22+50", 1000.0 to "ПК25+00"),
      got,
    )
    // убывающий участок
    val down = Stationing(0.0, 100.0, doubleArrayOf(50.0, 500.0, -1.0))
    val ds = ArrayList<Double>()
    down.forEachMultiple(10.0) { d, _ -> ds.add(d) }
    // каждое расстояние — один раз, стык на 50 м не задваивается
    assertEquals(listOf(0.0, 10.0, 20.0, 30.0, 40.0, 50.0, 60.0, 70.0, 80.0, 90.0, 100.0), ds.sorted())
  }

  @Test
  fun `угол подписи на повороте читаем`() {
    // трасса идёт на восток, потом на запад-север (направление > 90°)
    val g = read(alignment(
      """<Line><Start>0 0</Start><End>0 500</End></Line><Line><Start>0 500</Start><End>500 0</End></Line>"""))
    val angles = labels(g).map { it.rotationDeg }
    assertTrue(angles.all { it > -90.0 && it <= 90.0 })
    assertEquals(0.0, angles.first(), 1e-9)
    // на второй прямой направление 135° → подпись развёрнута на −45°
    assertEquals(-45.0, angles.last(), 1e-6)
    assertEquals(-45.0, StationLabels.readableAngle(Math.toRadians(135.0)), 1e-9)
    assertEquals(45.0, StationLabels.readableAngle(Math.toRadians(-135.0)), 1e-9)
  }

  @Test
  fun `подписей не больше ста, конец не пропадает`() {
    val g = read(alignment("""<Line><Start>0 0</Start><End>0 100000000</End></Line>"""))
    val labels = labels(g)
    assertEquals(100, labels.size)
    assertEquals("ПК0", labels.first().text)
    assertEquals("ПК1000000", labels.last().text)
  }

  @Test
  fun `шаг - от размера сцены - трасса 1000 м в сцене 5 км - ПК0 - ПК10`() {
    // вторая трасса раздвигает сцену до 5 км: шаг 100 м
    val bytes = """<?xml version="1.0"?>
<LandXML xmlns="http://www.landxml.org/schema/LandXML-1.2" version="1.2">
<Alignments><Alignment name="Т1"><CoordGeom>$line1000</CoordGeom></Alignment>
<Alignment name="Т2"><CoordGeom><Line><Start>0 5000</Start><End>10 5000</End></Line></CoordGeom></Alignment></Alignments>
</LandXML>""".encodeToByteArray()
    val texts = labels(read(bytes)).filter { it.objectId == 0 }.map { it.text }
    assertEquals((0..10).map { "ПК$it" }, texts)
    assertEquals(100.0, StationLabels.labelStep(5000.0, 1000.0))
    // трасса длиннее сцены (извилистая) — шаг крупнее, чтобы подписей было не больше ста
    assertEquals(250.0, StationLabels.labelStep(1000.0, 20000.0))
  }

  @Test
  fun `с профилем - на отметках профиля, без профиля - плоские`() {
    val profile = """<Profile><ProfAlign name="П"><PVI>0 100</PVI><PVI>1000 200</PVI></ProfAlign></Profile>"""
    val g = read(alignment(line1000, equations = profile))
    // трасса идёт на восток (в LandXML — «север восток»): расстояние = x, отметка 100 + 0,1·x
    val labels = labels(g)
    assertTrue(labels.size > 2)
    for (l in labels) assertEquals(100 + 0.1 * l.x, l.z, 1e-6, l.text)
  }
}
