package dev.iskandern.kotin.landxml

import org.junit.Test
import dev.iskandern.kotin.io.ByteArrayInput
import dev.iskandern.kotin.model.LineBatch
import dev.iskandern.kotin.model.SceneGeometry
import dev.iskandern.kotin.ru
import dev.iskandern.kotin.scene.buildRenderScene
import java.io.File
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LandXmlPipeNetworkTest {

  private val fixture = File("src/jvmTest/resources/landxml/pipenetwork.xml")

  private val logs = ArrayList<String>()

  private fun read(bytes: ByteArray): SceneGeometry =
    LandXmlReader(ByteArrayInput(bytes), LandXmlReader.SECTIONS.map { it() }, log = {}).read()

  // разделы с логом в [logs]: точки — для pntRef
  private fun readLogged(bytes: ByteArray): SceneGeometry =
    LandXmlReader(ByteArrayInput(bytes), listOf(CgPointsSection(log = {}), PipeNetworksSection(log = { logs.add(ru(it)) })), log = {}).read()

  private fun SceneGeometry.objectsOf(kind: String) = objects.withIndex().filter { it.value.kind == kind }

  private fun SceneGeometry.attr(id: Int, name: String) = objects[id].attributes.firstOrNull { ru(it.name) == name }?.value?.let(::ru)

  // вершины осевой линии объекта [id] по порядку: x, y, z
  private fun SceneGeometry.polyline(id: Int): List<DoubleArray> {
    val result = ArrayList<DoubleArray>()
    for (batch in lines) {
      val ids = batch.objectIds ?: continue
      for (i in 0 until batch.coords.size / 6) {
        if (ids[i] != id) continue
        val c = batch.coords
        if (result.isEmpty()) result.add(doubleArrayOf(c[i * 6], c[i * 6 + 1], c[i * 6 + 2]))
        result.add(doubleArrayOf(c[i * 6 + 3], c[i * 6 + 4], c[i * 6 + 5]))
      }
    }
    return result
  }

  private fun SceneGeometry.batchOf(id: Int): LineBatch = lines.single { b -> b.objectIds?.any { it == id } == true }

  @Test
  fun `фикстура - число объектов, слои, неразрешённая труба пропущена`() {
    val geometry = readLogged(fixture.readBytes())
    assertEquals(listOf("К1", "К2", "К3"), geometry.objectsOf("Struct").map { it.value.name })
    assertEquals(listOf("Т1", "Т2"), geometry.objectsOf("Pipe").map { it.value.name })
    assertTrue(listOf("Колодцы: канализация", "Трубы").all { name -> geometry.layers.any { ru(it.name) == name } })
    assertTrue(logs.any { "«Т3»" in it && "«К9»" in it }, logs.toString())
    assertTrue(logs.any { "колодцев 3 из 3, труб 2 из 3" in it }, logs.toString())
    // один меш на слой колодцев: цилиндр К1 и прямоугольная призма К2 (за ним — меши труб, KoTIN-71)
    val mesh = geometry.meshes.single { ru(it.layer) == "Колодцы: канализация" }
    assertEquals("Колодцы: канализация", ru(mesh.layer))
    val ids = geometry.objectsOf("Struct").map { it.index }
    assertEquals(setOf(ids[0], ids[1]), (0 until mesh.triangleCount).map { mesh.objectOf(it) }.toSet())
    // цилиндр: 16 граней сбоку и 16 в крышке; призма: 4 стены и крышка по 2 треугольника
    assertEquals(3 * 16 + 5 * 2, mesh.triangleCount)
    // К3 без отметок — точка на z = 0 (сцена поднимет на поверхность), подписи у всех колодцев
    assertEquals(listOf(Triple(2050.0, 1020.0, 0.0)), geometry.points.filter { ru(it.layer) == "Колодцы: канализация" }.map { Triple(it.x, it.y, it.z) })
    assertEquals(listOf("К1", "К2", "К3"), geometry.labels.filter { ru(it.layer) == "Колодцы: канализация" }.map { it.text })
  }

  @Test
  fun `колодцы разных сетей - своя сетка, слой и цвет у каждого типа`() {
    val geometry = read(File("src/jvmTest/resources/landxml/synthetic/palette-scene.xml").readBytes())
    val types = listOf("storm", "sanitary", "water", "other")
    for (type in types) {
      val layer = PipeNetworksSection.structLayer(type)
      val mesh = geometry.meshes.single { it.layer == layer }
      assertTrue(mesh.triangleCount > 0)
      assertEquals(LandXmlPalette.struct(type), geometry.layers.single { it.name == layer }.trueColor)
      // колодцы сетки — объекты только своей сети и своего цвета
      val ids = (0 until mesh.triangleCount).map { mesh.objectOf(it) }.toSet()
      assertTrue(ids.all { geometry.objects[it].color == LandXmlPalette.struct(type) })
    }
    // в меню колодцы и трубы сети рядом
    val names = geometry.layers.map { it.name }
    for (type in types) {
      val at = names.indexOf(PipeNetworksSection.structLayer(type))
      assertEquals(PipeNetworksSection.tubeLayer(type), names.getOrNull(at + 1), type)
    }
  }

  @Test
  fun `колодцы - размеры, центр по ссылке, отметки`() {
    val geometry = read(fixture.readBytes())
    val mesh = geometry.meshes.single { ru(it.layer) == "Колодцы: канализация" }
    val (k1, k2) = geometry.objectsOf("Struct").map { it.index }
    fun vertices(id: Int) = (0 until mesh.triangleCount).filter { mesh.objectOf(it) == id }
      .flatMap { t -> (0 until 3).map { mesh.triangles[t * 3 + it] } }.distinct()
      .map { v -> doubleArrayOf(mesh.coords[v * 3], mesh.coords[v * 3 + 1], mesh.coords[v * 3 + 2]) }
    // диаметр 1500 — миллиметры: радиус 0,75 м; от дна 100 до верха 105
    val cylinder = vertices(k1)
    assertEquals(0.75, cylinder.maxOf { hypot(it[0] - 2000.0, it[1] - 1000.0) }, 1e-9)
    assertEquals(100.0, cylinder.minOf { it[2] }, 1e-12)
    assertEquals(105.0, cylinder.maxOf { it[2] }, 1e-12)
    assertEquals("1500", geometry.attr(k1, "Диаметр"))
    // центр К2 — точка CgPoint после сети; длина 2 по направлению 90° (на восток), ширина 1,5 по северу
    val box = vertices(k2)
    assertEquals(listOf(2029.0, 2031.0, 999.25, 1000.75, 99.0, 104.0),
      listOf(box.minOf { it[0] }, box.maxOf { it[0] }, box.minOf { it[1] }, box.maxOf { it[1] }, box.minOf { it[2] }, box.maxOf { it[2] }).map { Math.round(it * 1e9) / 1e9 })
    assertEquals("Канализация", geometry.attr(k2, "Сеть"))
    assertEquals("sanitary", geometry.attr(k2, "Тип сети"))
    assertEquals("99.8 (out)", geometry.attr(k2, "Лоток Т2"))
    assertEquals(LandXmlPalette.struct("sanitary"), geometry.objects[k2].color)
  }

  @Test
  fun `трубы - отметки лотков и дуга через Center`() {
    val geometry = read(fixture.readBytes())
    val (t1, t2) = geometry.objectsOf("Pipe").map { it.index }
    // Т1: начало — Invert К1 для Т1, конец — дно К2 (своего Invert у К2 нет)
    val straight = geometry.polyline(t1)
    assertEquals(2, straight.size)
    assertEquals(listOf(2000.0, 1000.0, 100.5), straight.first().toList())
    assertEquals(listOf(2030.0, 1000.0, 99.0), straight.last().toList().map { Math.round(it * 1e9) / 1e9 })
    assertEquals("100.5", geometry.attr(t1, "Лоток в начале"))
    assertEquals("99", geometry.attr(t1, "Лоток в конце"))
    assertEquals("300", geometry.attr(t1, "Диаметр"))
    assertEquals("30", geometry.attr(t1, "Длина в файле"))
    assertEquals("50 ‰", geometry.attr(t1, "Уклон"))
    val batch = geometry.batchOf(t1)
    assertEquals("Трубы", ru(batch.layer))
    assertEquals(LandXmlPalette.pipe("sanitary"), batch.color)
    assertTrue(!batch.flat)

    // Т2: четверть окружности R 20 с центром (2050, 1000) через точку Center; отметка линейно 99,8 → 99
    val arc = geometry.polyline(t2)
    assertTrue(arc.size > 3)
    for (p in arc) assertEquals(20.0, hypot(p[0] - 2050.0, p[1] - 1000.0), 1e-6)
    assertEquals(99.8, arc.first()[2], 1e-12)
    assertEquals(99.0, arc.last()[2], 1e-12)
    assertEquals(listOf(2050.0, 1020.0), arc.last().take(2))
    assertTrue(arc.zipWithNext().all { (a, b) -> b[2] < a[2] })
    // дуга не уходит за хорду в сторону центра: точки — в четверти x ∈ [2030, 2050], y ∈ [1000, 1020]
    assertTrue(arc.all { it[0] in 2029.999..2050.001 && it[1] in 999.999..1020.001 })
    assertEquals(AlignmentsSection.format(10 * PI), geometry.attr(t2, "Длина в плане, м"))
    assertEquals("да", geometry.attr(t2, "Кривая"))
  }

  @Test
  fun `сверка с эталоном - колодцы, трубы, лотки`() {
    val bytes = fixture.readBytes()
    val expected = LandXmlReference.summarize(bytes)
    val network = expected.pipeNetworks.single()
    assertEquals(3, network.structs)
    assertEquals(listOf("Т1", "Т2"), network.pipes.map { it.name })
    assertEquals(listOf(100.5, 99.0, 99.8, 99.0), network.pipes.flatMap { listOf(it.start, it.end) })
    assertSummaryMatches(expected, summaryOf(read(bytes)), setOf(SURFACES, PIPE_NETWORKS), "pipenetwork.xml")
    val json = expected.toJson()
    assertEquals(json, parseSummaryJson(json).toJson())
  }

  @Test
  fun `имена колодцев - в пределах сети, отметки и центр по-разному`() {
    // в двух сетях колодцы с одинаковыми именами; во второй у колодца нет отметок, у другого — только дно
    val xml = """
      <LandXML version="1.2">
        <PipeNetworks>
          <PipeNetwork name="А" pipeNetType="storm">
            <Structs>
              <Struct name="1" elevSump="10"><Center>0 0</Center><InletStruct/><Invert elev="11" flowDir="out" refPipe="а"/></Struct>
              <Struct name="2" elevRim="12" elevSump="9"><Center>0 10</Center><OutletStruct/><Invert elev="10" flowDir="in" refPipe="а"/></Struct>
            </Structs>
            <Pipes><Pipe name="а" refStart="1" refEnd="2"><CircPipe diameter="0.3"/></Pipe></Pipes>
          </PipeNetwork>
          <PipeNetwork name="Б" pipeNetType="water">
            <Structs>
              <Struct name="1"><Center>100 0</Center><CircStruct diameter="1"/><Invert elev="5" flowDir="in" refPipe="x"/></Struct>
              <Struct name="2"><Center>100 10</Center><CircStruct diameter="1"/><Invert elev="5" flowDir="in" refPipe="x"/></Struct>
            </Structs>
            <Pipes><Pipe name="б" refStart="1" refEnd="2"><CircPipe diameter="0.3"/></Pipe></Pipes>
          </PipeNetwork>
        </PipeNetworks>
      </LandXML>
    """.trimIndent().encodeToByteArray()
    val geometry = read(xml)
    val (a, b) = geometry.objectsOf("Pipe").map { it.index }
    assertEquals(listOf(listOf(0.0, 0.0, 11.0), listOf(10.0, 0.0, 10.0)), geometry.polyline(a).map { it.toList() })
    // сеть Б: отметок нет — труба в плане (flat), колодцы — точками
    assertEquals(listOf(listOf(0.0, 100.0, 0.0), listOf(10.0, 100.0, 0.0)), geometry.polyline(b).map { it.toList() })
    assertTrue(geometry.batchOf(b).flat)
    assertEquals(LandXmlPalette.pipe("water"), geometry.batchOf(b).color)
    // колодец А/1 — только дно: точка на нём; А/2 без формы — вертикальный отрезок 9–12
    val structs = geometry.objectsOf("Struct").map { it.index }
    assertEquals(listOf(10.0, 0.0, 0.0), geometry.points.map { it.z })
    assertEquals(listOf(listOf(10.0, 0.0, 9.0), listOf(10.0, 0.0, 12.0)), geometry.polyline(structs[1]).map { it.toList() })
    assertTrue(geometry.meshes.none { ru(it.layer).startsWith("Колодцы") })
    assertSummaryMatches(LandXmlReference.summarize(xml), summaryOf(geometry), setOf(PIPE_NETWORKS))
  }

  @Test
  fun `сеть в сцене - рендер без ошибок`() {
    val scene = buildRenderScene(read(fixture.readBytes()))
    assertTrue(scene.surfaces.isNotEmpty())
  }
}
