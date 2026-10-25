package dev.iskandern.kotin.landxml

import org.junit.Test
import dev.iskandern.kotin.io.ByteArrayInput
import dev.iskandern.kotin.model.LineBatch
import dev.iskandern.kotin.model.SceneGeometry
import dev.iskandern.kotin.readGeometry
import dev.iskandern.kotin.scene.DASH
import dev.iskandern.kotin.scene.GAP
import dev.iskandern.kotin.scene.OUTLINE_WIDTH_DP
import dev.iskandern.kotin.scene.boundaryEdges
import dev.iskandern.kotin.scene.buildRenderScene
import dev.iskandern.kotin.scene.dashedBatch
import dev.iskandern.kotin.scene.lineWidthPx
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Граница поверхности (T15): обводка TIN без своей границы, тёмная граница из файла, пунктир структурных линий. */
class LandXmlOutlineTest {

  private fun read(text: String): SceneGeometry {
    val section = SurfacesSection(log = {})
    val scene = LandXmlSceneBuilder()
    val xml = XmlTokenizer(ByteArrayInput(text.encodeToByteArray()), section.names)
    while (xml.next() != XmlTokenizer.EOF) {
      if (xml.event == XmlTokenizer.START && xml.depth == 2 && xml.localName() == "Surfaces") section.read(xml, scene)
    }
    return scene.finish()
  }

  private fun surfaces(body: String) =
    """<LandXML xmlns="http://www.landxml.org/schema/LandXML-1.2" version="1.2"><Surfaces>$body</Surfaces></LandXML>"""

  // квадрат 100 × 100 из двух треугольников (в LandXML — «север восток отметка»)
  private val square = """<Definition surfType="TIN"><Pnts>
    <P id="1">0 0 10</P><P id="2">0 100 10</P><P id="3">100 100 10</P><P id="4">100 0 10</P>
    </Pnts><Faces><F>1 2 3</F><F>1 3 4</F></Faces></Definition>"""

  private val boundary = """<SourceData><Boundaries><Boundary bndType="outer">
    <PntList3D>0 0 10 0 100 10 100 100 10 100 0 10</PntList3D></Boundary></Boundaries></SourceData>"""

  @Test
  fun `внешние рёбра - встреченные один раз`() {
    // два треугольника с общей диагональю 0-2: на границе четыре стороны квадрата
    val edges = boundaryEdges(intArrayOf(0, 1, 2, 0, 2, 3))
    val pairs = (0 until edges.size / 2).map { setOf(edges[it * 2], edges[it * 2 + 1]) }.toSet()
    assertEquals(setOf(setOf(0, 1), setOf(1, 2), setOf(2, 3), setOf(0, 3)), pairs)
  }

  @Test
  fun `TIN без границы - обводка по внешним рёбрам`() {
    val geometry = read(surfaces("""<Surface name="EG">$square</Surface>"""))
    assertTrue(geometry.meshes.single().outline)
    val scene = buildRenderScene(geometry)
    // обводка — часть поверхности, а не линия чертежа
    assertEquals(4, scene.surfaces.single().outline!!.size / 2)
    assertTrue(scene.lines.isEmpty())
  }

  @Test
  fun `TIN с границей в файле - обводки нет, граница тёмная и толстая`() {
    // SourceData и до, и после Definition
    for (body in listOf(boundary + square, square + boundary)) {
      val geometry = read(surfaces("""<Surface name="EG">$body</Surface>"""))
      assertFalse(geometry.meshes.single().outline, body)
      assertEquals(null, buildRenderScene(geometry).surfaces.single().outline)
      val border = geometry.lines.single()
      assertEquals(LandXmlPalette.BOUNDARY, border.color)
      assertEquals(OUTLINE_WIDTH_DP, lineWidthPx(border.lineWeight))
    }
  }

  @Test
  fun `полость - не внешняя граница, обводка остаётся`() {
    val void = boundary.replace("outer", "void")
    assertTrue(read(surfaces("""<Surface name="EG">$void$square</Surface>""")).meshes.single().outline)
  }

  @Test
  fun `структурная линия - сплошная в геометрии, пунктир при показе`() {
    // ломаная 0 → 50 → 100 по востоку на отметке 10; сцена 100 м
    val text = surfaces("""<Surface name="EG"><SourceData><Breaklines><Breakline>
      <PntList3D>0 0 10 0 50 10 0 100 10</PntList3D></Breakline></Breaklines></SourceData>$square</Surface>""")
    val geometry = read(text)
    val batch = geometry.lines.single { it.color == LandXmlPalette.BREAKLINE }
    // длина и звенья объекта — как в файле
    assertTrue(batch.dashed)
    assertEquals(2, batch.segmentCount)
    val lines = buildRenderScene(geometry).lines.single { it.color == LandXmlPalette.BREAKLINE }
    val on = 100 * DASH
    val period = on + 100 * GAP
    var drawn = 0.0
    for (k in 0 until lines.lineCount) {
      val a = lines.indices[k * 2] * 3
      val b = lines.indices[k * 2 + 1] * 3
      val len = (lines.positions[b] - lines.positions[a]).toDouble()
      assertTrue(len <= on + 1e-4, "штрих $len")
      drawn += len
    }
    // фаза не сбрасывается в изломе: доля штрихов — как у сплошной линии длиной 100
    val whole = (100 / period).toInt()
    assertEquals(whole * on + minOf(on, 100 - whole * period), drawn, 1e-3)
    // у штрихов — объект линии
    assertTrue(lines.objectIds!!.all { it == batch.objectIds!![0] })
  }

  @Test
  fun `пунктир - фаза сбрасывается на разрыве ломаной`() {
    // два отрезка по 1 м не подряд: штрих 2 м — у каждого свой штрих с начала
    val batch = LineBatch("L", 0, false, doubleArrayOf(0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 5.0, 0.0, 0.0, 6.0, 0.0, 0.0), dashed = true)
    val dashed = dashedBatch(batch, 2.0, 1.0)
    assertEquals(batch.coords.toList(), dashed.coords.toList())
  }

  @Test
  fun `DXF - сетка 3DFACE обводится`() {
    val face = arrayOf("0", "3DFACE", "8", "TIN", "10", "0", "20", "0", "30", "0", "11", "1", "21", "0", "31", "0",
      "12", "1", "22", "1", "32", "0", "13", "1", "23", "1", "33", "0")
    val geometry = readGeometry("0", "SECTION", "2", "ENTITIES", *face, "0", "ENDSEC", "0", "EOF")
    assertTrue(geometry.meshes.single().outline)
    val scene = buildRenderScene(geometry)
    assertEquals(3, scene.surfaces.single().outline!!.size / 2)
    assertTrue(scene.lines.isEmpty())
  }
}
