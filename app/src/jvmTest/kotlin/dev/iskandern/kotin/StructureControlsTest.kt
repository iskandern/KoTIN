package dev.iskandern.kotin

import dev.iskandern.kotin.io.ByteArrayInput
import dev.iskandern.kotin.landxml.LandXmlReader
import dev.iskandern.kotin.scene.RenderScene
import dev.iskandern.kotin.scene.buildRenderScene
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Колодцы и трубы LandXML — своя галочка «Трубы»; «Поверхность» и «Каркас» относятся только к рельефу. */
class StructureControlsTest {

  private val terrain = """
    <Surfaces><Surface name="Рельеф"><Definition surfType="TIN">
      <Pnts><P id="1">0 0 0</P><P id="2">0 50 5</P><P id="3">50 50 0</P><P id="4">50 0 5</P></Pnts>
      <Faces><F>1 2 3</F><F>1 3 4</F></Faces>
    </Definition></Surface></Surfaces>
  """

  private val pipes = """
    <PipeNetworks><PipeNetwork name="С" pipeNetType="storm">
      <Structs>
        <Struct name="1" elevRim="3" elevSump="0"><Center>10 10</Center><CircStruct diameter="1.5"/></Struct>
        <Struct name="2" elevRim="3" elevSump="0"><Center>30 10</Center></Struct>
      </Structs>
      <Pipes><Pipe name="т" refStart="1" refEnd="2"><CircPipe diameter="0.4"/></Pipe></Pipes>
    </PipeNetwork></PipeNetworks>
  """

  private fun scene(vararg sections: String): RenderScene = buildRenderScene(
    LandXmlReader(ByteArrayInput("<LandXML version=\"1.2\">${sections.joinToString("")}</LandXML>".encodeToByteArray())).read(),
  )

  @Test
  fun `трубы без рельефа - только галочка Трубы`() {
    val c = AvailableControls(scene(pipes))
    assertTrue(c.structures)
    assertFalse(c.surfaces, "без рельефа нет ни «Поверхности», ни «Каркаса»")
    assertFalse(c.occlusion)
  }

  @Test
  fun `рельеф без труб - галочки Трубы нет`() {
    val c = AvailableControls(scene(terrain))
    assertTrue(c.surfaces)
    assertFalse(c.structures)
  }

  @Test
  fun `рельеф и трубы - обе галочки, сетки труб помечены`() {
    val scene = scene(terrain, pipes)
    val c = AvailableControls(scene)
    assertTrue(c.surfaces && c.structures)
    assertEquals(setOf("Рельеф"), scene.surfaces.filter { !it.structure }.map { ru(it.layer) }.toSet())
    assertTrue(scene.surfaces.filter { it.structure }.all { ru(it.layer).startsWith("Колодцы") || ru(it.layer).startsWith("Трубы") })
  }

  @Test
  fun `выбор трубы зависит от Труб, а не от Поверхности`() {
    val scene = scene(terrain, pipes)
    val pipe = scene.objects.indexOfFirst { it.kind == "Pipe" }
    val geometry = collectObject(scene, pipe)
    val layer = scene.objects[pipe].layer
    // осевая линия, подпись и точки трубы выключены: остаётся только её сетка
    val meshOnly = ViewSettings(showLines = false, showLabels = false, showPoints = false)
    assertTrue(geometry.isVisibleIn(meshOnly.copy(showSurface = false, showWire = false), layer))
    assertFalse(geometry.isVisibleIn(meshOnly.copy(showStructures = false), layer))
  }

  @Test
  fun `DXF - галочки Трубы нет`() {
    val face = arrayOf(
      "0", "3DFACE", "8", "TIN",
      "10", "0", "20", "0", "30", "0", "11", "10", "21", "0", "31", "0", "12", "10", "22", "10", "32", "0", "13", "0", "23", "10", "33", "0",
    )
    val dxf = buildRenderScene(readGeometry("0", "SECTION", "2", "ENTITIES", *face, "0", "ENDSEC", "0", "EOF"))
    assertFalse(AvailableControls(dxf).structures)
    assertTrue(AvailableControls(dxf).surfaces)
  }
}
