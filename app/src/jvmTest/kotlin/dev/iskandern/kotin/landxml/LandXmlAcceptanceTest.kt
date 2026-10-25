package dev.iskandern.kotin.landxml

import org.junit.Test
import dev.iskandern.kotin.io.ByteArrayInput
import dev.iskandern.kotin.io.ByteInput
import dev.iskandern.kotin.io.DrawingFormat
import dev.iskandern.kotin.model.MAX_SCENE_SEGMENTS
import dev.iskandern.kotin.model.SceneGeometry
import dev.iskandern.kotin.ru
import java.io.File
import java.lang.management.ManagementFactory
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Приёмка скорости, памяти и лимитов (KoTIN-73) — то, что дёшево и не шумит: счётчики строк, медленных чисел
 * и объектов, выделенная при чтении память, прогресс до конца файла, лимит отрезков у всех источников линий,
 * вложенность и длинный `PntList`. Время и пик кучи на крупных файлах — [LandXmlLoadTest] (`LANDXML_BIG=1`).
 */
class LandXmlAcceptanceTest {

  private val fixtures = File("src/jvmTest/resources/landxml")

  private class Read(val geometry: SceneGeometry, val hotStrings: Long, val slowNumbers: Long, val progress: Long, val allocated: Long)

  // как приложение: все разделы, размер файла не передаётся; счётчики — по разделам поверхностей
  private fun read(input: ByteInput): Read {
    val surfaces = SurfacesSection(log = {})
    val sections = listOf(AlignmentsSection(log = {}), CgPointsSection(log = {}), surfaces)
    val slow = LandXmlNumbers.slowPathCount
    var progress = 0L
    val allocatedBefore = allocatedBytes()
    val geometry = LandXmlReader(DrawingFormat.detect(input).input, sections, log = {}).read { progress = it }
    val allocated = allocatedBytes() - allocatedBefore
    return Read(geometry, surfaces.hotStrings + surfaces.sourceData.hotStrings, LandXmlNumbers.slowPathCount - slow, progress, allocated)
  }

  private fun allocatedBytes(): Long =
    (ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean).currentThreadAllocatedBytes

  private fun corpus(): List<File> = listOf(fixtures, File(fixtures, "reference"))
    .flatMap { dir -> dir.listFiles { f -> f.extension == "xml" }.orEmpty().sortedBy { it.name } }

  @Test
  fun `синтетический TIN — строк, медленных чисел и объектов на вершины нет, прогресс до конца`() {
    val tin = SyntheticLandXml(points = 100_000)
    val bytes = tin.bytes()
    val result = read(ByteArrayInput(bytes))
    assertEquals(0, result.hotStrings, "строки в горячем цикле")
    assertEquals(0, result.slowNumbers, "числа на медленном пути")
    val geometry = result.geometry
    val vertices = geometry.meshes.sumOf { it.vertexCount }
    assertEquals(tin.faces - tin.invisibleFaces, geometry.meshes.single().triangleCount)
    // объект на сетку, ни точек, ни подписей на вершину
    val perThousand = (geometry.objects.size + geometry.points.size + geometry.labels.size) * 1000L / vertices
    assertEquals(0, perThousand, "объектов на 1000 вершин")
    assertEquals(bytes.size.toLong(), result.progress)
  }

  @Test
  fun `фикстуры — строк и медленных чисел нет, объект на сетку, прогресс до конца`() {
    val files = corpus()
    assertTrue(files.size >= 19)
    for (file in files) {
      val bytes = file.readBytes()
      val result = read(ByteArrayInput(bytes))
      val geometry = result.geometry
      assertEquals(0, result.hotStrings, "${file.name}: строки в горячем цикле")
      // медленный путь — только у радиуса спирали "INF" (по схеме), не у координат
      val infinities = Regex("\"INF\"").findAll(bytes.decodeToString()).count()
      assertEquals(infinities.toLong(), result.slowNumbers, "${file.name}: числа на медленном пути")
      assertEquals(geometry.meshes.size, geometry.objects.count { it.kind == SurfacesSection.KIND }, "${file.name}: объектов поверхностей")
      assertEquals(bytes.size.toLong(), result.progress, "${file.name}: прогресс")
    }
  }

  /**
   * Выделено за чтение (не пик, а всё созданное — пик не больше): куски и итоговые массивы сетки (2×), id точек
   * с ростом вдвое и таблица «id → индекс» (до [PER_POINT_SLACK] на точку), буфер токенизатора и мелочь.
   * До кусков (KoTIN-73) было 3,8× итоговых массивов: удвоение массивов и обрезанная копия в конце.
   */
  @Test
  fun `память — выделено при чтении не больше 2× итоговых массивов`() {
    val tin = SyntheticLandXml(points = 150_000)
    val bytes = tin.bytes()
    read(ByteArrayInput(bytes)) // прогрев: классы и таблицы
    val result = read(ByteArrayInput(bytes))
    val mesh = result.geometry.meshes.single()
    val arrays = mesh.coords.size * 8L + mesh.triangles.size * 4L
    println("выделено при чтении %.1f МБ, итоговые массивы %.1f МБ (%.2f×)".format(result.allocated / 1e6, arrays / 1e6, result.allocated.toDouble() / arrays))
    val budget = arrays * 2 + tin.points * PER_POINT_SLACK + ALLOCATION_SLACK
    assertTrue(result.allocated <= budget, "выделено ${result.allocated}, массивов $arrays, бюджет $budget")
  }

  @Test
  fun `лимит отрезков — дуги трасс, горизонтали, границы, структурные линии, цепочки`() {
    val list = (0 until 50).joinToString(" ") { "$it 0 1" }
    val list2D = (0 until 50).joinToString(" ") { "$it 0" }
    val sources = mapOf(
      "горизонтали" to """<Contours><Contour elev="5"><PntList2D>$list2D</PntList2D></Contour></Contours>""",
      "границы" to """<Boundaries><Boundary bndType="outer"><PntList3D>$list</PntList3D></Boundary></Boundaries>""",
      "структурные линии" to """<Breaklines><Breakline><PntList3D>$list</PntList3D></Breakline></Breaklines>""",
      "цепочки" to """<Chain>${(1..50).joinToString(" ") { "p$it" }}</Chain>""",
    )
    val points = (1..50).joinToString("") { """<CgPoint name="p$it">0 $it 1</CgPoint>""" }
    for ((what, body) in sources) {
      val text = """<LandXML xmlns="http://www.landxml.org/schema/LandXML-1.2" version="1.2"><CgPoints>$points</CgPoints>""" +
        """<Surfaces><Surface name="S"><SourceData>$body</SourceData></Surface></Surfaces></LandXML>"""
      val geometry = readLimited(text, maxSegments = 10)
      assertTrue(geometry.truncated, what)
      assertEquals(10, geometry.lines.sumOf { it.segmentCount }, what)
    }
    // дуга: 1/4 окружности радиусом 100 разбивается на десятки отрезков
    val arc = """<LandXML xmlns="http://www.landxml.org/schema/LandXML-1.2" version="1.2"><Alignments><Alignment name="A" length="157.08" staStart="0">
      <CoordGeom><Curve rot="ccw" radius="100" length="157.08"><Start>0 0</Start><Center>0 100</Center><End>100 100</End></Curve></CoordGeom>
      </Alignment></Alignments></LandXML>"""
    val full = readLimited(arc, maxSegments = MAX_SCENE_SEGMENTS)
    assertFalse(full.truncated)
    assertTrue(full.lines.sumOf { it.segmentCount } > 10)
    val limited = readLimited(arc, maxSegments = 10)
    assertTrue(limited.truncated)
    assertEquals(10, limited.lines.sumOf { it.segmentCount })
  }

  // чтение всеми разделами с накопителем с лимитом [maxSegments] — как LandXmlReader, но лимит свой
  private fun readLimited(text: String, maxSegments: Int): SceneGeometry {
    val sections = listOf(AlignmentsSection(log = {}), CgPointsSection(log = {}), SurfacesSection(log = {}))
    val scene = LandXmlSceneBuilder(maxSegments)
    val roots = XmlNames(*Array(sections.size) { sections[it].element })
    val xml = XmlTokenizer(ByteArrayInput(text.encodeToByteArray()), roots)
    while (xml.next() != XmlTokenizer.EOF) {
      if (xml.event != XmlTokenizer.START || xml.depth != 2) continue
      val section = sections[xml.nameId.takeIf { it >= 0 } ?: continue]
      xml.names = section.names
      section.read(xml, scene)
      xml.names = roots
    }
    return scene.finish()
  }

  @Test
  fun `вложенность — до предела читается, глубже понятная ошибка, а не падение`() {
    fun nested(levels: Int) = buildString {
      append("""<LandXML version="1.2"><Surfaces><Surface name="S">""")
      repeat(levels) { append("<Feature>") }
      repeat(levels) { append("</Feature>") }
      append("""<Definition surfType="TIN"><Pnts><P id="1">0 0 0</P><P id="2">0 10 0</P><P id="3">10 0 0</P></Pnts>""")
      append("""<Faces><F>1 2 3</F></Faces></Definition></Surface></Surfaces></LandXML>""")
    }
    val ok = read(ByteArrayInput(nested(XmlTokenizer.MAX_DEPTH - 3).encodeToByteArray())).geometry
    assertEquals(1, ok.meshes.single().triangleCount)
    val e = assertFailsWith<XmlFormatException> { read(ByteArrayInput(nested(XmlTokenizer.MAX_DEPTH * 4).encodeToByteArray())) }
    assertTrue("вложенность" in ru(e.message!!), ru(e.message!!))
  }

  @Test
  fun `длинный PntList — читается потоком, без строк`() {
    // 300 000 точек ≈ 9 МБ одним текстом; 100 МБ — в LandXmlLoadTest
    val input = LongPntList(300_000)
    val result = read(input)
    assertEquals(0, result.hotStrings)
    assertEquals(299_999, result.geometry.lines.sumOf { it.segmentCount })
    assertEquals(input.produced, result.progress)
  }

  private companion object {
    // буфер токенизатора (до 256 КБ, растёт с 16 КБ), сцена, строки консоли
    const val ALLOCATION_SLACK = 2L shl 20
    // id точек (4 Б, рост вдвое — до 8 Б) и таблица «id → индекс» (4 Б на id до наибольшего)
    const val PER_POINT_SLACK = 16L
  }
}

/** Структурная линия из [points] точек одним `PntList3D`, на лету — без файла и без текста в памяти. */
class LongPntList(private val points: Int) : ByteInput {
  private var pending = ByteArray(0)
  private var at = 0
  private var next = -1
  var produced = 0L
    private set

  override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
    if (at >= pending.size && !refill()) return -1
    val n = minOf(length, pending.size - at)
    pending.copyInto(buffer, offset, at, at + n)
    at += n
    produced += n
    return n
  }

  private fun refill(): Boolean {
    val sb = StringBuilder()
    when {
      next == -1 -> {
        sb.append("""<LandXML xmlns="http://www.landxml.org/schema/LandXML-1.2" version="1.2"><Surfaces><Surface name="S">""")
        sb.append("<SourceData><Breaklines><Breakline><PntList3D>")
        next = 0
      }
      next < points -> {
        val end = minOf(points, next + 10_000)
        for (i in next until end) {
          sb.append(6_000_000 + i / 1000).append('.').append(i % 1000).append(' ')
            .append(400_000 + i % 1000).append(".25 ").append(100 + i % 7).append(".5 ")
        }
        next = end
      }
      next == points -> {
        sb.append("</PntList3D></Breakline></Breaklines></SourceData></Surface></Surfaces></LandXML>")
        next++
      }
      else -> return false
    }
    pending = sb.toString().encodeToByteArray()
    at = 0
    return true
  }
}
