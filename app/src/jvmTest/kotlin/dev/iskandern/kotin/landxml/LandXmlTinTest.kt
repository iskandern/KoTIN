package dev.iskandern.kotin.landxml

import org.junit.Test
import dev.iskandern.kotin.io.ByteArrayInput
import dev.iskandern.kotin.dxf.DxfDocumentReader
import dev.iskandern.kotin.dxf.DxfFlattener
import dev.iskandern.kotin.model.SceneGeometry
import dev.iskandern.kotin.model.SceneMesh
import dev.iskandern.kotin.io.asByteInput
import dev.iskandern.kotin.ru
import dev.iskandern.kotin.scene.buildRenderScene
import dev.iskandern.kotin.io.DrawingFormat
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Поверхности TIN и grid (KoTIN-60): фикстуры, сверка с эталоном, скорость против DXF. */
class LandXmlTinTest {

  private val fixtures = File("src/jvmTest/resources/landxml")
  private val logs = ArrayList<String>()

  private fun read(bytes: ByteArray, section: SurfacesSection = SurfacesSection(log = { logs.add(ru(it)) })): SceneGeometry {
    val detected = DrawingFormat.detect(ByteArrayInput(bytes))
    assertEquals(DrawingFormat.LANDXML, detected.format)
    return LandXmlReader(detected.input, listOf(section), log = {}).read()
  }

  private fun read(name: String) = read(File(fixtures, name).readBytes())

  private fun readText(text: String) = read(text.encodeToByteArray())

  private fun surfaces(body: String) =
    """<LandXML xmlns="http://www.landxml.org/schema/LandXML-1.2" version="1.2"><Surfaces>$body</Surfaces></LandXML>"""

  private fun vertex(mesh: SceneMesh, v: Int) = listOf(mesh.coords[v * 3], mesh.coords[v * 3 + 1], mesh.coords[v * 3 + 2])

  // z-составляющая нормали каждого треугольника (удвоенная площадь в плане со знаком)
  private fun normalsZ(mesh: SceneMesh) = (0 until mesh.triangleCount).map { k ->
    val c = mesh.coords
    val a = mesh.triangles[k * 3] * 3
    val b = mesh.triangles[k * 3 + 1] * 3
    val d = mesh.triangles[k * 3 + 2] * 3
    (c[b] - c[a]) * (c[d + 1] - c[a + 1]) - (c[b + 1] - c[a + 1]) * (c[d] - c[a])
  }

  @Test
  fun `две поверхности — слои, вершины, треугольники, оси, нормаль вверх, объекты`() {
    val geometry = read("tin-two-surfaces.xml")
    assertEquals(listOf("Площадка", "Откос"), geometry.layers.map { ru(it.name) })
    assertNotEquals(geometry.layers[0].trueColor, geometry.layers[1].trueColor)
    val (site, slope) = geometry.meshes
    assertEquals("Площадка", ru(site.layer))

    // точка 15 — только в невидимой грани, грань с id 77 пропущена
    assertEquals(4, site.vertexCount)
    assertEquals(2, site.triangleCount)
    // E, N, Z: первое число P — север
    assertEquals(listOf(400000.0, 6000000.0, 100.0), vertex(site, 0))
    assertEquals(listOf(400010.0, 6000010.0, 105.0), vertex(site, 2))
    assertEquals(listOf(400000.0, 6000000.0, 100.0, 400010.0, 6000010.0, 105.0),
      site.run { listOf(minX, minY, minZ, maxX, maxY, maxZ) })
    assertTrue(normalsZ(site).all { it > 0 })
    assertNull(site.edgeMask)

    // записан по часовой — развёрнут
    assertEquals(3, slope.vertexCount)
    assertEquals(1, slope.triangleCount)
    assertTrue(normalsZ(slope).all { it > 0 })

    assertEquals(2, geometry.objects.size)
    val obj = geometry.objects[site.objectId]
    assertEquals(SurfacesSection.KIND, obj.kind)
    assertEquals("Площадка", obj.name)
    assertEquals("Площадка", ru(obj.layer))
    val attributes = obj.attributes.associate { ru(it.name) to ru(it.value) }
    assertEquals("Рельеф площадки", attributes["Описание"])
    assertEquals("TIN", attributes["Тип"])
    assertEquals("existing", attributes["Состояние"])
    assertEquals("100", attributes["Площадь 2D"])
    assertEquals("111.803", attributes["Площадь 3D"])
    assertEquals("100", attributes["Отметка мин."])
    assertEquals("105", attributes["Отметка макс."])
    assertEquals("5", attributes["Точек"])
    assertEquals("4", attributes["Граней"])
    assertEquals("1", attributes["Невидимых граней"])
    assertEquals("Откос", geometry.objects[slope.objectId].name)

    assertTrue(logs.any { "«Площадка»: граней с неизвестным id 1" in it }, logs.toString())
    assertTrue(logs.any { "«Откос»: 1 граней записаны по часовой" in it }, logs.toString())
    // большие координаты сдвигаются к началу при подготовке сцены
    assertTrue(buildRenderScene(geometry).radius.isFinite())
  }

  @Test
  fun `версия 1_0 — F без атрибутов`() {
    val geometry = read("tin-1.0.xml")
    val mesh = geometry.meshes.single()
    assertEquals("Рельеф 1.0", ru(mesh.layer))
    assertEquals(5, mesh.vertexCount)
    assertEquals(4, mesh.triangleCount)
    assertTrue(normalsZ(mesh).all { it > 0 })
    assertEquals(listOf(100.0, 200.0, 10.0, 120.0, 220.0, 14.0), mesh.run { listOf(minX, minY, minZ, maxX, maxY, maxZ) })
    val attributes = geometry.objects.single().attributes.associate { ru(it.name) to ru(it.value) }
    assertNull(attributes["Площадь 2D"])
    assertEquals("4", attributes["Граней"])
  }

  @Test
  fun `grid — ячейка из четырёх id даёт два треугольника со скрытой диагональю, поверхность без Definition не падает`() {
    val geometry = read("grid.xml")
    val mesh = geometry.meshes.single()
    assertEquals("Сетка", ru(mesh.layer))
    assertEquals(9, mesh.vertexCount)
    // две ячейки в первом Faces, две во втором
    assertEquals(8, mesh.triangleCount)
    assertTrue(normalsZ(mesh).all { it > 0 })
    // первая ячейка 1 2 5 4: треугольники 1-2-5 и 1-5-4, ребро 1–5 скрыто в обоих
    assertContentEquals(intArrayOf(0, 1, 4, 0, 4, 3), mesh.triangles.copyOf(6))
    val mask = mesh.edgeMask!!
    assertEquals(8, mask.size)
    for (k in 0 until 4) {
      assertEquals(3, mask[k * 2].toInt())
      assertEquals(6, mask[k * 2 + 1].toInt())
    }
    assertEquals("grid", geometry.objects.single().attributes.first { ru(it.name) == "Тип" }.value)
    assertEquals(1, geometry.objects.size)
    assertTrue(logs.any { "«Только исходные» нет Definition" in it }, logs.toString())
    // в каркасе нет диагоналей: 12 рёбер сетки 3 × 3
    val scene = buildRenderScene(geometry)
    assertEquals(12, scene.surfaces.single().edges.size / 2)
  }

  @Test
  fun `развёрнутая ячейка grid — скрыта та же диагональ`() {
    // ячейка по часовой: 1 4 5 2
    val geometry = readText(surfaces("""<Surface name="g"><Definition surfType="grid"><Pnts>
      <P id="1">0 0 0</P><P id="2">0 10 0</P><P id="4">10 0 0</P><P id="5">10 10 0</P>
      </Pnts><Faces><F>1 4 5 2</F></Faces></Definition></Surface>"""))
    val mesh = geometry.meshes.single()
    assertTrue(normalsZ(mesh).all { it > 0 })
    // четыре стороны ячейки, без диагонали
    assertEquals(4, buildRenderScene(geometry).surfaces.single().edges.size / 2)
  }

  @Test
  fun `Faces раньше Pnts — понятная ошибка`() {
    val e = assertFailsWith<XmlFormatException> {
      readText(surfaces("""<Surface name="X"><Definition surfType="TIN"><Faces><F>1 2 3</F></Faces><Pnts><P id="1">0 0 0</P></Pnts></Definition></Surface>"""))
    }
    assertTrue("Faces раньше Pnts" in ru(e.message!!), ru(e.message!!))
  }

  @Test
  fun `точка без отметки, вырожденная грань, лишние id, повтор id, одинаковые имена`() {
    val geometry = readText(surfaces("""
      <Surface name="A"><Definition surfType="TIN"><Pnts>
        <P id="1">0 0</P><P id="2">0 10 5</P><P id="3">10 0 5</P><P id="3">99 99 99</P><P>1 1 1</P>
      </Pnts><Faces>
        <F>1 2 3</F><F>1 1 3</F><F>1 2 3 1 2</F><F>1 2</F><F>1 x 3</F>
      </Faces></Definition></Surface>
      <Surface name="A"><Definition><Pnts><P id="7">0 0 0</P><P id="8">0 1 0</P><P id="9">1 0 0</P></Pnts>
      <Faces><F>7 8 9</F></Faces></Definition></Surface>"""))
    val (first, second) = geometry.meshes
    assertEquals(listOf("A", "A (2)"), listOf(first.layer, second.layer))
    assertEquals(listOf("A", "A"), geometry.objects.map { it.name })
    assertEquals(1, first.triangleCount)
    // первая точка без отметки — z = 0; повтор id 3 — действует первая
    assertEquals(listOf(0.0, 0.0, 0.0), vertex(first, 0))
    assertEquals(listOf(0.0, 10.0, 5.0), vertex(first, 2))
    assertEquals(3, first.vertexCount)
    // нет surfType — TIN
    assertEquals("TIN", geometry.objects[1].attributes.first { ru(it.name) == "Тип" }.value)
    assertTrue(logs.any { "«A»: граней с неизвестным id 3, вырожденных 1" in it && "точек без id или координат 1, повторов id 1" in it }, logs.toString())
  }

  @Test
  fun `редкие id — таблица через хеш`() {
    val geometry = readText(surfaces("""<Surface name="H"><Definition surfType="TIN"><Pnts>
      <P id="1000000">0 0 1</P><P id="7">0 10 2</P><P id="2000000000">10 0 3</P>
      </Pnts><Faces><F>1000000 7 2000000000</F><F>7 8 2000000000</F></Faces></Definition></Surface>"""))
    val mesh = geometry.meshes.single()
    assertEquals(1, mesh.triangleCount)
    assertEquals(listOf(0.0, 0.0, 1.0, 0.0, 10.0, 3.0), vertex(mesh, 0) + vertex(mesh, 2))
  }

  /** Фикстуры: сводка сцены совпадает с эталоном, площади и отметки сетки — с контрольными числами файла. */
  @Test
  fun `сверка с эталоном и контрольными числами`() {
    val files = (listOf(fixtures, File(fixtures, "reference"), File(fixtures, "synthetic"))
      .flatMap { dir -> dir.listFiles { f -> f.extension == "xml" }.orEmpty().sortedBy { it.name } })
    assertTrue(files.size >= 20)
    // ошибка экспортёра в самом файле
    val knownControl = mapOf("bad-control-numbers.xml" to "area2DSurf")
    var checked = 0
    for (file in files) {
      val bytes = file.readBytes()
      val reference = LandXmlReference.summarize(bytes)
      val scene = summaryOf(read(bytes))
      assertSummaryMatches(reference, scene, setOf(SURFACES), file.name)
      val withFaces = reference.surfaces.filter { it.visibleFaces > 0 }
      for ((ours, ref) in scene.surfaces.zip(withFaces)) {
        val control = SurfaceSummary(ours.name, null, null, ours.visibleFaces, null, ours.usedPoints, ours.box, ours.area2D, ours.area3D,
          ref.fileArea2D, ref.fileArea3D, ref.fileElevMin, ref.fileElevMax)
        val mismatches = controlMismatches(control).filter { m -> knownControl[file.name]?.let { it !in m } ?: true }
        assertTrue(mismatches.isEmpty(), "${file.name}: $mismatches")
        if (ref.fileArea2D != null) checked++
      }
    }
    assertTrue(checked >= 8, "поверхностей с контрольными числами: $checked")
  }

  @Test
  fun `TIN читается без строк и медленного пути, не медленнее DXF с теми же 3DFACE`() {
    val tin = SyntheticLandXml(points = 150_000)
    val xmlFile = File.createTempFile("synthetic", ".xml").apply { deleteOnExit() }
    val dxfFile = File.createTempFile("synthetic", ".dxf").apply { deleteOnExit() }
    tin.writeTo(xmlFile)
    DxfBaseline(tin).writeTo(dxfFile)

    lateinit var section: SurfacesSection
    lateinit var geometry: SceneGeometry
    val slow = LandXmlNumbers.slowPathCount
    val xmlResult = Bench.run("LandXmlReader: TIN", xmlFile.length()) {
      section = SurfacesSection(log = {})
      geometry = xmlFile.inputStream().asByteInput().use { input ->
        LandXmlReader(DrawingFormat.detect(input).input, listOf(section), log = {}, sizeBytes = xmlFile.length()).read()
      }
    }
    val slowNumbers = LandXmlNumbers.slowPathCount - slow
    println("LandXML TIN: точек ${tin.points}, граней ${tin.faces}; чисел на медленном пути $slowNumbers, строк в цикле ${section.hotStrings}")
    assertEquals(0, slowNumbers, "числа на медленном пути")
    assertEquals(0, section.hotStrings, "строки в цикле по P/F")
    val mesh = geometry.meshes.single()
    assertEquals(tin.faces - tin.invisibleFaces, mesh.triangleCount)
    assertEquals(1, geometry.objects.size)

    var dxfTriangles = 0
    val dxfResult = Bench.run("DXF: тот же TIN (3DFACE)", dxfFile.length()) {
      val dxf = dxfFile.inputStream().asByteInput().use { DxfFlattener(DxfDocumentReader(it).read()).flatten() }
      dxfTriangles = dxf.meshes.sumOf { it.triangleCount }
    }
    assertEquals(tin.faces, dxfTriangles)
    // по треугольникам в секунду: файлы разного размера; время шумит — допуск вдвое, как у стенда токенизатора
    val xmlRate = mesh.triangleCount / xmlResult.medianMs
    val dxfRate = dxfTriangles / dxfResult.medianMs
    println("треугольников в мс: LandXML %.0f, DXF %.0f; пик кучи LandXML %.1f МБ, DXF %.1f МБ"
      .format(xmlRate, dxfRate, xmlResult.peakHeapMb, dxfResult.peakHeapMb))
    assertTrue(xmlRate * 2 >= dxfRate, "LandXML %.0f треуг./мс, DXF %.0f".format(xmlRate, dxfRate))
    assertTrue(xmlResult.peakHeapMb <= dxfResult.peakHeapMb * 2, "пик кучи LandXML ${xmlResult.peakHeapMb} МБ, DXF ${dxfResult.peakHeapMb} МБ")
  }
}
