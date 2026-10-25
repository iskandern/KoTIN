package dev.iskandern.kotin.landxml

import org.junit.Test
import dev.iskandern.kotin.AvailableControls
import dev.iskandern.kotin.io.ByteArrayInput
import dev.iskandern.kotin.model.PointMark
import dev.iskandern.kotin.model.SceneGeometry
import dev.iskandern.kotin.model.swappedXY
import dev.iskandern.kotin.readGeometry
import dev.iskandern.kotin.scene.GlyphAtlas
import dev.iskandern.kotin.scene.RenderLabel
import dev.iskandern.kotin.scene.RenderScene
import dev.iskandern.kotin.scene.buildRenderScene
import dev.iskandern.kotin.scene.buildTextMeshes
import dev.iskandern.kotin.scene.cellGlyph
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Подписи LandXML горизонтальны на экране при любой трассе и камере; подписи DXF — как прежде, вдоль строки. */
class LandXmlLabelOrientationTest {

  private fun readLandXml(bytes: ByteArray) = LandXmlReader(ByteArrayInput(bytes)).read()

  @Test
  fun `решение сцены о плоском чертеже у LandXML не срабатывает, у DXF срабатывает`() {
    // отметки точек разбросаны сильнее плана — для DXF это «плоский чертёж», для LandXML отметки настоящие
    val points = (0..3).map { PointMark("Точки", 7, it.toDouble(), 0.0, it * 100.0, 0.0, -1) }
    val dxf = SceneGeometry(emptyList(), emptyList(), emptyList(), emptyList(), points, emptyList(), false)
    assertTrue(buildRenderScene(dxf).drawingFlattened)
    assertFalse(buildRenderScene(dxf.swappedXY().let { SceneGeometry(it.meshes, it.lines, it.fills, it.labels, it.points, it.layers, false, flattenAuto = false) }).drawingFlattened)
  }

  @Test
  fun `с галочкой точки, подписи и линии LandXML на нижнем уровне поверхностей, поверхность остаётся`() {
    val g = readLandXml(FLATTEN_XML.toByteArray())
    assertTrue(g.points.isNotEmpty() && g.lines.isNotEmpty() && g.meshes.isNotEmpty())
    val off = buildRenderScene(g)
    val on = buildRenderScene(g, flattenDrawing = true)
    assertTrue(off.flattenChangesScene && on.flattenChangesScene)
    assertTrue(on.drawingFlattened && !off.drawingFlattened)
    // нижний уровень поверхностей: z = 100 при центре сцены cz
    val surfaceY = on.surfaces.flatMap { s -> s.positions.filterIndexed { i, _ -> i % 3 == 1 } }
    assertEquals(surfaceY, off.surfaces.flatMap { s -> s.positions.filterIndexed { i, _ -> i % 3 == 1 } })
    val flatY = surfaceY.min()
    assertTrue(surfaceY.max() > flatY)
    assertTrue(on.points.all { it.y == flatY })
    assertTrue(on.labels.all { it.y == flatY })
    assertTrue(on.lines.all { l -> l.positions.filterIndexed { i, _ -> i % 3 == 1 }.all { it == flatY } })
    // без галочки отметки точек настоящие
    assertTrue(off.points.any { it.y != flatY })
    assertTrue(off.lines.any { l -> l.positions.filterIndexed { i, _ -> i % 3 == 1 }.any { it != flatY } })
  }

  @Test
  fun `подпись DXF не горизонтальна на экране`() {
    val g = readGeometry(
      "0", "SECTION", "2", "ENTITIES",
      "0", "TEXT", "8", "0", "10", "1", "20", "2", "30", "0", "40", "1", "50", "30", "1", "abc",
      "0", "ENDSEC", "0", "EOF",
    )
    val label = g.labels.single()
    assertFalse(label.screenHorizontal)
    assertEquals(30.0, label.rotationDeg, 1e-9)
    assertFalse(buildRenderScene(g).labels.single().screenHorizontal)
  }

  @Test
  fun `в сетке у горизонтальной подписи нулевое направление - шейдер кладёт строку горизонтально`() {
    val glyph = cellGlyph(0, 0, 20, 24, 64, 64, advance = 20f)
    val atlas = GlyphAtlas(64, 64, ByteArray(64 * 64 * 4), mapOf('A' to glyph), 50, 40f)
    // направление на север (−Z): у DXF так и уходит в сетку, у горизонтальной подписи — обнуляется
    fun along(horizontal: Boolean): FloatArray {
      val scene = RenderScene(
        surfaces = emptyList(), lines = emptyList(), fills = emptyList(),
        labels = listOf(RenderLabel("T", 0, 0f, 0f, 0f, 1f, 0f, -1f, "A", screenHorizontal = horizontal)),
        points = emptyList(), layerInfos = emptyList(), truncated = false, drawingFlattened = false,
        originX = 0.0, originY = 0.0, originZ = 0.0, halfX = 1f, halfY = 1f, halfZ = 1f,
      )
      return buildTextMeshes(scene, atlas).labels.single().along
    }
    assertTrue(along(true).all { it == 0f })
    assertEquals(-1f, along(false)[1])
  }
}

private const val FLATTEN_XML = """<?xml version="1.0"?>
<LandXML xmlns="http://www.landxml.org/schema/LandXML-1.2" version="1.2">
 <Units><Metric linearUnit="meter"/></Units>
 <CgPoints>
  <CgPoint name="1">1005 2005 108</CgPoint>
  <CgPoint name="2">1002 2002 103</CgPoint>
 </CgPoints>
 <Surfaces><Surface name="S"><Definition surfType="TIN">
  <Pnts><P id="1">1000 2000 100</P><P id="2">1000 2010 105</P><P id="3">1010 2010 110</P><P id="4">1010 2000 106</P></Pnts>
  <Faces><F>1 2 3</F><F>1 3 4</F></Faces>
 </Definition></Surface></Surfaces>
 <Alignments><Alignment name="A" length="10" staStart="0">
  <CoordGeom><Line><Start>1001 2001</Start><End>1009 2009</End></Line></CoordGeom>
 </Alignment></Alignments>
</LandXML>"""
