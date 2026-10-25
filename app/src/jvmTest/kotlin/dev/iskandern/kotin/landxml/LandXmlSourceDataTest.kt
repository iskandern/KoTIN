package dev.iskandern.kotin.landxml

import org.junit.Test
import dev.iskandern.kotin.io.ByteArrayInput
import dev.iskandern.kotin.model.LineBatch
import dev.iskandern.kotin.model.SceneGeometry
import dev.iskandern.kotin.ru
import dev.iskandern.kotin.scene.buildRenderScene
import dev.iskandern.kotin.io.DrawingFormat
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Исходные данные поверхности `SourceData` (KoTIN-61): границы, структурные линии, горизонтали, точки данных, Chain. */
class LandXmlSourceDataTest {

  private val fixtures = File("src/jvmTest/resources/landxml")
  private val logs = ArrayList<String>()

  private class Result(val surfaces: SurfacesSection, val geometry: SceneGeometry)

  // все разделы читателя; раздел поверхностей — свой, чтобы видеть счётчики
  private fun read(bytes: ByteArray): Result {
    val surfaces = SurfacesSection(log = { logs.add(ru(it)) })
    val detected = DrawingFormat.detect(ByteArrayInput(bytes))
    assertEquals(DrawingFormat.LANDXML, detected.format)
    val sections = listOf(AlignmentsSection(log = {}), CgPointsSection(log = {}), surfaces)
    return Result(surfaces, LandXmlReader(detected.input, sections, log = {}).read())
  }

  private fun read(file: File) = read(file.readBytes())

  // только раздел поверхностей, накопитель с лимитом [maxSegments]
  private fun readSurfaces(text: String, maxSegments: Int): SceneGeometry {
    val section = SurfacesSection(log = { logs.add(ru(it)) })
    val scene = LandXmlSceneBuilder(maxSegments)
    val xml = XmlTokenizer(ByteArrayInput(text.encodeToByteArray()), section.names)
    while (xml.next() != XmlTokenizer.EOF) {
      if (xml.event == XmlTokenizer.START && xml.depth == 2 && xml.localName() == "Surfaces") section.read(xml, scene)
    }
    return scene.finish()
  }

  private fun surfaces(body: String) =
    """<LandXML xmlns="http://www.landxml.org/schema/LandXML-1.2" version="1.2"><Surfaces>$body</Surfaces></LandXML>"""

  // отрезки объекта [objectId]: x1 y1 z1 x2 y2 z2
  private fun segments(geometry: SceneGeometry, objectId: Int): List<List<Double>> = geometry.lines.flatMap { batch ->
    val ids = batch.objectIds!!
    (0 until batch.segmentCount).filter { ids[it] == objectId }.map { i -> (0 until 6).map { batch.coords[i * 6 + it] } }
  }

  private fun batchOf(geometry: SceneGeometry, objectId: Int): LineBatch =
    geometry.lines.single { b -> b.objectIds!!.any { it == objectId } }

  private fun objectId(geometry: SceneGeometry, kind: String, name: String) =
    geometry.objects.indexOfFirst { it.kind == kind && it.name == name }.also { assertTrue(it >= 0, "нет $kind «$name»") }

  private fun length(segments: List<List<Double>>) = segments.sumOf { s ->
    val dx = s[3] - s[0]; val dy = s[4] - s[1]; val dz = s[5] - s[2]
    sqrt(dx * dx + dy * dy + dz * dz)
  }

  private fun planLength(segments: List<List<Double>>) = segments.sumOf { s -> hypot(s[3] - s[0], s[4] - s[1]) }

  // отрезков в плане: куски посадки по рёбрам TIN сливаются обратно
  private fun planCount(segments: List<List<Double>>) = planSegments(segments.map { doubleArrayOf(it[0], it[1], it[3], it[4]) })

  // «Площадка» фикстуры — одна плоскость: 100 на юге … 104 на севере
  private fun plateau(north: Double) = 100 + (north - 6000000) * 0.2

  @Test
  fun `фикстура — слои, отрезки, замыкание, оси, отметки горизонталей, точки данных, Chain`() {
    val geometry = read(File(fixtures, "sourcedata.xml")).geometry
    assertEquals(
      listOf("Площадка", "Площадка: границы", "Площадка: структурные линии", "Площадка: горизонтали",
        "Площадка: точки данных", "Только данные: структурные линии", "Точки").sorted(),
      geometry.layers.map { ru(it.name) }.sorted(),
    )
    assertFalse(geometry.truncated)

    // внешняя граница без повтора первой точки — замкнута; x — восток (второе число), y — север
    val outer = objectId(geometry, SurfaceSourceData.BOUNDARY_KIND, "Внешняя")
    val outerSegments = segments(geometry, outer)
    assertEquals(4, outerSegments.size)
    assertEquals(listOf(400000.0, 6000000.0, 100.0, 400020.0, 6000000.0, 100.0), outerSegments.first())
    assertEquals(listOf(400000.0, 6000020.0, 104.0, 400000.0, 6000000.0, 100.0), outerSegments.last())
    assertEquals(40 + 2 * sqrt(416.0), length(outerSegments), 1e-9)
    assertFalse(batchOf(geometry, outer).flat)
    val outerObject = geometry.objects[outer]
    assertEquals("Площадка: границы", ru(outerObject.layer))
    assertEquals(LandXmlPalette.boundary("outer"), outerObject.color)
    val attributes = outerObject.attributes.associate { ru(it.name) to ru(it.value) }
    assertEquals("outer", attributes["Тип"])
    assertEquals("true", attributes["Обрезка рёбер (edgeTrim)"])
    assertEquals("400", attributes["Площадь"])

    // полость PntList2D с повтором первой точки: лишнего отрезка нет, свой цвет; 2D — села на поверхность (KoTIN-67):
    // куски по рёбрам TIN на рельефе, в плане те же 4 отрезка, замкнута, длина 20
    val void = objectId(geometry, SurfaceSourceData.BOUNDARY_KIND, "Колодец")
    val voidSegments = segments(geometry, void)
    assertEquals(4, planCount(voidSegments))
    assertEquals(20.0, planLength(voidSegments), 1e-9)
    assertEquals(voidSegments.first().take(2), voidSegments.last().subList(3, 5))
    assertFalse(batchOf(geometry, void).flat)
    assertTrue(voidSegments.all { abs(it[2] - plateau(it[1])) < 1e-9 && abs(it[5] - plateau(it[4])) < 1e-9 })
    assertEquals(LandXmlPalette.boundary("void"), batchOf(geometry, void).color)

    // структурные линии: цвет по brkType, 2D — на поверхности
    val ridge = objectId(geometry, SurfaceSourceData.BREAKLINE_KIND, "Бровка")
    assertEquals(2, segments(geometry, ridge).size)
    assertEquals(2 * sqrt(104.0), length(segments(geometry, ridge)), 1e-9)
    assertEquals(LandXmlPalette.BREAKLINE, batchOf(geometry, ridge).color)
    // стена: в плане тот же отрезок 400002 → 400018 на севере 6000002, отметка рельефа 100,4
    val wall = objectId(geometry, SurfaceSourceData.BREAKLINE_KIND, "Стена")
    val wallSegments = segments(geometry, wall)
    assertEquals(1, planCount(wallSegments))
    assertEquals(listOf(400002.0, 6000002.0), wallSegments.first().take(2))
    assertEquals(listOf(400018.0, 6000002.0), wallSegments.last().subList(3, 5))
    assertEquals(16.0, planLength(wallSegments), 1e-9)
    assertTrue(wallSegments.all { abs(it[2] - 100.4) < 1e-9 && abs(it[5] - 100.4) < 1e-9 })
    assertFalse(batchOf(geometry, wall).flat)
    assertEquals(LandXmlPalette.breakline("wall"), batchOf(geometry, wall).color)
    val untyped = objectId(geometry, SurfaceSourceData.BREAKLINE_KIND, "")
    assertTrue(geometry.objects[untyped].attributes.isEmpty())
    assertEquals(LandXmlPalette.BREAKLINE, batchOf(geometry, untyped).color)

    // горизонтали: на высоте elev, не плоские, один пакет на слой, объект без атрибутов
    val contours = geometry.objects.withIndex().filter { it.value.kind == SurfaceSourceData.CONTOUR_KIND }
    assertEquals(listOf("101", "102"), contours.map { it.value.name })
    assertTrue(contours.all { it.value.attributes.isEmpty() })
    assertEquals(2, segments(geometry, contours[0].index).size)
    assertTrue(segments(geometry, contours[0].index).all { it[2] == 101.0 && it[5] == 101.0 })
    assertTrue(segments(geometry, contours[1].index).all { it[2] == 102.0 && it[5] == 102.0 })
    val contourBatches = geometry.lines.filter { ru(it.layer) == "Площадка: горизонтали" }
    assertEquals(1, contourBatches.size)
    assertFalse(contourBatches[0].flat)
    assertEquals(3, contourBatches[0].segmentCount)

    // точки данных — значки без объектов
    val data = geometry.points.filter { ru(it.layer) == "Площадка: точки данных" }
    assertEquals(5, data.size)
    assertEquals(listOf(400000.0, 6000000.0, 100.0), data[0].run { listOf(x, y, z) })
    assertTrue(data.all { it.objectId < 0 })

    // Chain: точки CgPoint после поверхности — разрешены в finish()
    val chain = objectId(geometry, SurfaceSourceData.CHAIN_KIND, "Цепочка")
    assertEquals(
      listOf(listOf(400001.0, 6000001.0, 100.1, 400019.0, 6000001.0, 100.1), listOf(400019.0, 6000001.0, 100.1, 400019.0, 6000019.0, 103.9)),
      segments(geometry, chain),
    )
    assertEquals("Площадка: структурные линии", ru(geometry.objects[chain].layer))

    // поверхность без Definition: сетки нет, линии есть
    assertEquals(listOf("Площадка"), geometry.meshes.map { ru(it.layer) })
    val near = geometry.objects.indexOfFirst { ru(it.layer) == "Только данные: структурные линии" }
    assertEquals(10.0, length(segments(geometry, near)), 1e-9)
    assertEquals(LandXmlPalette.breakline("proximity"), batchOf(geometry, near).color)

    // объект — на каждый отрезок
    for (batch in geometry.lines) assertEquals(batch.segmentCount, batch.objectIds!!.size)
  }

  @Test
  fun `короткие списки и горизонталь без elev — пропуск со счётчиком`() {
    val geometry = read(surfaces("""
      <Surface name="S"><SourceData>
        <Breaklines><Breakline><PntList3D>1 2 3</PntList3D></Breakline><Breakline><PntList2D>1 2 3 4</PntList2D></Breakline></Breaklines>
        <Contours><Contour><PntList2D>1 2 3 4</PntList2D></Contour><Contour elev="5"><PntList2D>1 2</PntList2D></Contour></Contours>
        <Boundaries><Boundary bndType="outer" edgeTrim="true"><PntList2D>0 0 0 10 10 10</PntList2D></Boundary></Boundaries>
      </SourceData></Surface>
    """.trimIndent()).encodeToByteArray()).geometry
    assertEquals(1, geometry.objects.count { it.kind == SurfaceSourceData.BREAKLINE_KIND })
    assertEquals(0, geometry.objects.count { it.kind == SurfaceSourceData.CONTOUR_KIND })
    // граница-треугольник без повтора — три отрезка
    assertEquals(3, geometry.lines.single { ru(it.layer) == "S: границы" }.segmentCount)
    assertTrue(logs.any { "списков меньше двух точек 2" in it && "горизонталей без elev 1" in it }, logs.toString())
  }

  @Test
  fun `лимит отрезков — флаг truncated`() {
    val list = (0 until 100).joinToString(" ") { "$it 0 1" }
    val geometry = readSurfaces(surfaces("""<Surface name="S"><SourceData><Breaklines><Breakline>""" +
      """<PntList3D>$list</PntList3D></Breakline></Breaklines></SourceData></Surface>"""), maxSegments = 10)
    assertTrue(geometry.truncated)
    assertEquals(10, geometry.lines.sumOf { it.segmentCount })
  }

  @Test
  fun `длинный список читается без строк — мелкими кусками буфера тоже`() {
    val n = 20_000
    val list = StringBuilder()
    for (k in 0 until n) list.append("6000000.123456789012 ").append(400000 + k).append(".5 ").append(100 + k % 7).append(' ')
    val text = surfaces("""<Surface name="S"><SourceData><Breaklines><Breakline brkType="standard">""" +
      """<PntList3D>$list</PntList3D></Breakline></Breaklines><DataPoints><PntList3D>$list</PntList3D></DataPoints>""" +
      """</SourceData></Surface>""")
    val result = read(text.encodeToByteArray())
    assertEquals(0L, result.surfaces.sourceData.hotStrings)
    assertEquals(n - 1, result.geometry.lines.single().segmentCount)
    assertEquals(n, result.geometry.points.size)
    val first = result.geometry.lines.single().coords
    assertEquals(400000.5, first[0])
    assertEquals(6000000.123456789, first[1], 1e-6)
  }

  @Test
  fun `точек данных больше лимита — значков нет, строка в консоль`() {
    val n = SurfaceSourceData.MAX_DATA_POINTS + 1
    val list = StringBuilder()
    for (k in 0 until n) list.append(k % 1000).append(' ').append(k / 1000).append(" 1 ")
    val result = read(surfaces("""<Surface name="S"><SourceData><DataPoints><PntList3D>$list</PntList3D></DataPoints>""" +
      """</SourceData></Surface>""").encodeToByteArray())
    assertTrue(result.geometry.points.isEmpty())
    assertTrue(logs.any { "точек данных $n" in it && "не показываются" in it }, logs.toString())
  }

  @Test
  fun `фикстура — эталон сходится с читателем`() {
    val bytes = File(fixtures, "sourcedata.xml").readBytes()
    val reference = LandXmlReference.summarize(bytes)
    val kinds = reference.sourceData.associate { "${it.surface} / ${it.kind}" to it }
    // числа «руками» из описания фикстуры; отрезки и длина — в плане: у бровки и горизонтали 101 три точки на одной
    // прямой — по одному отрезку
    val boundary = kinds.getValue("Площадка / Boundary")
    assertEquals(listOf(2, 9, 8), listOf(boundary.lists, boundary.points, boundary.segments))
    assertEquals(80 + 20.0, boundary.length, 1e-9)
    val breakline = kinds.getValue("Площадка / Breakline")
    assertEquals(listOf(3, 7, 3), listOf(breakline.lists, breakline.points, breakline.segments))
    assertEquals(20 + 16 + 16.0, breakline.length, 1e-9)
    val contour = kinds.getValue("Площадка / Contour")
    assertEquals(listOf(2, 5, 2), listOf(contour.lists, contour.points, contour.segments))
    assertEquals(40.0, contour.length, 1e-9)
    assertEquals(listOf(2, 5), kinds.getValue("Площадка / DataPoints").run { listOf(lists, points) })
    assertEquals(10.0, kinds.getValue("Только данные / Breakline").length, 1e-9)
    assertSummaryMatches(reference, summaryOf(read(bytes).geometry), setOf(SURFACES, SOURCE_DATA), "sourcedata.xml")
  }

}
