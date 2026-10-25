package dev.iskandern.kotin

import dev.iskandern.kotin.dxf.DxfColors
import dev.iskandern.kotin.scene.MAX_FILL_STEPS
import dev.iskandern.kotin.scene.RenderScene
import dev.iskandern.kotin.scene.buildRenderScene
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DxfDrawOrderTest {

  private val red = DxfColors.aciToRgb(1)
  private val green = DxfColors.aciToRgb(3)

  // SOLID-квадрат со стороной 8 с handle [handle]
  private fun solid(handle: String, x: Double, color: Int) = arrayOf(
    "0", "SOLID", "5", handle, "8", "0", "62", "$color",
    "10", "$x", "20", "0", "11", "${x + 8}", "21", "0", "12", "$x", "22", "8", "13", "${x + 8}", "23", "8",
  )

  private val blockRecords = arrayOf(
    "0", "SECTION", "2", "TABLES",
    "0", "TABLE", "2", "BLOCK_RECORD", "5", "1", "70", "2",
    "0", "BLOCK_RECORD", "5", "1F", "100", "AcDbSymbolTableRecord", "2", "*Model_Space",
    "0", "BLOCK_RECORD", "5", "1B", "100", "AcDbSymbolTableRecord", "2", "*Paper_Space",
    "0", "ENDTAB", "0", "ENDSEC",
  )

  // красный (handle A0), затем зелёный (A1) внахлёст
  private val entities = arrayOf("0", "SECTION", "2", "ENTITIES", *solid("A0", 0.0, 1), *solid("A1", 4.0, 3), "0", "ENDSEC")

  // SORTENTSTABLE владельца [owner]: пары handle сущности — ключ
  private fun sortTable(owner: String, vararg pairs: Pair<String, String>) = arrayOf(
    "0", "SECTION", "2", "OBJECTS",
    "0", "SORTENTSTABLE", "5", "90", "102", "{ACAD_REACTORS", "330", "8F", "102", "}", "330", "8F",
    "100", "AcDbSortentsTable", "330", owner, *pairs.flatMap { listOf("331", it.first, "5", it.second) }.toTypedArray(),
    "0", "ENDSEC",
  )

  private fun scene(vararg sections: Array<String>): RenderScene =
    buildRenderScene(readGeometry(*sections.flatten().toTypedArray(), "0", "EOF"))

  // ступень порядка прорисовки заливки цвета [color]: у всех вершин одна
  private fun RenderScene.step(color: Int): Float {
    val steps = fills.single { it.color == color }.steps.toSet()
    assertEquals(1, steps.size)
    return steps.single()
  }

  @Test
  fun `без таблицы сверху заливка, что позже в файле`() {
    val scene = scene(blockRecords, entities)
    assertEquals(2, scene.fillSteps)
    assertTrue(scene.step(green) > scene.step(red))
  }

  @Test
  fun `SORTENTSTABLE модели поднимает первую заливку наверх`() {
    val scene = scene(blockRecords, entities, sortTable("1F", "A0" to "FFFF"))
    assertTrue(scene.step(red) > scene.step(green))
  }

  @Test
  fun `сущности не из таблицы сортируются по своему handle`() {
    // ключ зелёного (10) меньше handle красного (A0) — зелёный уходит вниз
    val scene = scene(blockRecords, entities, sortTable("1F", "A1" to "10"))
    assertTrue(scene.step(red) > scene.step(green))
  }

  @Test
  fun `таблица другого блока на модель не действует`() {
    val scene = scene(blockRecords, entities, sortTable("1B", "A0" to "FFFF"))
    assertTrue(scene.step(green) > scene.step(red))
  }

  @Test
  fun `без таблицы записей блоков единственная таблица сортировки — модели`() {
    val scene = scene(entities, sortTable("1F", "A0" to "FFFF"))
    assertTrue(scene.step(red) > scene.step(green))
  }

  @Test
  fun `больше MAX_FILL_STEPS заливок - ступени сжимаются с сохранением порядка`() {
    val count = MAX_FILL_STEPS * 3
    val body = (0 until count).flatMap { solid((0x100 + it).toString(16), it * 2.0, 1 + it % 2).toList() }.toTypedArray()
    val scene = scene(arrayOf("0", "SECTION", "2", "ENTITIES", *body, "0", "ENDSEC"))
    assertEquals(MAX_FILL_STEPS, scene.fillSteps)
    // треугольники идут в порядке файла: ступени не убывают и не выходят за предел
    for (fill in scene.fills) {
      val steps = fill.steps
      for (i in 1 until steps.size) assertTrue(steps[i] >= steps[i - 1])
      assertTrue(steps.all { it >= 0f && it < MAX_FILL_STEPS })
    }
    assertEquals(0f, scene.fills.minOf { it.steps.min() })
    assertEquals(MAX_FILL_STEPS - 1f, scene.fills.maxOf { it.steps.max() })
  }

  @Test
  fun `тестовый файл - таблица поднимает красный и опускает белый хэтч`() {
    val file = File("src/jvmTest/resources/dxf/draworder.dxf")
    val geometry = readGeometry(file)
    val scene = buildRenderScene(geometry)
    // по две заливки красного и зелёного: левая пара — по файлу, правая — по таблице
    fun steps(color: Int) = scene.fills.single { it.color == color }.let { fill -> fill.steps.filterIndexed { i, _ -> i % 3 == 0 } }
    val reds = steps(red)
    val greens = steps(green)
    assertEquals(4, reds.size)
    assertEquals(4, greens.size)
    // у левых квадратов (по 2 треугольника) порядок файла: зелёный выше; у правых — красный выше
    assertTrue(greens[0] > reds[0])
    assertTrue(reds[2] > greens[2])
    // белый хэтч — ниже синего хэтча и жёлтой полилинии
    val white = scene.step(DxfColors.aciToRgb(7))
    assertTrue(white < scene.step(DxfColors.aciToRgb(5)))
    assertTrue(white < scene.step(DxfColors.aciToRgb(2)))
    assertTrue(scene.step(DxfColors.aciToRgb(5)) < scene.step(DxfColors.aciToRgb(2)))
  }
}
