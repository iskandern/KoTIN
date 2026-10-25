package dev.iskandern.kotin.landxml

import dev.iskandern.kotin.io.ByteArrayInput
import dev.iskandern.kotin.scene.buildRenderScene
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Трасса толще линий без веса: 2,5 dp на экране (эталон), остальные линии — не тоньше 1,5 dp (SceneView). */
class AlignmentLineWeightTest {

  @Test
  fun `линии трассы с весом 0,8 мм`() {
    val bytes = File("src/jvmTest/resources/landxml/attributes/alignment-speed-stations.xml").readBytes()
    val geometry = LandXmlReader(ByteArrayInput(bytes), listOf(AlignmentsSection(log = {}, stationing = true)), log = {}).read()
    val alignment = geometry.lines.filter { it.color == LandXmlPalette.ALIGNMENT }
    assertTrue(alignment.isNotEmpty())
    assertTrue(alignment.all { it.lineWeight == AlignmentsSection.ALIGNMENT_WEIGHT })
    val scene = buildRenderScene(geometry)
    assertEquals(2.5f, scene.lines.first { it.color == LandXmlPalette.ALIGNMENT }.widthPx)
  }
}
