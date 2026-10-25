package dev.iskandern.kotin

import dev.iskandern.kotin.dxf.DxfDocumentReader
import dev.iskandern.kotin.dxf.DxfEntity
import dev.iskandern.kotin.dxf.DxfFlattener
import dev.iskandern.kotin.dxf.DxfLayout
import dev.iskandern.kotin.dxf.DxfViewport
import dev.iskandern.kotin.io.asByteInput
import dev.iskandern.kotin.dxf.formatViewportScale
import dev.iskandern.kotin.dxf.DxfColors
import dev.iskandern.kotin.dxf.SheetWindow
import dev.iskandern.kotin.dxf.sheetWithViewports
import dev.iskandern.kotin.dxf.viewportScale
import dev.iskandern.kotin.model.FillBatch
import dev.iskandern.kotin.model.LineBatch
import dev.iskandern.kotin.model.MAX_SCENE_SEGMENTS
import dev.iskandern.kotin.model.SceneGeometry
import dev.iskandern.kotin.model.SceneLabel
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DxfLayoutTest {

  private fun read(vararg pairs: String) =
    DxfDocumentReader(pairs.joinToString("\n", postfix = "\n").byteInputStream().asByteInput()).read()

  private fun readFile(name: String) =
    File(testDxfDir, "$name").inputStream().use { DxfDocumentReader(it.asByteInput()).read() }

  // запись блока *Paper_Space (handle 1F), LINE модели, LINE и VIEWPORT листа в ENTITIES, LAYOUT со ссылкой на 1F
  private val inline = arrayOf(
    "0", "SECTION", "2", "TABLES", "0", "TABLE", "2", "BLOCK_RECORD",
    "0", "BLOCK_RECORD", "5", "1E", "2", "*Model_Space",
    "0", "BLOCK_RECORD", "5", "1F", "2", "*Paper_Space",
    "0", "ENDTAB", "0", "ENDSEC",
    "0", "SECTION", "2", "ENTITIES",
    "0", "LINE", "8", "M", "10", "0", "20", "0", "11", "10", "21", "0",
    "0", "LINE", "67", "1", "8", "P", "10", "0", "20", "0", "11", "0", "21", "10",
    "0", "VIEWPORT", "5", "30", "330", "1F", "100", "AcDbEntity", "67", "1", "8", "VP", "100", "AcDbViewport",
    "10", "150", "20", "100", "30", "0", "40", "200", "41", "120", "68", "1", "69", "2",
    "12", "500", "22", "300", "13", "0", "23", "0",
    "16", "0", "26", "0", "36", "1", "17", "1", "27", "2", "37", "3", "45", "60000", "51", "15",
    "331", "a1", "331", "A2",
    "0", "ENDSEC",
    "0", "SECTION", "2", "OBJECTS",
    "0", "LAYOUT", "5", "40", "330", "1A", "100", "AcDbPlotSettings", "1", "Настройка",
    "40", "5", "41", "10", "42", "5", "43", "10", "44", "420", "45", "297", "72", "1", "73", "0", "142", "1", "143", "1",
    "100", "AcDbLayout", "1", "Лист1", "70", "1", "71", "2", "10", "-5", "20", "-10", "11", "415", "21", "287", "330", "1F",
    "0", "ENDSEC", "0", "EOF",
  )

  @Test
  fun `LAYOUT и VIEWPORT читаются, сущности листа не попадают в модель`() {
    val document = read(*inline)
    assertEquals(listOf("M"), document.entities.map { it.common.layer })
    val layout = document.layouts.single()
    assertEquals("Лист1", layout.name)
    assertEquals(2, layout.tabOrder)
    assertEquals(420.0, layout.paperWidth)
    assertEquals(297.0, layout.paperHeight)
    assertContentEquals(doubleArrayOf(5.0, 10.0, 5.0, 10.0), layout.margins)
    assertEquals(listOf("P"), layout.entities.map { it.common.layer })

    val vp = layout.viewports.single()
    assertEquals(150.0, vp.centerX)
    assertEquals(100.0, vp.centerY)
    assertEquals(200.0, vp.width)
    assertEquals(120.0, vp.height)
    assertEquals(2, vp.id)
    assertEquals(500.0, vp.viewCenterX)
    assertEquals(300.0, vp.viewCenterY)
    assertEquals(listOf(1.0, 2.0, 3.0), listOf(vp.targetX, vp.targetY, vp.targetZ))
    assertEquals(listOf(0.0, 0.0, 1.0), listOf(vp.directionX, vp.directionY, vp.directionZ))
    assertEquals(60000.0, vp.viewHeight)
    assertEquals(15.0, vp.twistDeg)
    assertEquals(500.0, vp.scale)
    assertEquals(listOf("A1", "A2"), vp.frozenLayers)
    assertEquals("VP", vp.common.layer)
  }

  @Test
  fun `лист раскрывается отдельно - рамка бумаги, рамка окна и подпись масштаба`() {
    val geometry = DxfFlattener(read(*inline)).flatten()
    // в модели — только линия модели
    assertEquals(listOf("M"), geometry.lines.map { it.layer })
    val sheet = geometry.sheets.single()
    assertEquals("Лист1", sheet.name)
    assertTrue(sheet.geometry.lines.any { it.layer == "P" })
    assertTrue(sheet.geometry.lines.any { it.layer == "VP" })
    assertEquals(listOf("1:500"), sheet.geometry.labels.map { it.text })
    // бумага по границам листа: −5…415 × −10…287
    val xs = sheet.geometry.lines.filter { it.layer == "0" }.flatMap { b -> b.coords.filterIndexed { i, _ -> i % 3 == 0 } }
    assertEquals(-5.0, xs.min())
    assertEquals(415.0, xs.max())
  }

  @Test
  fun `тестовый файл - два листа, тексты и окна только на листах`() {
    val document = readFile("layouts.dxf")
    val withContent = document.layouts.filter { it.hasContent }
    assertEquals(listOf("План", "Узел"), withContent.map { it.name })
    // модель в метрах, лист в мм
    assertEquals(6, document.insUnits)
    fun scales(layout: DxfLayout) = layout.viewports.filter { it.id != 1 }.map { viewportScale(it, layout, document.insUnits) }
    assertEquals(listOf(500.0, 100.0), scales(withContent[0]))
    assertEquals(listOf(20.0), scales(withContent[1]))

    val geometry = DxfFlattener(document).flatten()
    assertEquals(listOf("МОДЕЛЬ"), geometry.labels.map { it.text })
    assertEquals(listOf("План", "Узел"), geometry.sheets.map { it.name })
    // подпись модели попадает только в первое окно «Плана»
    // при открытии на листе только его сущности, модель в окна добавляется при выборе листа
    assertEquals(setOf("ЛИСТ ПЛАН", "1:500", "1:100"), geometry.sheets[0].geometry.labels.map { it.text }.toSet())
    assertEquals(listOf("ЛИСТ ПЛАН", "1:500", "1:100", "МОДЕЛЬ").sorted(), geometry.sheets[0].withModel(geometry).labels.map { it.text }.sorted())
    assertEquals(setOf("ЛИСТ УЗЕЛ", "1:20"), geometry.sheets[1].withModel(geometry).labels.map { it.text }.toSet())
    // в каждом окне видны отрезки модели (слой 0, цвета 3 и 1), и все — внутри своих окон
    for ((index, layout) in withContent.withIndex()) {
      val windows = layout.viewports.filter { it.id != 1 }
      val modelCoords = geometry.sheets[index].withModel(geometry).lines
        .filter { it.color == DxfColors.aciToRgb(1) || it.color == DxfColors.aciToRgb(3) }
        .flatMap { it.coords.toList() }
      assertTrue(modelCoords.isNotEmpty(), layout.name)
      for (p in modelCoords.indices step 3) {
        val x = modelCoords[p]
        val y = modelCoords[p + 1]
        assertTrue(windows.any { abs(x - it.centerX) <= it.width / 2 + 1e-9 && abs(y - it.centerY) <= it.height / 2 + 1e-9 }, "$x $y")
      }
    }
  }

  @Test
  fun `модель в окне листа - масштаб 1 к 500 и модель целиком`() {
    val document = readFile("layout-units.dxf")
    val layout = document.layouts.single { it.hasContent }
    val window = layout.viewports.single { it.id != 1 }
    assertEquals(500.0, viewportScale(window, layout, document.insUnits), 1e-9)
    val model = DxfFlattener(document).flatten()
    val sheet = model.sheets.single().withModel(model)
    // повторный выбор листа — без пересчёта
    assertSame(sheet, model.sheets.single().withModel(model))
    assertEquals(listOf("1:500", "МОДЕЛЬ 100×50 м").sorted(), sheet.labels.map { it.text }.sorted())
    // окно 200 × 100 с центром (210, 148): модель 100 × 50 м — ровно по окну
    val green = sheet.lines.single { it.color == DxfColors.aciToRgb(3) }.coords
    val xs = green.filterIndexed { i, _ -> i % 3 == 0 }
    val ys = green.filterIndexed { i, _ -> i % 3 == 1 }
    assertEquals(110.0, xs.min(), 1e-9)
    assertEquals(310.0, xs.max(), 1e-9)
    assertEquals(98.0, ys.min(), 1e-9)
    assertEquals(198.0, ys.max(), 1e-9)
    val text = sheet.labels.single { it.text.startsWith("МОДЕЛЬ") }
    assertEquals(150.0, text.x, 1e-9)
    assertEquals(142.0, text.y, 1e-9)
    assertEquals(10.0, text.height, 1e-9)
    // объекты модели выбираются и на листе
    assertTrue(text.objectId >= 0)
    assertEquals("TEXT", sheet.objects[text.objectId].kind)
  }

  private fun window(
    width: Double = 10.0, height: Double = 10.0, viewHeight: Double = 100.0, twist: Double = 0.0,
    frozen: Set<String> = emptySet(), directionZ: Double = 1.0, status: Int = 1, flags: Int = 0,
  ) = DxfViewport(
    0.0, 0.0, 0.0, width, height, 2, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, directionZ, viewHeight, twist, emptyList(), status, flags,
  ).also { it.frozenLayerNames = frozen }

  // окна — как их отбирает раскрыватель: только с моделью
  private fun sheetOf(model: SceneGeometry, vararg windows: DxfViewport): SceneGeometry {
    val empty = SceneGeometry(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), false)
    return sheetWithViewports(empty, model, windows.filter { it.showsModel }.map { SheetWindow(it) }, MAX_SCENE_SEGMENTS)
  }

  private fun model(
    lines: List<LineBatch> = emptyList(), fills: List<FillBatch> = emptyList(), labels: List<SceneLabel> = emptyList(),
  ) = SceneGeometry(emptyList(), lines, fills, labels, emptyList(), emptyList(), false)

  @Test
  fun `отрезки модели режутся по окну`() {
    // вид высотой 100 в окне 10: k = 0,1; отрезок −1000…1000 по X → −100…100 → обрезан до −5…5
    val line = LineBatch("A", 0xFF0000, true, doubleArrayOf(-1000.0, 0.0, 0.0, 1000.0, 0.0, 0.0))
    val outside = LineBatch("B", 0xFF0000, true, doubleArrayOf(0.0, 100.0, 0.0, 10.0, 100.0, 0.0))
    val sheet = sheetOf(model(lines = listOf(line, outside)), window())
    assertContentEquals(doubleArrayOf(-5.0, 0.0, 0.0, 5.0, 0.0, 0.0), sheet.lines.single().coords)
    // два окна — один пакет на пакет модели
    assertEquals(2, sheetOf(model(lines = listOf(line)), window(), window(width = 4.0)).lines.single().segmentCount)
  }

  @Test
  fun `заливка режется по контуру окна`() {
    // треугольник (0,0)-(100,0)-(0,100) → (0,0)-(10,0)-(0,10); в окне 0…5 остаётся квадрат 5 × 5
    val fill = FillBatch("A", 0x00FF00, true, doubleArrayOf(0.0, 0.0, 0.0, 100.0, 0.0, 0.0, 0.0, 100.0, 0.0))
    val sheet = sheetOf(model(fills = listOf(fill)), window())
    val c = sheet.fills.single().coords
    var area = 0.0
    for (t in 0 until c.size / 9) {
      val o = t * 9
      area += abs((c[o + 3] - c[o]) * (c[o + 7] - c[o + 1]) - (c[o + 6] - c[o]) * (c[o + 4] - c[o + 1])) / 2
      for (v in 0 until 3) {
        assertTrue(c[o + v * 3] in -1e-9..5.0 + 1e-9 && c[o + v * 3 + 1] in -1e-9..5.0 + 1e-9)
      }
    }
    assertEquals(25.0, area, 1e-9)
  }

  @Test
  fun `подписи по точке вставки, поворот вида и замороженные слои`() {
    val inside = SceneLabel("A", 0, 10.0, 0.0, 0.0, 5.0, 0.0, "внутри")
    val far = SceneLabel("A", 0, 500.0, 0.0, 0.0, 5.0, 0.0, "снаружи")
    val frozen = SceneLabel("F", 0, 0.0, 0.0, 0.0, 5.0, 0.0, "заморожен")
    val labels = model(labels = listOf(inside, far, frozen))
    val plain = sheetOf(labels, window(frozen = setOf("F"))).labels.single()
    assertEquals("внутри", plain.text)
    assertEquals(1.0, plain.x, 1e-9)
    assertEquals(0.5, plain.height, 1e-9)
    // поворот вида 90°: (10, 0) → (0, 1), подпись поворачивается вместе с видом
    val turned = sheetOf(labels, window(twist = 90.0, frozen = setOf("F"))).labels.single()
    assertEquals(0.0, turned.x, 1e-9)
    assertEquals(1.0, turned.y, 1e-9)
    assertEquals(90.0, turned.rotationDeg, 1e-9)
    // окно выключено (бит кода 90) или смотрит не сверху — модели в нём нет
    assertTrue(sheetOf(labels, window(flags = DxfViewport.FLAG_OFF)).labels.isEmpty())
    assertTrue(sheetOf(labels, window(directionZ = -1.0)).labels.isEmpty())
    // состояние 68 = 0 у окон неактивных листов (AutoCAD) — окно не выключено
    assertEquals(listOf("внутри"), sheetOf(labels, window(status = 0, frozen = setOf("F"))).labels.map { it.text })
  }

  @Test
  fun `окно всего листа без номера - первое, модель в нём не рисуется`() {
    // экспорт через ODA: у всех окон номер −1, состояние 68 = 1 у окна всего листа и 2 у остальных
    fun viewport(status: Int, flags: Int = 0) = ("0 VIEWPORT 5 ${30 + status} 330 1F 100 AcDbEntity 67 1 8 VP 100 AcDbViewport " +
      "10 100 20 100 30 0 40 200 41 100 68 $status 69 -1 12 100 22 100 16 0 26 0 36 1 45 100 90 $flags").split(" ").toTypedArray()
    // вместо окна из [inline] — три окна
    val head = inline.indexOf("VIEWPORT") - 1
    val tail = (head until inline.size).first { inline[it] == "ENDSEC" } - 1
    val document = read(
      *inline.take(head).toTypedArray(),
      *viewport(1), *viewport(2), *viewport(3, flags = DxfViewport.FLAG_OFF),
      *inline.drop(tail).toTypedArray(),
    )
    val layout = document.layouts.single()
    assertEquals(listOf(1, 2, 3), layout.viewports.map { it.status })
    assertSame(layout.viewports[0], layout.paperViewport)
    assertEquals(listOf(false, false, true), layout.viewports.map { it.off })
    assertEquals(listOf(true, true, false), layout.viewports.map { it.showsModel })
    // окно всего листа — вид 1:1 на сам лист: ни рамки, ни подписи, ни модели; выключенное окно — рамка без модели
    val sheet = DxfFlattener(document).flatten().sheets.single()
    assertEquals(8, sheet.geometry.lines.filter { it.layer == "VP" }.sumOf { it.segmentCount })
    assertEquals(listOf("1:1", "1:1"), sheet.geometry.labels.map { it.text })
    assertEquals(1, sheet.windows.size)
  }

  @Test
  fun `масштаб в единицах модели и листа`() {
    val vp = window(height = 100.0, viewHeight = 50.0)
    val mm = DxfLayout("A", 1)
    assertEquals(500.0, viewportScale(vp, mm, 6), 1e-9)
    // единицы не заданы — как раньше: высота вида / высота окна
    assertEquals(0.5, viewportScale(vp, mm, 0), 1e-9)
    // модель в мм, лист в дюймах: 50 мм / 100 дюймов
    assertEquals(50.0 / 2540.0, viewportScale(vp, DxfLayout("B", 1, inches = true), 4), 1e-12)
    // масштаб печати листа («вписать» 1:2,145) на масштаб окна не влияет
    assertEquals(500.0, viewportScale(vp, DxfLayout("C", 1, scalePaper = 1.0, scaleDrawing = 2.145), 6), 1e-9)
  }

  @Test
  fun `модель обрезана лимитом - лист с ней тоже`() {
    val line = LineBatch("A", 0xFF0000, true, doubleArrayOf(0.0, 0.0, 0.0, 1.0, 0.0, 0.0))
    val model = SceneGeometry(emptyList(), listOf(line), emptyList(), emptyList(), emptyList(), emptyList(), truncated = true)
    assertTrue(sheetOf(model, window()).truncated)
    assertFalse(sheetOf(model(lines = listOf(line)), window()).truncated)
  }

  @Test
  fun `листы только с окном всего листа не показываются`() {
    val document = readFile("proxy-gayka.dxf")
    assertTrue(document.layouts.isNotEmpty())
    assertTrue(document.layouts.none { it.hasContent })
    val a4 = assertNotNull(document.layouts.firstOrNull { it.name == "A4" })
    assertEquals(210.0, a4.paperWidth)
    assertEquals(297.0, a4.paperHeight)
    assertTrue(DxfFlattener(document).flatten().sheets.isEmpty())
  }

  @Test
  fun `бумага без границ листа - от полей и смещения печати`() {
    val layout = DxfLayout("A", 1, 210.0, 297.0, doubleArrayOf(5.0, 10.0, 5.0, 10.0), plotOriginX = 2.0)
    assertContentEquals(doubleArrayOf(-7.0, -10.0, 203.0, 287.0), layout.paperBounds())
    // поворот на 90° меняет стороны местами
    val turned = DxfLayout("B", 1, 210.0, 297.0, doubleArrayOf(5.0, 10.0, 5.0, 10.0), plotRotation = 1)
    assertContentEquals(doubleArrayOf(-10.0, -5.0, 287.0, 205.0), turned.paperBounds())
    // в дюймах
    val inches = DxfLayout("C", 1, 254.0, 127.0, inches = true)
    assertContentEquals(doubleArrayOf(0.0, 0.0, 10.0, 5.0), inches.paperBounds())
    assertEquals(null, DxfLayout("D", 1).paperBounds())
  }

  @Test
  fun `без номеров окон окно всего листа - первое`() {
    fun viewport(id: Int) = DxfViewport(0.0, 0.0, 0.0, 10.0, 10.0, id, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0, 100.0, 0.0, emptyList())
    val unnumbered = DxfLayout("A", 1).apply { viewports = listOf(viewport(0), viewport(0)) }
    assertSame(unnumbered.viewports[0], unnumbered.paperViewport)
    assertTrue(unnumbered.hasContent)
    val numbered = DxfLayout("B", 1).apply { viewports = listOf(viewport(2), viewport(1)) }
    assertSame(numbered.viewports[1], numbered.paperViewport)
    assertFalse(DxfLayout("C", 1).apply { viewports = listOf(viewport(0)) }.hasContent)
  }

  @Test
  fun `подпись масштаба`() {
    assertEquals("1:500", formatViewportScale(500.0))
    assertEquals("1:500", formatViewportScale(500.00001))
    assertEquals("1:1", formatViewportScale(1.0))
    assertEquals("2:1", formatViewportScale(0.5))
    assertEquals("1:2.5", formatViewportScale(2.5))
    assertEquals("", formatViewportScale(0.0))
  }

  @Test
  fun `3DFACE листа в сетки модели не попадает`() {
    val document = read(
      "0", "SECTION", "2", "ENTITIES",
      "0", "3DFACE", "67", "1", "8", "P", "10", "0", "20", "0", "30", "0", "11", "1", "21", "0", "31", "0",
      "12", "1", "22", "1", "32", "0",
      "0", "ENDSEC", "0", "EOF",
    )
    assertTrue(document.faceMeshes.isEmpty())
    // без LAYOUT (R12) лист собирается по пространству
    val layout = document.layouts.single()
    assertFalse(layout.entities.isEmpty())
    assertTrue(layout.entities.single() is DxfEntity.Face)
  }
}
