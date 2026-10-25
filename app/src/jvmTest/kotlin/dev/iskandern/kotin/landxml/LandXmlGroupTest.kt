package dev.iskandern.kotin.landxml

import org.junit.Test
import dev.iskandern.kotin.io.ByteArrayInput
import dev.iskandern.kotin.dxf.DxfDocumentReader
import dev.iskandern.kotin.dxf.DxfFlattener
import dev.iskandern.kotin.model.SceneGeometry
import dev.iskandern.kotin.ru
import dev.iskandern.kotin.scene.buildRenderScene
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Список «Элементы» LandXML: группы в порядке [LandXmlGroup.ORDER], имена «<что>: <уточнение>»; у DXF групп нет. */
class LandXmlGroupTest {

  private val fixtures = File("src/jvmTest/resources/landxml")

  private fun read(file: File): SceneGeometry =
    LandXmlReader(ByteArrayInput(file.readBytes()), LandXmlReader.SECTIONS.map { it() }, log = {}).read()

  // слои по группам: «группа» → имена в порядке списка
  private fun SceneGeometry.grouped(): List<Pair<String, List<String>>> =
    layers.groupBy { it.group }.map { (group, infos) -> group to infos.map { ru(it.name) } }

  private fun assertOrdered(g: SceneGeometry) {
    val ranks = g.layers.map { LandXmlGroup.ORDER.indexOf(it.group) }
    assertTrue(ranks.all { it >= 0 }, "у каждого слоя LandXML — группа из списка: ${g.layers.map { it.name to it.group }}")
    assertEquals(ranks.sorted(), ranks, "слои идут по группам в порядке списка")
    // группа во Worker и в сцене доходит до списка
    assertEquals(g.layers.map { it.group }, buildRenderScene(g).layerInfos.map { it.group })
  }

  @Test
  fun `сети и точки`() {
    val g = read(File(fixtures, "pipenetwork.xml"))
    assertOrdered(g)
    assertEquals(
      listOf(LandXmlGroup.NETWORKS to listOf("Трубы", "Колодцы: канализация", "Трубы: канализация"), LandXmlGroup.POINTS to listOf("Точки")),
      g.grouped(),
    )
  }

  @Test
  fun `ситуация и участки — имена PlanFeature из файла, участки в той же группе`() {
    val g = read(File(fixtures, "planfeatures-parcels.xml"))
    assertOrdered(g)
    assertEquals(
      listOf(
        LandXmlGroup.SURFACES to listOf("Рельеф"),
        LandXmlGroup.PLANIMETRY to listOf("Бровка", "Здание", "Ограждение", "Опора освещения", "Ситуация", "Участки"),
        LandXmlGroup.POINTS to listOf("Точки"),
      ),
      g.grouped(),
    )
    // без имени — вид по описанию в общей форме
    assertTrue(read(File(fixtures, "planfeatures-kinds.xml")).layers.any { ru(it.name) == "Ситуация: Curb Edge of Pavement" })
  }

  @Test
  fun `исходные данные поверхностей — после поверхностей, имена по поверхности`() {
    val g = read(File(fixtures, "sourcedata.xml"))
    assertOrdered(g)
    assertEquals(
      listOf(
        LandXmlGroup.SURFACES to listOf("Площадка"),
        LandXmlGroup.SOURCE_DATA to listOf(
          "Площадка: границы", "Площадка: структурные линии", "Площадка: горизонтали", "Площадка: точки данных",
          "Только данные: структурные линии",
        ),
        LandXmlGroup.POINTS to listOf("Точки"),
      ),
      g.grouped(),
    )
  }

  @Test
  fun `поперечники, показатели дорог, пункты сети — в своих группах, имена в общей форме`() {
    val cross = read(File(fixtures, "crosssects.xml"))
    assertOrdered(cross)
    assertEquals(listOf("Поперечники: земля", "Поперечники: проект"), cross.grouped().toMap()[LandXmlGroup.CROSS_SECTIONS])

    val road = read(File(fixtures, "attributes/roadway-passport.xml"))
    assertOrdered(road)
    assertEquals(
      listOf(
        "Трассы", ru(StationLabels.LAYER), ru(StationLabels.MID_LAYER), "Трассы: расчётная скорость", "Трассы: скорость 85%", "Трассы: разрешённая скорость",
        "Трассы: интенсивность движения", "Трассы: дорожные знаки",
      ),
      road.grouped().toMap()[LandXmlGroup.ALIGNMENTS],
    )

    val speeds = read(File(fixtures, "attributes/alignment-speed-stations.xml"))
    assertOrdered(speeds)
    assertEquals(
      listOf("Трассы", ru(StationLabels.LAYER), ru(StationLabels.MID_LAYER), "Трассы: скорость по пикетам", "Трассы: вершины углов"),
      speeds.grouped().toMap()[LandXmlGroup.ALIGNMENTS],
    )

    val monuments = read(File(fixtures, "attributes/points-monuments-features.xml"))
    assertOrdered(monuments)
    assertEquals(listOf("Опорная сеть", "Точки: пункты сети"), monuments.grouped().toMap()[LandXmlGroup.POINTS])
  }

  @Test
  fun `DXF — слои без групп, список прежний`() {
    val file = File("src/jvmTest/resources/dxf/linetypes.dxf")
    val document = DxfDocumentReader(ByteArrayInput(file.readBytes())).read()
    val g = DxfFlattener(document).flatten()
    assertTrue(g.layers.size > 1)
    assertTrue(g.layers.all { it.group.isEmpty() })
    assertTrue(buildRenderScene(g).layerInfos.all { it.group.isEmpty() })
  }
}
