package dev.iskandern.kotin.landxml

import org.junit.Test
import dev.iskandern.kotin.io.ByteArrayInput
import dev.iskandern.kotin.model.MAX_SCENE_SEGMENTS
import dev.iskandern.kotin.model.SceneGeometry
import dev.iskandern.kotin.ru
import dev.iskandern.kotin.scene.buildRenderScene
import java.io.File
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Переходные кривые `Spiral` (KoTIN-64): читатель против эталона [SpiralReference] и концов из файла. */
class LandXmlSpiralTest {

  private val fixture = File("src/jvmTest/resources/landxml/spirals.xml")
  private val inf = Double.POSITIVE_INFINITY

  private class Result(val section: AlignmentsSection, val geometry: SceneGeometry, val log: List<String>)

  private fun read(bytes: ByteArray, maxSegments: Int = MAX_SCENE_SEGMENTS): Result {
    val log = ArrayList<String>()
    val section = AlignmentsSection(log = { log.add(ru(it)) })
    val scene = LandXmlSceneBuilder(maxSegments)
    val xml = XmlTokenizer(ByteArrayInput(bytes), section.names)
    while (xml.next() != XmlTokenizer.EOF) {
      if (xml.event == XmlTokenizer.START && xml.depth == 2 && xml.localName() == "Alignments") section.read(xml, scene)
    }
    return Result(section, scene.finish(), log)
  }

  private fun ne(x: Double, y: Double) = "%.9f %.9f".format(Locale.ROOT, y, x)
  private fun r(v: Double) = if (v.isInfinite()) "INF" else v.toString()

  /**
   * Спираль от ([x], [y]) с направлением [theta]: PI на начальной касательной, End — по эталону (или [end]).
   */
  private fun spiral(
    type: String, length: Double, rs: Double, re: Double, rot: String,
    x: Double = 0.0, y: Double = 0.0, theta: Double = 0.0, end: DoubleArray? = null, extra: String = "",
  ): String {
    val sign = if (rot == "cw") -1.0 else 1.0
    val e = end ?: SpiralReference.at(type, length, if (rs.isInfinite()) 0.0 else sign / rs, if (re.isInfinite()) 0.0 else sign / re, x, y, theta, length)
    return "<Spiral length=\"$length\" radiusStart=\"${r(rs)}\" radiusEnd=\"${r(re)}\" rot=\"$rot\" spiType=\"$type\"$extra>" +
      "<Start>${ne(x, y)}</Start><PI>${ne(x + 10 * cos(theta), y + 10 * sin(theta))}</PI><End>${ne(e[0], e[1])}</End></Spiral>"
  }

  private fun document(vararg alignments: List<String>): ByteArray = buildString {
    append("<?xml version=\"1.0\"?><LandXML xmlns=\"http://www.landxml.org/schema/LandXML-1.2\" version=\"1.2\"><Alignments>")
    for ((k, elements) in alignments.withIndex()) {
      append("<Alignment name=\"A$k\" length=\"0\" staStart=\"0\"><CoordGeom>")
      elements.forEach { append(it) }
      append("</CoordGeom></Alignment>")
    }
    append("</Alignments></LandXML>")
  }.encodeToByteArray()

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
  fun `клотоида L 100 R 300 от прямой - поворот L на 2R, конец на End`() {
    val xml = spiral("clothoid", 100.0, inf, 300.0, "ccw")
    val result = read(document(listOf(xml)))
    val alignment = result.section.geometries.single()
    assertEquals(CoordGeom.KIND_SPIRAL, alignment.elementKind(0))
    assertEquals(100.0, alignment.length, 0.0)
    val end = assertNotNull(alignment.at(100.0))
    assertEquals(100.0 / 600, end.direction, 1e-9)
    assertEquals(0.1667, end.direction, 1e-4)
    // End по рядам Френеля: x = L − L⁵/(40R²L²) + …, y = L³/(6RL) − …
    assertEquals(100 - 100.0 / 360, end.x, 1e-3)
    assertEquals(100.0 / 18 - 1.0 / 90.72, end.y, 1e-3)
    // по всей длине — против эталона мелким шагом, в пределах 1 мм; начало касается прямой
    for (s in listOf(0.0, 10.0, 33.3, 50.0, 77.0, 99.0)) {
      val p = assertNotNull(alignment.at(s))
      val q = SpiralReference.at("clothoid", 100.0, 0.0, 1 / 300.0, 0.0, 0.0, 0.0, s)
      assertEquals(q[0], p.x, 1e-3, "s=$s")
      assertEquals(q[1], p.y, 1e-3, "s=$s")
      assertEquals(s * s / 60_000, p.direction, 1e-6, "s=$s")
    }
    // ломаная: непрерывна, кончается ровно на End, вершины на кривой
    val segs = segments(result.geometry, 0)
    assertTrue(segs.size >= 4)
    for (k in 1 until segs.size) assertEquals(segs[k - 1][2], segs[k][0], 0.0)
    assertEquals(end.x, segs.last()[2], 1e-9)
    assertEquals(end.y, segs.last()[3], 1e-9)
    assertTrue(result.log.none { "не попадает" in it }, result.log.toString())
  }

  @Test
  fun `фикстура - эталон, End из файла и читатель сходятся`() {
    val bytes = fixture.readBytes()
    val reference = SpiralReference.spirals(bytes)
    assertEquals(6, reference.size)
    val result = read(bytes)
    assertEquals(listOf("S-клотоида", "Блосс cw", "Косинусоида", "Синусоида", "Кубическая парабола"), result.section.alignmentNames)
    // каждая спираль: в какой трассе и каким элементом
    val places = listOf(0 to 1, 0 to 3, 1 to 0, 2 to 0, 3 to 0, 4 to 0)
    for ((item, place) in reference.zip(places)) {
      val what = "${item.type} ${item.length}"
      // эталон попадает в End файла (фикстура согласована)
      val e = item.at(item.length)
      assertEquals(item.end[0], e[0], 1e-3, what)
      assertEquals(item.end[1], e[1], 1e-3, what)
      // читатель — в тех же точках, что эталон, по всей длине
      val alignment = result.section.geometries[place.first]
      var from = 0.0
      for (k in 0 until place.second) from += alignment.elementLength(k)
      assertEquals(item.length, alignment.elementLength(place.second), 0.0, what)
      for (f in listOf(0.0, 0.25, 0.5, 0.75, 1.0)) {
        val p = assertNotNull(alignment.atDistance(from + f * item.length))
        val q = item.at(f * item.length)
        assertTrue(hypot(p.x - q[0], p.y - q[1]) < 1e-3, "$what, доля $f: ${p.x} ${p.y} / ${q[0]} ${q[1]}")
        assertEquals(0.0, CoordGeom.normalize(p.direction - q[2]), 1e-6, what)
      }
    }
    assertTrue(result.log.none { "не попадает" in it }, result.log.toString())
    // замену кубической параболы клотоидой — в консоль
    assertEquals(1, result.log.count { "cubicParabola" in it && "клотоидой" in it }, result.log.toString())
    buildRenderScene(result.geometry)
  }

  @Test
  fun `спираль - дуга - спираль замыкают поворот`() {
    val result = read(fixture.readBytes())
    val s = result.section.geometries[0]
    assertEquals(5, s.elementCount)
    assertEquals(550.0, s.length, 1e-6)
    // поворот: L/2R + дуга + L/2R
    val turn = 100.0 / 600 + 150.0 / 300 + 100.0 / 600
    assertEquals(turn, assertNotNull(s.atDistance(550.0)).direction, 1e-6)
    // на стыках направление непрерывно, а выходная прямая — по касательной к концу спирали
    var at = 0.0
    for (k in 0 until s.elementCount - 1) {
      at += s.elementLength(k)
      val before = assertNotNull(s.atDistance(at - 1e-7))
      val after = assertNotNull(s.atDistance(at + 1e-7))
      assertEquals(0.0, CoordGeom.normalize(after.direction - before.direction), 1e-6, "стык $k")
      assertTrue(hypot(after.x - before.x, after.y - before.y) < 1e-5, "стык $k")
    }
    // направление монотонно растёт на спиралях и дуге
    var last = -1.0
    for (k in 0..550) {
      val d = assertNotNull(s.atDistance(k.toDouble())).direction
      assertTrue(d >= last - 1e-12, "на $k м: $d < $last")
      last = d
    }
  }

  @Test
  fun `INF в radiusStart и в radiusEnd, оба INF - прямая`() {
    val toArc = spiral("clothoid", 60.0, inf, 120.0, "cw")
    val fromArc = spiral("clothoid", 60.0, 120.0, inf, "cw", y = 100.0, theta = 1.0)
    val both = spiral("bloss", 40.0, inf, inf, "ccw", y = 200.0, theta = 0.5)
    val result = read(document(listOf(toArc), listOf(fromArc), listOf(both)))
    assertTrue(result.section.geometries.all { it.elementKind(0) == CoordGeom.KIND_SPIRAL })
    assertEquals(-60.0 / 240, assertNotNull(result.section.geometries[0].atDistance(60.0)).direction, 1e-9)
    assertEquals(1.0 - 60.0 / 240, assertNotNull(result.section.geometries[1].atDistance(60.0)).direction, 1e-9)
    val line = result.section.geometries[2]
    val mid = assertNotNull(line.atDistance(20.0))
    assertEquals(20 * cos(0.5), mid.x, 1e-6)
    assertEquals(200 + 20 * sin(0.5), mid.y, 1e-6)
    // направление — по PI, записанному с 9 знаками
    assertEquals(0.5, mid.direction, 1e-9)
    assertTrue(result.log.none { "не попадает" in it }, result.log.toString())
  }

  @Test
  fun `все 16 типов - не падают, поворот монотонный, полный поворот L·(κ0+κ1)·½`() {
    val types = Spiral.TYPE_NAMES.toList()
    assertEquals(16, types.size)
    for (rot in listOf("ccw", "cw")) {
      val sign = if (rot == "cw") -1.0 else 1.0
      val result = read(document(*types.map { listOf(spiral(it, 100.0, 500.0, 200.0, rot, theta = 0.2)) }.toTypedArray()))
      for ((k, type) in types.withIndex()) {
        val alignment = result.section.geometries[k]
        assertEquals(CoordGeom.KIND_SPIRAL, alignment.elementKind(0), type)
        // у всех профилей ∫f = ½
        assertEquals(0.2 + sign * 100 * (1 / 500.0 + 1 / 200.0) / 2, assertNotNull(alignment.atDistance(100.0)).direction, 1e-9, type)
        var last = Double.NaN
        for (step in 0..200) {
          val p = assertNotNull(alignment.atDistance(step * 0.5))
          if (!last.isNaN()) assertTrue(sign * (p.direction - last) >= -1e-12, "$type $rot, ${step * 0.5} м")
          last = p.direction
          val q = SpiralReference.at(type, 100.0, sign / 500, sign / 200, 0.0, 0.0, 0.2, step * 0.5)
          assertTrue(hypot(p.x - q[0], p.y - q[1]) < 1e-3, "$type $rot, ${step * 0.5} м")
        }
        assertTrue(segments(result.geometry, k).isNotEmpty(), type)
      }
      // запасной вариант — одно предупреждение на тип: biquadraticParabola и шесть из задачи
      val fallback = result.log.filter { "клотоидой" in it }
      assertEquals(7, fallback.size, fallback.toString())
      for (type in listOf("biquadraticParabola", "cubic\"", "cubicParabola", "japaneseCubic", "radioid", "weinerBogen", "sineHalfWave")) {
        assertEquals(1, fallback.count { type in it }, "$type: $fallback")
      }
      assertTrue(result.log.none { "не попадает" in it }, result.log.toString())
      buildRenderScene(result.geometry)
    }
  }

  @Test
  fun `запасной вариант - одно предупреждение на тип, неизвестный тип - тоже клотоида`() {
    val a = spiral("radioid", 50.0, inf, 100.0, "ccw")
    val b = spiral("radioid", 50.0, 100.0, inf, "ccw", y = 50.0)
    val c = spiral("clothoid", 50.0, inf, 100.0, "ccw", y = 100.0).replace("clothoid", "spline")
    val result = read(document(listOf(a, b), listOf(c)))
    assertEquals(1, result.log.count { "radioid" in it }, result.log.toString())
    assertEquals(1, result.log.count { "неизвестным" in it }, result.log.toString())
    assertEquals(0.25, assertNotNull(result.section.geometries[1].atDistance(50.0)).direction, 1e-9)
  }

  @Test
  fun `конец мимо End - линейная поправка, больше 1 процента - в консоль`() {
    // End сдвинут на 0,3 м (0,6 % длины) — молча; на 2 м — с сообщением; в обоих случаях ломаная кончается на End
    val exact = SpiralReference.at("clothoid", 50.0, 0.0, 0.01, 0.0, 0.0, 0.0, 50.0)
    val near = spiral("clothoid", 50.0, inf, 100.0, "ccw", end = doubleArrayOf(exact[0], exact[1] + 0.3))
    val far = spiral("clothoid", 50.0, inf, 100.0, "ccw", y = 100.0, end = doubleArrayOf(exact[0] + 2, exact[1] + 100))
    val result = read(document(listOf(near), listOf(far)))
    assertEquals(1, result.log.count { "не попадает" in it }, result.log.toString())
    for ((k, end) in listOf(doubleArrayOf(exact[0], exact[1] + 0.3), doubleArrayOf(exact[0] + 2, exact[1] + 100)).withIndex()) {
      val segs = segments(result.geometry, k)
      assertEquals(end[0], segs.last()[2], 1e-6)
      assertEquals(end[1], segs.last()[3], 1e-6)
      val p = assertNotNull(result.section.geometries[k].atDistance(50.0))
      assertEquals(end[0], p.x, 1e-6)
      assertEquals(end[1], p.y, 1e-6)
    }
    // у малой ошибки направление из PI сохранено, середина сдвинута на половину поправки
    val mid = assertNotNull(result.section.geometries[0].atDistance(25.0))
    val q = SpiralReference.at("clothoid", 50.0, 0.0, 0.01, 0.0, 0.0, 0.0, 25.0)
    assertEquals(q[0], mid.x, 1e-6)
    assertEquals(q[1] + 0.15, mid.y, 1e-6)
  }

  @Test
  fun `направление - PI, иначе dirStart в градусах, иначе по хорде`() {
    val exact = SpiralReference.at("clothoid", 50.0, 0.0, 0.01, 0.0, 0.0, PI / 6, 50.0)
    val e = ne(exact[0], exact[1])
    val noPi = "<Spiral length=\"50\" radiusStart=\"INF\" radiusEnd=\"100\" rot=\"ccw\" spiType=\"clothoid\"%s><Start>0 0</Start><End>$e</End></Spiral>"
    val result = read(document(listOf(noPi.format(" dirStart=\"30\"")), listOf(noPi.format("")), listOf(noPi.format(" dirStart=\"0.5236\""))))
    for (k in 0 until 3) {
      val start = assertNotNull(result.section.geometries[k].atDistance(0.0))
      assertEquals(PI / 6, start.direction, 1e-4, "трасса $k")
      val mid = assertNotNull(result.section.geometries[k].atDistance(25.0))
      val q = SpiralReference.at("clothoid", 50.0, 0.0, 0.01, 0.0, 0.0, PI / 6, 25.0)
      assertTrue(hypot(mid.x - q[0], mid.y - q[1]) < 1e-3, "трасса $k")
    }
    // dirStart в радианах не сходится с концами — взято по хорде, без сообщения
    assertTrue(result.log.none { "не попадает" in it }, result.log.toString())
  }

  @Test
  fun `без длины или радиуса - хорда Start–End`() {
    val bad = "<Spiral radiusStart=\"INF\" radiusEnd=\"100\" rot=\"ccw\" spiType=\"clothoid\"><Start>0 0</Start><End>3 4</End></Spiral>"
    val zero = "<Spiral length=\"50\" radiusStart=\"0\" radiusEnd=\"100\" rot=\"ccw\" spiType=\"clothoid\"><Start>0 0</Start><End>3 4</End></Spiral>"
    val result = read(document(listOf(bad), listOf(zero)))
    for (g in result.section.geometries) {
      assertEquals(CoordGeom.KIND_CHORD, g.elementKind(0))
      assertEquals(5.0, g.length, 1e-12)
    }
  }

  @Test
  fun `лимит отрезков действует и для спиралей`() {
    val elements = (0 until 50).map { spiral("bloss", 100.0, inf, 50.0, "ccw", y = it * 50.0) }
    val full = read(document(elements))
    assertFalse(full.geometry.truncated)
    assertTrue(full.geometry.lines.single { it.layer == AlignmentsSection.LAYER }.segmentCount > 100)
    val limited = read(document(elements), maxSegments = 100)
    assertTrue(limited.geometry.truncated)
    assertEquals(100, limited.geometry.lines.single { it.layer == AlignmentsSection.LAYER }.segmentCount)
  }

  @Test
  fun `скорость - 20 тысяч спиралей`() {
    val types = listOf("clothoid", "bloss", "cosine", "sinusoid", "biquadratic")
    val elements = (0 until 20_000).map {
      val t = types[it % types.size]
      spiral(t, 80.0, 400.0, 150.0, if (it % 2 == 0) "ccw" else "cw", x = (it % 100) * 200.0, y = (it / 100) * 200.0)
    }
    val bytes = document(elements)
    repeat(3) { read(bytes) }
    val start = System.nanoTime()
    val result = read(bytes)
    val ms = (System.nanoTime() - start) / 1e6
    println("20000 спиралей (${bytes.size / 1024} КБ): %.1f мс, отрезков ${result.geometry.lines.single { it.layer == AlignmentsSection.LAYER }.segmentCount}".format(ms))
    assertTrue(result.log.none { "не попадает" in it })
  }

  @Test
  fun `эталон - ряды Френеля и мелкий шаг совпадают`() {
    // клотоида целиком (ряды) против той же кривой без 1 нм (шаги)
    for ((k0, k1) in listOf(0.0 to 1 / 300.0, 0.0 to -1 / 50.0, 1 / 120.0 to 0.0)) {
      val series = SpiralReference.at("clothoid", 100.0, k0, k1, 10.0, 20.0, 0.7, 100.0)
      val steps = SpiralReference.at("clothoid", 100.0, k0, k1, 10.0, 20.0, 0.7, 100.0 - 1e-9)
      assertEquals(series[0], steps[0], 1e-6)
      assertEquals(series[1], steps[1], 1e-6)
      assertEquals(series[2], steps[2], 1e-9)
    }
    // кривизна профилей: 0 в начале, 1 в конце, ½ в середине
    for (type in listOf("clothoid", "bloss", "sinusoid", "cosine", "biquadratic", "revBloss")) {
      assertEquals(0.0, SpiralReference.shape(type, 0.0), 1e-12)
      assertEquals(1.0, SpiralReference.shape(type, 1.0), 1e-12)
      assertEquals(0.5, SpiralReference.shape(type, 0.5), 1e-12)
    }
    assertTrue(abs(SpiralReference.shape("bloss", 0.25) - 0.15625) < 1e-12)
  }
}
