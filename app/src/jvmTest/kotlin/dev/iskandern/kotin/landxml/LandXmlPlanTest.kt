package dev.iskandern.kotin.landxml

import org.junit.Test
import dev.iskandern.kotin.io.ByteArrayInput
import dev.iskandern.kotin.model.LineBatch
import dev.iskandern.kotin.model.SceneGeometry
import dev.iskandern.kotin.ru
import dev.iskandern.kotin.scene.buildRenderScene
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Ситуация `PlanFeatures` и участки `Parcels` (KoTIN-65). */
class LandXmlPlanTest {

  private val fixture = File("src/jvmTest/resources/landxml/planfeatures-parcels.xml")

  private val logs = ArrayList<String>()

  // все разделы: ссылки pntRef ищутся по точкам CgPoints
  private fun read(bytes: ByteArray): SceneGeometry {
    val sections = listOf(CgPointsSection(log = {}), PlanFeaturesSection(log = { logs.add(ru(it)) }),
      ParcelsSection(log = { logs.add(ru(it)) }), SurfacesSection(log = {}))
    return LandXmlReader(ByteArrayInput(bytes), sections, log = {}).read()
  }

  private fun summary(geometry: SceneGeometry, kind: String) = summaryOf(geometry).contours.filter { it.kind == kind }

  // поверхность фикстуры: два треугольника (0,0,10) (200,0,12) (200,200,20) и (0,0,10) (200,200,20) (0,200,15)
  private fun relief(x: Double, y: Double) = if (y >= x) 10 + 0.025 * (x + y) else 10 + 0.01 * x + 0.04 * y

  // отрезки объекта по всем пакетам: пакет и x1 y1 z1 x2 y2 z2
  private fun pieces(geometry: SceneGeometry, objectId: Int) = geometry.lines.flatMap { batch ->
    val ids = batch.objectIds!!
    (0 until batch.segmentCount).filter { ids[it] == objectId }.map { i -> batch to DoubleArray(6) { batch.coords[i * 6 + it] } }
  }

  private fun planLength(pieces: List<DoubleArray>) = pieces.sumOf { hypot(it[3] - it[0], it[4] - it[1]) }

  private fun planCount(pieces: List<DoubleArray>) = planSegments(pieces.map { doubleArrayOf(it[0], it[1], it[3], it[4]) })

  // плоское на рельефе: пакет не плоский, отметки концов — рельеф
  private fun onRelief(pieces: List<Pair<LineBatch, DoubleArray>>) = pieces.all { (batch, c) ->
    !batch.flat && abs(c[2] - relief(c[0], c[1])) < 1e-9 && abs(c[5] - relief(c[3], c[4])) < 1e-9
  }

  @Test
  fun `ситуация - ломаные, замкнутость, точки, слои`() {
    val geometry = read(fixture.readBytes())
    val features = summary(geometry, "PlanFeature")
    assertEquals(listOf("Бровка", "Здание", "Ограждение", "Опора освещения", ""), features.map { it.name })
    // бровка — одна открытая ломаная из прямой, дуги и прямой; здание замкнуто, площадь 40 × 30
    assertEquals(listOf(1, 1, 1, 0, 1), features.map { it.contours })
    assertEquals(listOf(false, true, false, false, false), features.map { it.closed })
    assertEquals(1200.0, features[1].area, 1e-9)
    // опоры: две точки текстом и одна по ссылке на CgPoint
    assertEquals(listOf(0, 0, 0, 3, 0), features.map { it.locations })

    val feature = geometry.objects.withIndex().filter { it.value.kind == PlanFeaturesSection.KIND }
    // слой — имя PlanFeature, без имени — «Ситуация»
    assertEquals(listOf("Бровка", "Здание", "Ограждение", "Опора освещения", "Ситуация"), feature.map { ru(it.value.layer) })
    assertEquals(listOf("Описание"), feature[0].value.attributes.map { ru(it.name) })
    // без атрибутов в файле — пустой список
    assertTrue(feature[1].value.attributes.isEmpty())

    // ограждение с отметками — не плоское, отметки свои; плановые линии без отметок сели на поверхность под ними
    // (KoTIN-67): куски по рёбрам на рельефе, проекция на план та же (замкнутость и площадь — в сводке выше)
    val fence = geometry.lines.single { ru(it.layer) == "Ограждение" }
    assertFalse(fence.flat)
    assertEquals(listOf(14.0, 16.0, 16.0, 18.0), (0 until 4).map { fence.coords[it * 3 + 2] })
    for (f in feature.filter { ru(it.value.layer) != "Ограждение" && ru(it.value.layer) != "Опора освещения" }) {
      assertTrue(onRelief(pieces(geometry, f.index)), f.value.layer)
    }
    // ситуация без имени: в плане тот же отрезок (150, 190) → (190, 190)
    val plain = pieces(geometry, feature[4].index).map { it.second }
    assertEquals(1, planCount(plain))
    assertEquals(listOf(150.0, 190.0), plain.first().take(2))
    assertEquals(listOf(190.0, 190.0), plain.last().slice(3..4))

    // точки: x — восток, y — север; без отметки — на рельефе, по ссылке — отметка CgPoint
    val poles = geometry.points.filter { ru(it.layer) == "Опора освещения" }
    assertEquals(listOf(Triple(20.0, 150.0, relief(20.0, 150.0)), Triple(40.0, 160.0, 16.5), Triple(60.0, 170.0, 17.0)), poles.map { Triple(it.x, it.y, it.z) })
    assertTrue(poles.all { it.objectId == feature[3].index })
    assertEquals(List(3) { "Опора освещения" }, geometry.labels.filter { ru(it.layer) == "Опора освещения" }.map { it.text })
  }

  @Test
  fun `участки - замыкание, вложенные, подписи в Center и в центре габаритов`() {
    val geometry = read(fixture.readBytes())
    val parcels = summary(geometry, "Parcel")
    // внешний раньше вложенных
    assertEquals(listOf("Участок 1", "Участок 1а", "Участок 1б"), parcels.map { it.name })
    assertTrue(parcels.all { it.closed && it.contours == 1 })
    // три стороны в файле + замыкающая; дуга в сцене — ломаная, площадь чуть меньше точной
    assertEquals(4800.0, parcels[0].area, 1e-9)
    assertEquals(400.0, parcels[1].area, 1e-9)
    assertEquals(PI * 15 * 15 / 2, parcels[2].area, PI * 15 * 15 / 2 * CONTOUR_AREA_REL)
    assertTrue(logs.any { "участков 3" in it && "замкнуто отрезком 1" in it }, logs.toString())

    // один слой и один пакет на все участки; поверхность под всеми — пакет на рельефе (KoTIN-67)
    val batches = geometry.lines.filter { it.layer == ParcelsSection.LAYER }
    assertEquals(1, batches.size)
    assertEquals(LandXmlPalette.PARCEL, batches[0].color)
    for (id in geometry.objects.indices.filter { geometry.objects[it].kind == ParcelsSection.KIND }) {
      assertTrue(onRelief(pieces(geometry, id)), geometry.objects[id].name)
    }

    val labels = geometry.labels.filter { it.layer == ParcelsSection.LAYER }
    // подписи — только имена (площадь — в свойствах); центры дальше десятой доли габаритов — каждая на своём месте
    assertEquals(listOf("Участок 1", "Участок 1а", "Участок 1б"), labels.map { it.text })
    assertTrue(labels.all { it.liftDp == 0.0 }, labels.map { it.liftDp }.toString())
    // Center текстом, центр габаритов, Center по ссылке
    assertEquals(listOf(130.0 to 150.0, 130.0 to 120.0, 165.0 to 115.0), labels.map { it.x to it.y })

    val first = geometry.objects.first { it.kind == ParcelsSection.KIND }
    assertEquals(
      mapOf("Площадь" to "4800", "Тип" to "Lot", "Класс" to "Жилая", "Описание" to "угловой"),
      first.attributes.associate { ru(it.name) to ru(it.value) },
    )
    assertTrue(geometry.objects.last().attributes.isEmpty())
  }

  @Test
  fun `поверхность под частью ситуации - внутри линии сели, вне остались плоскими, план тот же`() {
    val bytes = fixture.readBytes()
    // только треугольник 1-2-3 (юго-восток, y ≤ x, z = 10 + 0,01·x + 0,04·y): габариты сцены те же, дуги разбиты так же,
    // как при всей поверхности, — план сравнивается с ней
    val partial = read(bytes.decodeToString().replace("<F>1 3 4</F>", "").encodeToByteArray())
    val whole = read(bytes)
    fun plane(x: Double, y: Double) = 10 + 0.01 * x + 0.04 * y
    val level = partial.meshes.single().minZ
    assertEquals(10.0, level)
    val kinds = setOf(PlanFeaturesSection.KIND, ParcelsSection.KIND)
    val ids = partial.objects.indices.filter { partial.objects[it].kind in kinds && ru(partial.objects[it].layer) != "Ограждение" }
    for (id in ids) {
      val name = partial.objects[id].layer + " " + partial.objects[id].name
      val draped = pieces(partial, id)
      val plan = pieces(whole, id).map { it.second }
      // проекция на план не изменилась: отрезков в плане и длина — как на всей поверхности
      assertEquals(planCount(plan), planCount(draped.map { it.second }), name)
      assertEquals(planLength(plan), planLength(draped.map { it.second }), 1e-9, name)
      // внутри — на рельефе в неплоском пакете, вне (y > x) — плоские на нижнем уровне рельефа
      for ((batch, c) in draped) {
        if (batch.flat) {
          assertTrue(c[2] == level && c[5] == level, name)
          assertTrue(c[1] >= c[0] - 1e-9 && c[4] >= c[3] - 1e-9, name)
        } else {
          assertTrue(abs(c[2] - plane(c[0], c[1])) < 1e-9 && abs(c[5] - plane(c[3], c[4])) < 1e-9, name)
          assertTrue(c[1] <= c[0] + 1e-9 && c[4] <= c[3] + 1e-9, name)
        }
      }
    }
    // бровка и участок 1 пересекают край — куски в обоих пакетах; здание внутри; ситуация без имени вне, на поверхности
    // только её конец (190, 190) на ребре — кусок нулевой длины
    fun flags(name: String) = pieces(partial, partial.objects.indexOfFirst { it.name == name })
      .filter { (_, c) -> hypot(c[3] - c[0], c[4] - c[1]) > 1e-9 }.map { it.first.flat }.toSet()
    assertEquals(setOf(true, false), flags("Бровка"))
    assertEquals(setOf(true, false), flags("Участок 1"))
    assertEquals(setOf(false), flags("Здание"))
    assertEquals(setOf(true), flags(""))
    // опора без отметки (20, 150) вне поверхности — на нижнем уровне, подпись при ней — тоже
    assertEquals(level, partial.points.first { ru(it.layer) == "Опора освещения" }.z)
    assertEquals(level, partial.labels.first { ru(it.layer) == "Опора освещения" }.z)
  }

  @Test
  fun `сцена читателя сходится с эталоном, площадь по Гауссу - с атрибутом area`() {
    val bytes = fixture.readBytes()
    val reference = LandXmlReference.summarize(bytes)
    assertEquals(8, reference.contours.size)
    val parcels = reference.contours.filter { it.kind == "Parcel" }
    // точная площадь эталона против area из файла
    assertEquals(4800.0, parcels[0].area, 1e-9)
    assertEquals(400.0, parcels[1].area, 1e-9)
    assertEquals(PI * 15 * 15 / 2, parcels[2].area, 1e-9)
    // бровка: прямая 50, четверть R 20, прямая 60 — не замкнута
    assertFalse(reference.contours[0].closed)
    assertSummaryMatches(reference, summaryOf(read(bytes)), setOf(SURFACES, CONTOURS), "planfeatures-parcels.xml")

    // подмена: незамкнутый участок или потерянная точка дают расхождение
    val broken = read(bytes.decodeToString().replace("<Location>150 20</Location>", "").encodeToByteArray())
    assertTrue(summaryMismatches(reference, summaryOf(broken), setOf(CONTOURS)).any { "точек 3 ≠ 2" in it })
  }

  @Test
  fun `участки с центрами рядом - заголовки столбиком в общей точке`() {
    // три квадрата 100 × 100: у «13» и «13_BA» центры в 5 м (меньше десятой доли диагонали ≈ 14 м) — один столбик,
    // «14» в 50 м — сама по себе; порядок строк — по имени, столбик растёт вверх от точки
    fun parcel(name: String, cx: Int, cy: Int) =
      "<Parcel name=\"$name\"><Center>$cy $cx</Center><CoordGeom>" +
        "<Line><Start>0 0</Start><End>0 100</End></Line><Line><Start>0 100</Start><End>100 100</End></Line>" +
        "<Line><Start>100 100</Start><End>100 0</End></Line></CoordGeom></Parcel>"
    val text = "<LandXML version=\"1.2\"><Parcels>" + parcel("13_BA", 50, 52) + parcel("14", 100, 50) + parcel("13", 50, 47) +
      "</Parcels></LandXML>"
    val labels = read(text.encodeToByteArray()).labels.filter { it.layer == ParcelsSection.LAYER }
    assertEquals(listOf("13", "13_BA", "14"), labels.map { it.text })
    assertEquals(listOf(50.0 to 49.5, 50.0 to 49.5, 100.0 to 50.0), labels.map { it.x to it.y })
    val step = LabelScale.TITLE_DP * 1.2
    assertEquals(listOf(step, 0.0, 0.0), labels.map { it.liftDp })
    assertTrue(labels.all { it.objectId >= 0 })
  }

  @Test
  fun `тысячи участков - один пакет, объект на участок, лимит отрезков`() {
    val n = 3000
    val text = buildString {
      append("<LandXML version=\"1.2\"><Parcels>")
      for (k in 0 until n) {
        val e = k % 100 * 20
        val north = k / 100 * 20
        append("<Parcel name=\"$k\"><CoordGeom>")
        append("<Line><Start>$north $e</Start><End>$north ${e + 10}</End></Line>")
        append("<Line><Start>$north ${e + 10}</Start><End>${north + 10} ${e + 10}</End></Line>")
        append("<Line><Start>${north + 10} ${e + 10}</Start><End>${north + 10} $e</End></Line>")
        append("</CoordGeom></Parcel>")
      }
      append("</Parcels></LandXML>")
    }
    val geometry = read(text.encodeToByteArray())
    assertEquals(1, geometry.lines.size)
    assertEquals(4 * n, geometry.lines[0].segmentCount)
    assertEquals(n, geometry.objects.size)
    assertTrue(geometry.objects.all { it.attributes.isEmpty() })
    assertEquals(n, geometry.labels.size)
    assertTrue(summaryOf(geometry).contours.all { it.closed && it.area == 100.0 })
    buildRenderScene(geometry)

    // лимит отрезков накопителя: лишние отбрасываются, сцена помечена
    val section = ParcelsSection(log = {})
    val scene = LandXmlSceneBuilder(maxSegments = 10)
    val xml = XmlTokenizer(ByteArrayInput(text.encodeToByteArray()), section.names)
    while (xml.next() != XmlTokenizer.EOF) {
      if (xml.event == XmlTokenizer.START && xml.depth == 2) section.read(xml, scene)
    }
    val limited = scene.finish()
    assertTrue(limited.truncated)
    assertEquals(10, limited.lines.single().segmentCount)
  }

  @Test
  fun `сцена фикстуры строится`() {
    val render = buildRenderScene(read(fixture.readBytes()))
    assertTrue(render.lines.isNotEmpty())
  }
}
