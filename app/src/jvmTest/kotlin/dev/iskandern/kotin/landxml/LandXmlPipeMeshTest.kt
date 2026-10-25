package dev.iskandern.kotin.landxml

import org.junit.Test
import dev.iskandern.kotin.io.ByteArrayInput
import dev.iskandern.kotin.model.SceneGeometry
import dev.iskandern.kotin.model.SceneMesh
import dev.iskandern.kotin.ru
import dev.iskandern.kotin.scene.buildRenderScene
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sqrt
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LandXmlPipeMeshTest {

  private val fixture = File("src/jvmTest/resources/landxml/pipenetwork.xml")

  private fun read(bytes: ByteArray): SceneGeometry =
    LandXmlReader(ByteArrayInput(bytes), LandXmlReader.SECTIONS.map { it() }, log = {}).read()

  private fun SceneGeometry.pipes() = objects.withIndex().filter { it.value.kind == "Pipe" }.map { it.index }

  // треугольники и вершины объекта [id] в сетке: вершины — по номеру, кольца идут подряд
  private fun SceneMesh.triangles(id: Int) = (0 until triangleCount).filter { objectOf(it) == id }

  private fun SceneMesh.vertices(id: Int): List<DoubleArray> =
    triangles(id).flatMap { t -> (0 until 3).map { triangles[t * 3 + it] } }.distinct().sorted()
      .map { v -> doubleArrayOf(coords[v * 3], coords[v * 3 + 1], coords[v * 3 + 2]) }

  // расстояние от точки [p] до прямой через [a] и [b]
  private fun distanceToLine(p: DoubleArray, a: DoubleArray, b: DoubleArray): Double {
    val d = DoubleArray(3) { b[it] - a[it] }
    val v = DoubleArray(3) { p[it] - a[it] }
    val cx = v[1] * d[2] - v[2] * d[1]
    val cy = v[2] * d[0] - v[0] * d[2]
    val cz = v[0] * d[1] - v[1] * d[0]
    return sqrt(cx * cx + cy * cy + cz * cz) / sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2])
  }

  private fun network(type: String, pipe: String, sump1: Double = 100.0, sump2: Double = 99.0) = """
    <LandXML version="1.2">
      <PipeNetworks>
        <PipeNetwork name="С" pipeNetType="$type">
          <Structs>
            <Struct name="1" elevRim="103" elevSump="$sump1"><Center>0 0</Center></Struct>
            <Struct name="2" elevRim="103" elevSump="$sump2"><Center>20 0</Center></Struct>
          </Structs>
          <Pipes><Pipe name="т" refStart="1" refEnd="2">$pipe</Pipe></Pipes>
        </PipeNetwork>
      </PipeNetworks>
    </LandXML>
  """.trimIndent().encodeToByteArray()

  @Test
  fun `круглая труба - радиус по диаметру, треугольников 2 x N x сегментов`() {
    val geometry = read(fixture.readBytes())
    // обе трубы фикстуры (канализация) — одна сетка своего слоя цветом сети
    val mesh = geometry.meshes.single { ru(it.layer).startsWith("Трубы") }
    assertEquals(PipeNetworksSection.tubeLayer("sanitary"), mesh.layer)
    assertEquals(LandXmlPalette.pipe("sanitary"), geometry.layers.single { it.name == mesh.layer }.trueColor)
    val (t1, t2) = geometry.pipes()
    assertEquals(setOf(t1, t2), (0 until mesh.triangleCount).map { mesh.objectOf(it) }.toSet())

    // Т1: CircPipe 300 мм — радиус 0,15; ось выше лотков 100,5 → 99 на радиус; прямая — два кольца
    val vertices = mesh.vertices(t1)
    val n = vertices.size / 2
    assertTrue(n in PipeNetworksSection.TUBE_MIN..PipeNetworksSection.TUBE_MAX, "$n")
    assertEquals(2 * n * 1, mesh.triangles(t1).size)
    val a = doubleArrayOf(2000.0, 1000.0, 100.65)
    val b = doubleArrayOf(2030.0, 1000.0, 99.15)
    for (v in vertices) assertEquals(0.15, distanceToLine(v, a, b), 1e-9)
    // низ трубы — на лотке: нижняя точка первого кольца — на отметке лотка с точностью до грани и наклона кольца уклоном
    val bottom = vertices.take(n).minOf { it[2] }
    assertTrue(bottom >= 100.5 - 1e-9 && bottom < 100.5 + 0.15 * (1 - cos(PI / n)) + 1e-3, "$bottom")
  }

  @Test
  fun `кривая труба - кольца перпендикулярны оси дуги`() {
    val geometry = read(fixture.readBytes())
    val mesh = geometry.meshes.single { ru(it.layer).startsWith("Трубы") }
    val t2 = geometry.pipes()[1]
    // Т2: яйцевидная 0,6 × 0,4 — эллипс; четверть окружности R 20 с центром (2050, 1000), лотки 99,8 → 99
    val vertices = mesh.vertices(t2)
    val triangles = mesh.triangles(t2).size
    // треугольников 2 × N × сегментов, колец на одно больше, чем сегментов
    var rings = 0
    var n = 0
    for (r in 3..1000) if (vertices.size % r == 0 && triangles == 2 * (vertices.size / r) * (r - 1)) {
      rings = r
      n = vertices.size / r
    }
    assertTrue(rings > 3, "колец $rings")
    assertTrue(n in PipeNetworksSection.TUBE_MIN..PipeNetworksSection.TUBE_MAX, "$n")
    val slope = (99.0 - 99.8) / (10 * PI)
    for (r in 0 until rings) {
      val ring = vertices.subList(r * n, (r + 1) * n)
      // центр эллипса — среднее точек кольца: на дуге, выше лотка на половину высоты
      val c = DoubleArray(3) { k -> ring.sumOf { it[k] } / n }
      assertEquals(20.0, hypot(c[0] - 2050.0, c[1] - 1000.0), 1e-6)
      // дуга идёт по часовой стрелке от угла π (начало 2030, 1000) к π/2 (конец 2050, 1020)
      val dx = c[0] - 2050.0
      val dy = c[1] - 1000.0
      val angle = atan2(dy, dx)
      val travelled = PI - if (angle < 0) angle + 2 * PI else angle
      assertEquals(99.8 + slope * 20 * travelled + 0.3, c[2], 1e-6)
      // касательная к винтовой оси (на единицу длины в плане): в плане — поперёк радиуса по ходу, по высоте — уклон
      val tx = dy / 20
      val ty = -dx / 20
      for (v in ring) {
        val dot = (v[0] - c[0]) * tx + (v[1] - c[1]) * ty + (v[2] - c[2]) * slope
        // на концах касательная — отражением, у винтовой оси почти точно (кольцо по хорде дало бы ~0,007)
        assertTrue(abs(dot) < 1e-4, "кольцо $r: $dot")
      }
      // полуоси: вбок — пролёт 0,4 / 2, вверх — высота 0,6 / 2
      assertEquals(0.2, ring.maxOf { hypot(it[0] - c[0], it[1] - c[1]) }, 1e-3)
      assertEquals(0.3, ring.maxOf { it[2] - c[2] }, 1e-3)
    }
  }

  @Test
  fun `прямоугольная труба - четыре плоские стенки`() {
    // ось вдоль севера (x DXF — восток = 0): ширина — по x, высота — по z
    val geometry = read(network("storm", """<RectPipe height="0.4" width="800"/>""", sump1 = 100.0, sump2 = 100.0))
    val mesh = geometry.meshes.single()
    assertEquals(PipeNetworksSection.tubeLayer("storm"), mesh.layer)
    assertEquals(2 * 4 * 1, mesh.triangleCount)
    // у каждой стенки свои вершины: 4 стенки × 2 точки × 2 кольца
    assertEquals(16, mesh.vertexCount)
    assertEquals(listOf(-0.4, 0.4), (0 until mesh.vertexCount).map { mesh.coords[it * 3] }.distinct().sorted().map { round(it) })
    assertEquals(listOf(100.0, 100.4), (0 until mesh.vertexCount).map { mesh.coords[it * 3 + 2] }.distinct().sorted().map { round(it) })
    assertEquals(listOf(0.0, 20.0), (0 until mesh.vertexCount).map { mesh.coords[it * 3 + 1] }.distinct().sorted().map { round(it) })
  }

  @Test
  fun `лоток - трапеция без крышки`() {
    val geometry = read(network("water", """<Channel height="0.5" widthTop="1.2" widthBottom="0.6"/>""", sump1 = 100.0, sump2 = 100.0))
    val mesh = geometry.meshes.single()
    assertEquals(PipeNetworksSection.tubeLayer("water"), mesh.layer)
    // три грани: две стенки и дно
    assertEquals(2 * 3 * 1, mesh.triangleCount)
    val top = (0 until mesh.vertexCount).filter { round(mesh.coords[it * 3 + 2]) == 100.5 }.map { round(mesh.coords[it * 3]) }
    val bottom = (0 until mesh.vertexCount).filter { round(mesh.coords[it * 3 + 2]) == 100.0 }.map { round(mesh.coords[it * 3]) }
    assertEquals(listOf(-0.6, 0.6), top.distinct().sorted())
    assertEquals(listOf(-0.3, 0.3), bottom.distinct().sorted())
  }

  @Test
  fun `без размера или без отметок - только осевая линия`() {
    assertTrue(read(network("storm", """<CircPipe diameter="0"/>""")).meshes.isEmpty())
    assertTrue(read(network("storm", "")).meshes.isEmpty())
    val noElev = network("storm", """<CircPipe diameter="0.3"/>""").decodeToString()
      .replace(Regex(""" elevRim="[^"]*" elevSump="[^"]*""""), "").encodeToByteArray()
    val geometry = read(noElev)
    assertTrue(geometry.meshes.isEmpty())
    assertTrue(geometry.lines.any { ru(it.layer) == "Трубы" && it.flat })
  }

  @Test
  fun `все сечения - у каждой трубы объём, у прямоугольной 4 грани, у лотка 3`() {
    val geometry = read(File("src/jvmTest/resources/landxml/pipe-shapes.xml").readBytes())
    val mesh = geometry.meshes.single { it.layer == PipeNetworksSection.tubeLayer("storm") }
    val (t1, t2, t3, t4, t5) = geometry.pipes()
    assertEquals(2 * 4, mesh.triangles(t2).size)
    assertEquals(2 * 3, mesh.triangles(t4).size)
    for (id in listOf(t1, t3, t5)) assertTrue(mesh.triangles(id).isNotEmpty())
    // кривая — больше одного сегмента
    assertTrue(mesh.triangles(t5).size > mesh.triangles(t1).size)
    assertSummaryMatches(LandXmlReference.summarize(File("src/jvmTest/resources/landxml/pipe-shapes.xml").readBytes()),
      summaryOf(geometry), setOf(PIPE_NETWORKS), "pipe-shapes.xml")
  }

  @Test
  fun `граней по размеру сечения - от 8 до 24`() {
    assertEquals(PipeNetworksSection.TUBE_MIN, PipeNetworksSection.tubeFaces(0.3, 10_000.0))
    assertEquals(PipeNetworksSection.TUBE_MAX, PipeNetworksSection.tubeFaces(3.0, 50.0))
    val middle = PipeNetworksSection.tubeFaces(1.0, 500.0)
    assertTrue(middle in PipeNetworksSection.TUBE_MIN..PipeNetworksSection.TUBE_MAX)
  }

  @Test
  fun `сеть с трубами объёмом - рендер без ошибок`() {
    val scene = buildRenderScene(read(fixture.readBytes()))
    assertTrue(scene.surfaces.any { it.layer == PipeNetworksSection.tubeLayer("sanitary") })
  }

  private fun round(value: Double) = Math.round(value * 1e9) / 1e9
}
