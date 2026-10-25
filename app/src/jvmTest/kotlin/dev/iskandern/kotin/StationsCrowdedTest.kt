package dev.iskandern.kotin

import dev.iskandern.kotin.io.ByteArrayInput
import dev.iskandern.kotin.landxml.AlignmentsSection
import dev.iskandern.kotin.landxml.LandXmlReader
import dev.iskandern.kotin.landxml.StationLabels
import dev.iskandern.kotin.scene.RenderScene
import dev.iskandern.kotin.scene.buildRenderScene
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Промежуточные пикеты: тесно в исходном виде — только концы; галочки «Пикетаж» и «Все пикеты». */
class StationsCrowdedTest {
  // [count] прямых трасс по 1 км на север, через [gap] м к востоку: подписи пикетов одной трассы — столбиком; [east] —
  // одна трасса на восток, подписи в ряд
  private fun scene(count: Int, gap: Double, east: Boolean = false): RenderScene {
    val alignments = (0 until count).joinToString("") {
      """<Alignment name="Т$it"><CoordGeom><Line><Start>0 ${it * gap}</Start><End>${if (east) "0 1000" else "1000 ${it * gap}"}</End></Line></CoordGeom></Alignment>"""
    }
    val xml = """<?xml version="1.0"?>
<LandXML xmlns="http://www.landxml.org/schema/LandXML-1.2" version="1.2"><Alignments>$alignments</Alignments></LandXML>"""
    val reader = LandXmlReader(ByteArrayInput(xml.encodeToByteArray()), listOf(AlignmentsSection(log = {}, stationing = true)), log = {})
    return buildRenderScene(reader.read())
  }

  private val desktop = Viewport(1280, 800, 1f)

  @Test
  fun `одна трасса - все пикеты`() {
    assertFalse(scene(1, 0.0).stationsCrowded(desktop))
  }

  @Test
  fun `трассы далеко друг от друга - все пикеты`() {
    assertFalse(scene(2, 600.0).stationsCrowded(desktop))
  }

  @Test
  fun `трассы рядом - только концы`() {
    assertTrue(scene(5, 12.0).stationsCrowded(desktop))
  }

  @Test
  fun `строка вдоль строки - тесно и на одной трассе`() {
    // полсотни подписей в ряд на 1280 px ложатся друг на друга
    assertTrue(scene(1, 0.0, east = true).stationsCrowded(desktop))
  }

  @Test
  fun `галочки пикетажа`() {
    val all = setOf(StationLabels.LAYER, StationLabels.MID_LAYER)
    val base = ViewSettings(hiddenLayers = setOf("слой"))
    assertEquals(setOf("слой"), base.withStationing(show = true, all = true).hiddenLayers)
    val ends = base.withStationing(show = true, all = false)
    assertEquals(setOf("слой", StationLabels.MID_LAYER), ends.hiddenLayers)
    assertFalse(ends.allStations)
    // пикетаж скрыт целиком, выбор «только концы» помнится и возвращается с ним
    val off = ends.withStationing(show = false, all = ends.allStations)
    assertEquals(setOf("слой") + all, off.hiddenLayers)
    assertEquals(ends.hiddenLayers, off.withStationing(show = true, all = off.allStations).hiddenLayers)
  }
}
