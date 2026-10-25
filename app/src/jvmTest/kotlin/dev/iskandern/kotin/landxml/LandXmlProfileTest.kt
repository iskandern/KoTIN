package dev.iskandern.kotin.landxml

import org.junit.Test
import dev.iskandern.kotin.io.ByteArrayInput
import dev.iskandern.kotin.model.MAX_SCENE_SEGMENTS
import dev.iskandern.kotin.model.SceneGeometry
import java.io.File
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.max
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import dev.iskandern.kotin.ru

/** Профиль трассы `ProfAlign` (KoTIN-66): отметки по формулам, стыки, пикетаж, 3D-трасса, сверка с эталоном. */
class LandXmlProfileTest {

  private val fixture = File("src/jvmTest/resources/landxml/profile.xml")
  private class Result(val section: AlignmentsSection, val geometry: SceneGeometry, val log: List<String>) {
    fun index(name: String) = section.alignmentNames.indexOf(name).also { assertTrue(it >= 0, name) }
    fun profile(name: String) = assertNotNull(section.profiles[index(name)], name)
    fun alignment(name: String) = section.geometries[index(name)]
  }

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

  private val result by lazy { read(fixture.readBytes()) }

  // вершины отрезков объекта [id]: x y z, по порядку
  private fun vertices(geometry: SceneGeometry, id: Int): List<DoubleArray> = geometry.lines.filter { it.layer == AlignmentsSection.LAYER }.flatMap { batch ->
    val ids = batch.objectIds!!
    val c = batch.coords
    (0 until batch.segmentCount).filter { ids[it] == id }.flatMap {
      listOf(doubleArrayOf(c[it * 6], c[it * 6 + 1], c[it * 6 + 2]), doubleArrayOf(c[it * 6 + 3], c[it * 6 + 4], c[it * 6 + 5]))
    }
  }

  @Test
  fun `два уклона и парабола между ними - отметка по формуле`() {
    val p = result.profile("Парабола")
    // z = zНачала + g1·x + (g2 − g1)·x² / (2L): начало 150 (отметка 130), g1 0,2, g2 −0,1, L 200
    fun formula(x: Double) = 130 + 0.2 * x - 0.3 * x * x / 400
    assertEquals(100.0, p.elevation(0.0), 1e-12)
    assertEquals(110.0, p.elevation(50.0), 1e-12)
    for (x in listOf(0.0, 37.5, 100.0, 150.0, 200.0)) assertEquals(formula(x), p.elevation(150 + x), 1e-9, "x $x")
    assertEquals(142.5, p.elevation(250.0), 1e-9)
    assertEquals(140.0, p.elevation(350.0), 1e-9)
    assertEquals(135.0, p.elevation(400.0), 1e-9)
    assertEquals(125.0, p.elevation(500.0), 1e-12)
    assertEquals(0.2, p.grade(150.0), 1e-12)
    assertEquals(0.05, p.grade(250.0), 1e-12)
    assertEquals(-0.1, p.grade(350.0), 1e-12)
  }

  @Test
  fun `несимметричная кривая - две параболы с общей касательной на PVI`() {
    val p = result.profile("Несимметричная")
    // g1 −0,1, g2 +0,15, l1 80, l2 160 у PVI 200 (отметка 80): поправка на PVI e = Δg·l1·l2 / (2(l1 + l2))
    val e = 0.25 * 80 * 160 / (2 * 240)
    assertEquals(88.0, p.elevation(120.0), 1e-9)
    assertEquals(84.0 + e / 4, p.elevation(160.0), 1e-9)
    assertEquals(80.0 + e, p.elevation(200.0), 1e-9)
    assertEquals(92.0 + e / 4, p.elevation(280.0), 1e-9)
    assertEquals(104.0, p.elevation(360.0), 1e-9)
    // уклон в стыке — (g1·l1 + g2·l2) / (l1 + l2)
    assertEquals((-0.1 * 80 + 0.15 * 160) / 240, p.grade(200.0), 1e-12)
    assertEquals(-0.1, p.grade(120.0), 1e-12)
    assertEquals(0.15, p.grade(360.0), 1e-12)
  }

  @Test
  fun `CircCurve - дуга окружности, касательная к уклонам`() {
    val p = result.profile("Круговая")
    val r = 1000.0
    val a1 = atan(0.2)
    val a2 = atan(-0.1)
    val delta = a1 - a2
    // концы — в R·tg(Δ/2) от PVI по уклонам
    val t = r * kotlin.math.tan(delta / 2)
    val begin = 250 - t * cos(a1)
    val end = 250 + t * cos(a2)
    assertEquals(100 + 0.2 * begin, p.elevation(begin), 1e-9)
    assertEquals(150 - 0.1 * (end - 250), p.elevation(end), 1e-9)
    assertEquals(0.2, p.grade(begin), 1e-9)
    assertEquals(-0.1, p.grade(end), 1e-9)
    // радиус окружности через три точки дуги — R, ниже PVI
    for (x in listOf(begin + 5, 250.0, end - 5)) {
      val ax = x - 5; val az = p.elevation(ax)
      val bz = p.elevation(x)
      val cx = x + 5; val cz = p.elevation(cx)
      val ab = kotlin.math.hypot(5.0, bz - az)
      val bc = kotlin.math.hypot(5.0, cz - bz)
      val ac = kotlin.math.hypot(10.0, cz - az)
      val area2 = abs((x - ax) * (cz - az) - (bz - az) * (cx - ax))
      assertEquals(r, ab * bc * ac / (2 * area2), 1e-3, "x $x")
    }
    assertTrue(p.elevation(250.0) < 150.0)
    // точно, а не парабола: в середине дуги расхождение с параболой той же длины заметно
    val para = AlignmentProfile("", result.alignment("Круговая").stationing, doubleArrayOf(
      0.0, 100.0, 0.0, Double.NaN, Double.NaN,
      250.0, 150.0, AlignmentProfile.PARA.toDouble(), end - begin, Double.NaN,
      500.0, 125.0, 0.0, Double.NaN, Double.NaN,
    ))
    assertTrue(abs(para.elevation(250.0) - p.elevation(250.0)) > 1e-3)
  }

  @Test
  fun `отметка и уклон непрерывны во всех стыках`() {
    val h = 0.01
    for ((k, p) in result.section.profiles.withIndex()) {
      if (p == null) continue
      val name = result.section.alignmentNames[k]
      var x = p.start
      var z = p.elevation(x)
      var g = p.grade(x)
      var maxDz = 0.0
      var maxDg = 0.0
      while (x + h <= p.end) {
        x += h
        val z1 = p.elevation(x)
        val g1 = p.grade(x)
        maxDz = max(maxDz, abs(z1 - z))
        maxDg = max(maxDg, abs(g1 - g))
        z = z1
        g = g1
      }
      // уклоны до 0,2 — шаг даёт до 0,002 м; кривизна до 0,3/80 — изменение уклона до 4e-5
      assertTrue(maxDz <= 0.2 * h + 1e-9, "$name: скачок отметки $maxDz")
      assertTrue(maxDg <= 4e-5, "$name: скачок уклона $maxDg")
    }
  }

  @Test
  fun `пикет за пределами профиля - отметки нет, трасса не строится`() {
    val p = result.profile("Частичный")
    assertEquals("Частичный_V", p.name)
    assertTrue(p.elevation(99.0).isNaN())
    assertTrue(p.elevation(401.0).isNaN())
    assertEquals(100.0, p.elevation(100.0), 1e-12)
    assertEquals(115.0, p.elevation(250.0), 1e-12)
    val east = vertices(result.geometry, result.index("Частичный")).map { it[0] }
    assertEquals(100.0, east.min(), 1e-9)
    assertEquals(400.0, east.max(), 1e-9)
    // второй ProfAlign — только в консоль
    assertTrue(result.log.any { "Частичный" in it && "взят первый" in it }, result.log.joinToString("\n"))
  }

  @Test
  fun `профиль со StaEquation - пикеты профиля через уравнение`() {
    val a = result.alignment("Уравнение")
    val p = result.profile("Уравнение")
    // PVI на пикетах 1000, 1100, 2100, 2300 — на расстояниях 0, 100, 300, 500; уклоны 0,2, −0,1, 0,05
    assertEquals(116.25, p.elevationAt(a.stationing, 1100.0), 1e-9)
    assertEquals(115.0, p.elevationAt(a.stationing, 1150.0), 1e-9)
    assertEquals(110.0, p.elevationAt(a.stationing, 2000.0), 1e-9)
    assertEquals(101.875, p.elevationAt(a.stationing, 2100.0), 1e-9)
    assertEquals(110.0, p.elevationAt(a.stationing, 2300.0), 1e-9)
    assertEquals(p.elevation(300.0), p.elevationAt(a.stationing, 2100.0), 0.0)
    // пикета 1500 на трассе нет
    assertTrue(p.elevationAt(a.stationing, 1500.0).isNaN())
  }

  @Test
  fun `трасса с профилем - в 3D на отметках профиля, без профиля - в плане`() {
    val geometry = result.geometry
    assertFalse(geometry.truncated)
    for ((name, pvi) in listOf("Парабола" to 250.0, "Несимметричная" to 200.0, "Круговая" to 250.0)) {
      val p = result.profile(name)
      val v = vertices(geometry, result.index(name))
      assertTrue(v.size > 4, name)
      // трасса — по востоку от 0: расстояние = x
      for (point in v) assertEquals(p.elevation(point[0]), point[2], 1e-9, "$name: x ${point[0]}")
      assertEquals(0.0, v.first()[0], 1e-9)
      assertEquals(500.0, v.last()[0], 1e-9)
      // PVI — вершины
      for (x in listOf(0.0, pvi, 500.0)) assertTrue(v.any { abs(it[0] - x) < 1e-9 }, "$name: нет вершины $x")
    }
    val batches = geometry.lines.filter { it.layer == AlignmentsSection.LAYER }
    val flat = batches.single { it.flat }
    val flatIds = flat.objectIds!!.toSet()
    assertEquals(setOf(result.index("Без профиля")), flatIds)
    assertTrue(batches.single { !it.flat }.objectIds!!.none { it == result.index("Без профиля") })
    // на повороте плана — вершины на дуге, отметка на PVI 200
    val turn = vertices(geometry, result.index("Поворот"))
    for (point in turn.filter { it[0] > 200 + 1e-9 }) assertEquals(100.0, kotlin.math.hypot(point[0] - 200, point[1] - 700), 1e-6)
    assertEquals(120 + (-10 / 157.0796 - 0.1) * 100 / 8, turn.first { abs(it[0] - 200) < 1e-9 && abs(it[1] - 600) < 1e-9 }[2], 1e-9)
    val obj = geometry.objects[result.index("Парабола")]
    assertEquals("Парабола_V", obj.attributes.single { ru(it.name) == "Профиль" }.value)
  }

  @Test
  fun `лимит отрезков работает и для трассы с профилем`() {
    val limited = read(fixture.readBytes(), maxSegments = 5)
    assertTrue(limited.geometry.truncated)
    assertEquals(5, limited.geometry.lines.sumOf { it.segmentCount })
  }

  @Test
  fun `меньше двух PVI - трасса в плане`() {
    val bytes = fixture.readText()
      .replace("<PVI>500 125</PVI>", "")
      .replace("<ParaCurve length=\"200\">250 150</ParaCurve>", "")
      .encodeToByteArray()
    val r = read(bytes)
    assertNull(r.section.profiles[r.index("Парабола")])
    assertTrue(r.log.any { "Парабола" in it && "меньше двух PVI" in it }, r.log.joinToString("\n"))
  }

  // земля (KoTIN-87): рельеф сеткой 100 м, «Ось 1» с проектом и землёй, «Ось 2» только с землёй в два куска
  private val groundFixture = File("src/jvmTest/resources/landxml/synthetic/ground-profile.xml")
  private val ground by lazy { read(groundFixture.readBytes()) }

  // отрезки слоя земли объекта [id]: x y z x y z; [weight] — толщина пакета (линия земли или стойки)
  private fun groundSegments(r: Result, id: Int, weight: Int): List<DoubleArray> =
    r.geometry.lines.filter { it.layer == AlignmentsSection.GROUND_LAYER && it.lineWeight == weight }.flatMap { batch ->
      assertFalse(batch.flat)
      assertEquals(LandXmlPalette.ALIGNMENT_GROUND, batch.color)
      (0 until batch.segmentCount).filter { batch.objectIds!![it] == id }.map { batch.coords.copyOfRange(it * 6, it * 6 + 6) }
    }

  private fun groundId(r: Result, alignment: String): Int =
    r.geometry.objects.indices.single { r.geometry.objects[it].kind == AlignmentsSection.GROUND_KIND &&
      r.geometry.objects[it].attributes.any { a -> ru(a.name) == "Трасса" && a.value == alignment } }

  // отметка земли «Оси 1» по ряду сетки: 100, 103, 99, 102, 100 через 100 м, между ними — прямые
  private fun groundZ(x: Double): Double {
    val z = doubleArrayOf(100.0, 103.0, 99.0, 102.0, 100.0)
    val k = (x / 100).toInt().coerceAtMost(3)
    return z[k] + (z[k + 1] - z[k]) * (x - k * 100) / 100
  }

  @Test
  fun `профиль земли - своя линия на отметках ProfSurf, прорежена до вершин`() {
    val r = ground
    val id = groundId(r, "Ось 1")
    val obj = r.geometry.objects[id]
    assertEquals("Земля", obj.name)
    assertEquals(AlignmentsSection.GROUND_LAYER, obj.layer)
    assertEquals("41", obj.attributes.single { ru(it.name) == "Точек" }.value)
    // точки на прямой между вершинами сетки убраны: остались 5 вершин — 4 отрезка (трасса прямая)
    assertEquals("5", obj.attributes.single { ru(it.name) == "Точек на экране" }.value)
    val line = groundSegments(r, id, AlignmentsSection.GROUND_WEIGHT)
    assertEquals(4, line.size)
    for (s in line) for (end in 0..1) {
      val o = end * 3
      // x — восток, y — север
      assertEquals(100.0, s[o + 1], 1e-9)
      assertEquals(groundZ(s[o]), s[o + 2], 1e-9, "восток ${s[o]}")
    }
    // карточка трассы — число точек земли; земля не на объекте трассы: выбор трассы её не обводит
    val alignment = r.geometry.objects[r.index("Ось 1")]
    assertEquals("41", alignment.attributes.single { ru(it.name) == "Профиль земли, точек" }.value)
    for (batch in r.geometry.lines.filter { it.layer == AlignmentsSection.GROUND_LAYER }) {
      assertTrue(batch.objectIds!!.none { it == r.index("Ось 1") || it == r.index("Ось 2") })
    }
    // проектная линия — как была, на отметках ProfAlign
    val p = r.profile("Ось 1")
    for (v in vertices(r.geometry, r.index("Ось 1"))) assertEquals(p.elevation(v[0]), v[2], 1e-9)
  }

  @Test
  fun `стойки от земли до проекта - на пикетах пикетажа, где разница больше 5 см`() {
    val r = ground
    val stands = groundSegments(r, groundId(r, "Ось 1"), 0)
    val p = r.profile("Ось 1")
    // сцена — две трассы по 400 м: шаг пикетажа 10 м
    val step = StationLabels.labelStep(400.0, 400.0)
    assertEquals(10.0, step)
    assertTrue(stands.size in 30..41, "${stands.size} стоек")
    for (s in stands) {
      // вертикальная: план один, низ — земля, верх — проект
      assertEquals(s[0], s[3], 1e-9)
      assertEquals(s[1], s[4], 1e-9)
      val x = s[0]
      assertEquals(0.0, abs(x / step - Math.round(x / step)), 1e-9, "пикет $x не кратен шагу")
      assertEquals(groundZ(x), s[2], 1e-9)
      assertEquals(p.elevation(x), s[5], 1e-9)
      assertTrue(abs(s[5] - s[2]) > 0.05)
    }
    // без проекта стоек нет
    assertTrue(groundSegments(r, groundId(r, "Ось 2"), 0).isEmpty())
  }

  @Test
  fun `земля без проекта - трасса в плане, земля кусками PntList2D с разрывом`() {
    val r = ground
    assertNull(r.section.profiles[r.index("Ось 2")])
    val flat = r.geometry.lines.single { it.layer == AlignmentsSection.LAYER && it.flat }
    assertEquals(setOf(r.index("Ось 2")), flat.objectIds!!.toSet())
    val line = groundSegments(r, groundId(r, "Ось 2"), AlignmentsSection.GROUND_WEIGHT)
    assertTrue(line.isNotEmpty())
    // между ПК1+50 и ПК2+50 земли нет
    for (s in line) assertFalse(minOf(s[0], s[3]) < 250.0 - 1e-9 && maxOf(s[0], s[3]) > 150.0 + 1e-9, "отрезок ${s[0]}…${s[3]}")
    assertEquals(150.0, line.filter { it[3] <= 150.0 + 1e-9 }.maxOf { it[3] }, 1e-9)
    assertEquals(250.0, line.filter { it[0] >= 250.0 - 1e-9 }.minOf { it[0] }, 1e-9)
  }

  @Test
  fun `прореживание земли - мелкое по допуску уходит, излом остаётся`() {
    // 1001 точка через 1 м: шум ±1 мм и один пик 0,5 м на 500 м; допуск при сцене 1000 м — 0,1 м
    val points = DoubleArray(2002)
    for (i in 0..1000) {
      points[i * 2] = i.toDouble()
      points[i * 2 + 1] = 100.0 + (if (i % 2 == 0) 0.001 else -0.001) + if (i == 500) 0.5 else 0.0
    }
    val p = assertNotNull(AlignmentProfile.ground("EG", Stationing(0.0, 1000.0), points, 1000.0))
    // концы, пик и его подножия 499 и 501
    assertEquals(5, p.size)
    assertEquals(99.999, p.elevation(499.0), 1e-9)
    assertEquals(100.501, p.elevation(500.0), 1e-9)
    assertEquals(100.001, p.elevation(0.0), 1e-9)
    assertEquals(100.001, p.elevation(1000.0), 1e-9)
    assertNull(AlignmentProfile.ground("EG", Stationing(0.0, 1000.0), doubleArrayOf(5.0, 100.0), 1000.0))
  }

  @Test
  fun `отметки подряд - курсор, быстро на длинном профиле`() {
    val n = 20_000
    val pvis = DoubleArray(n * AlignmentProfile.PVI_SIZE)
    for (i in 0 until n) {
      val at = i * AlignmentProfile.PVI_SIZE
      pvis[at] = i * 10.0
      pvis[at + 1] = if (i % 2 == 0) 100.0 else 101.0
      pvis[at + 2] = AlignmentProfile.PARA.toDouble()
      pvis[at + 3] = 4.0
      pvis[at + 4] = Double.NaN
    }
    val length = (n - 1) * 10.0
    val p = AlignmentProfile("", Stationing(0.0, length), pvis)
    val count = 2_000_000
    val started = System.nanoTime()
    var sum = 0.0
    for (k in 0..count) sum += p.elevation(length * k / count)
    val ms = (System.nanoTime() - started) / 1e6
    println("профиль из $n PVI: ${count + 1} отметок подряд за %.0f мс".format(ms))
    assertTrue(sum > 0)
    // с поиском с начала было бы ~2e10 шагов — минуты; курсором — доли секунды
    assertTrue(ms < 5000, "$ms мс")
  }
}
