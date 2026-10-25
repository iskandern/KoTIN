package dev.iskandern.kotin.landxml

import org.junit.Test
import dev.iskandern.kotin.io.ByteArrayInput
import dev.iskandern.kotin.model.LineBatch
import dev.iskandern.kotin.model.SceneGeometry
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import dev.iskandern.kotin.ru

/** `Survey`: линии «станция → цель» по измеренным координатам; `PlanFeatures`: слои по `desc` и отметки `Chain`. */
class LandXmlSurveyTest {

  private val logs = ArrayList<String>()

  private fun read(name: String): SceneGeometry {
    val bytes = File("src/jvmTest/resources/landxml/$name").readBytes()
    val sections = listOf(CgPointsSection(log = {}), SurveySection(log = { logs.add(ru(it)) }), PlanFeaturesSection(log = {}))
    return LandXmlReader(ByteArrayInput(bytes), sections, log = {}).read()
  }

  // отрезки пакета: x1 y1 z1 x2 y2 z2
  private fun segments(batch: LineBatch) = (0 until batch.segmentCount).map { i -> DoubleArray(6) { batch.coords[i * 6 + it] } }

  private fun layerLines(geometry: SceneGeometry, layer: String) = geometry.lines.filter { it.layer == layer || ru(it.layer) == layer }

  @Test
  fun `съёмка - линии станция-цель, повтор круга, заглушка и ссылка на CgPoints`() {
    val geometry = read("survey.xml")
    val lines = layerLines(geometry, SurveySection.LAYER)
    assertEquals(LandXmlPalette.SURVEY, lines.first().color)
    val elevated = lines.filter { !it.flat }.flatMap { segments(it) }.map { it.toList() }
    // 1 → BS (координаты у самой цели), 1 → 2 (второй круг не рисуется), 1 → A (по CgPoints файла), 2 → 1 (по Survey/CgPoints)
    // цель T — заглушка «-1e20» без другого источника — пропущена
    assertEquals(
      setOf(
        listOf(200.0, 100.0, 10.0, 150.0, 100.0, 10.0),
        listOf(200.0, 100.0, 10.0, 300.0, 100.0, 20.0),
        listOf(200.0, 100.0, 10.0, 2000.0, 1000.0, 50.0),
        listOf(300.0, 100.0, 20.0, 200.0, 100.0, 10.0),
      ),
      elevated.toSet(),
    )
    assertEquals(4, elevated.size)
    // цель без отметки (X) — плоская линия из станции 2
    val flat = lines.filter { it.flat }.flatMap { segments(it) }
    assertEquals(listOf(listOf(300.0, 100.0, 0.0, 600.0, 500.0, 0.0)), flat.map { it.toList() })
    assertTrue(logs.any { "замеров" in it && "пропущены" in it }, logs.toString())
    // три станции в файле, линии только у двух
    assertTrue(logs.any { "станций 2 из 3" in it }, logs.toString())
  }

  @Test
  fun `съёмка - объект на станцию, значок и подпись только у станций с линиями`() {
    val geometry = read("survey.xml")
    val stations = geometry.objects.filter { it.kind == SurveySection.KIND }
    assertEquals(listOf("1", "2"), stations.map { it.name })
    assertEquals(listOf("10,00", "1", "20,00", "2"), geometry.labels.filter { it.layer == SurveySection.LAYER }.map { it.text })
    assertEquals(2, geometry.points.count { it.layer == SurveySection.LAYER })
    assertTrue(stations[0].attributes.any { it.name == "instrumentHeight" && it.value == "1.5" })
    // отрезки привязаны к объектам станций
    val ids = layerLines(geometry, SurveySection.LAYER).flatMap { b -> b.objectIds!!.toList() }.toSet()
    assertEquals(2, ids.size)
  }

  @Test
  fun `съёмка - без Survey ничего не добавляется`() {
    val geometry = read("cgpoints.xml")
    assertTrue(layerLines(geometry, SurveySection.LAYER).isEmpty())
  }

  @Test
  fun `ситуация - слой по desc без номеров, свой цвет у вида`() {
    val geometry = read("planfeatures-kinds.xml")
    val curb = layerLines(geometry, "Ситуация: Curb Edge of Pavement")
    // два бордюра с разными номерами — один слой и один цвет
    assertEquals(1, curb.size)
    assertEquals(2, curb[0].segmentCount)
    val sidewalk = layerLines(geometry, "Ситуация: Sidewalk").single()
    assertNotEquals(curb[0].color, sidewalk.color)
    assertEquals(LandXmlPalette.planFeature(0), curb[0].color)
    assertEquals(LandXmlPalette.planFeature(1), sidewalk.color)
    // имя важнее desc: слой «Fence» прежнего цвета; без имени и desc — «Ситуация»
    assertEquals(LandXmlPalette.PLAN_FEATURE, layerLines(geometry, "Fence").single().color)
    assertEquals(1, layerLines(geometry, PlanFeaturesSection.DEFAULT_LAYER).single().segmentCount)
  }

  @Test
  fun `ситуация - Chain берёт отметки CgPoint, без отметок остаётся планом`() {
    val geometry = read("planfeatures-kinds.xml")
    val run = layerLines(geometry, "Run").single()
    assertTrue(!run.flat)
    assertEquals(listOf(listOf(10.0, 10.0, 5.0, 50.0, 10.0, 7.0), listOf(50.0, 10.0, 7.0, 90.0, 40.0, 9.0)), segments(run).map { it.toList() })
    assertTrue(layerLines(geometry, "Plan").single().flat)
  }
}
