package dev.iskandern.kotin

import dev.iskandern.kotin.dxf.DxfColors
import dev.iskandern.kotin.dxf.TEXT_BACKGROUND_WINDOW
import dev.iskandern.kotin.dxf.TEXT_COLOR_ENTITY
import dev.iskandern.kotin.dxf.TEXT_STRIKE
import dev.iskandern.kotin.dxf.TEXT_UNDERLINE
import dev.iskandern.kotin.dxf.parseMText
import dev.iskandern.kotin.dxf.stripMTextFormatting
import dev.iskandern.kotin.scene.Glyph
import dev.iskandern.kotin.scene.GlyphAtlas
import dev.iskandern.kotin.scene.MARKER_FILL
import dev.iskandern.kotin.scene.buildRenderScene
import dev.iskandern.kotin.scene.buildTextMeshes
import dev.iskandern.kotin.scene.layoutMTextLabels
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DxfMTextFormatTest {

  private fun mtext(value: String, vararg extra: String) = arrayOf(
    "0", "SECTION", "2", "ENTITIES",
    "0", "MTEXT", "8", "T", "62", "3", "10", "0", "20", "0", "30", "0", "40", "1", "71", "1", *extra, "1", value,
    "0", "ENDSEC", "0", "EOF",
  )

  // фрагменты: текст каждого и его вид
  private fun pieces(raw: String, height: Double = 1.0): List<String> {
    val content = parseMText(raw, height)
    val runs = content.runs ?: return listOf(content.text)
    return runs.indices.map { content.text.substring(if (it == 0) 0 else runs[it - 1].end, runs[it].end) }
  }

  // все символы шириной 20 px атласа: при высоте букв 1 это 20 / 40 / 0,711 ≈ 0,70 ед.
  private val glyph = Glyph(0f, 0f, 1f, 1f, 30, 20f)
  private val atlas = GlyphAtlas(
    1, 1, ByteArray(4),
    "abcdefghijklmnopqrstuvwxyzабвгдежзиклмнопрстуфхцчшщыьэюя".associateWith { glyph } +
      mapOf(' ' to Glyph(0f, 0f, 0f, 0f, 0, 20f), MARKER_FILL to Glyph(0f, 0f, 1f, 1f, 56, 0f)),
    50, 40f,
  )

  @Test
  fun `цвет C делит строку на фрагменты`() {
    val content = parseMText("{\\C1;красный} обычный", 1.0)
    assertEquals(listOf("красный", " обычный"), pieces("{\\C1;красный} обычный"))
    val runs = assertNotNull(content.runs)
    assertEquals(DxfColors.aciToRgb(1), runs[0].color)
    assertEquals(TEXT_COLOR_ENTITY, runs[1].color)
  }

  @Test
  fun `true color c - в порядке BGR, BYLAYER - цвет сущности`() {
    val runs = assertNotNull(parseMText("\\c255;a\\c16711680;b\\C256;c", 1.0).runs)
    assertEquals(listOf(0xFF0000, 0x0000FF, TEXT_COLOR_ENTITY), runs.map { it.color })
  }

  @Test
  fun `высота H - абсолютная и относительная, группа восстанавливает`() {
    assertEquals(listOf("a", "b", "c", "d"), pieces("a{\\H2x;b\\H0.5x;c}\\H3;d", 1.5))
    val runs = assertNotNull(parseMText("a{\\H2x;b\\H0.5x;c}\\H3;d", 1.5).runs)
    assertEquals(listOf(1.0, 2.0, 1.0, 2.0), runs.map { it.scale })
  }

  @Test
  fun `подчёркивание и зачёркивание включаются и выключаются`() {
    val runs = assertNotNull(parseMText("\\Lпод\\l \\Kзач\\k", 1.0).runs)
    assertEquals(listOf(TEXT_UNDERLINE, 0, TEXT_STRIKE), runs.map { it.decoration })
  }

  @Test
  fun `шрифт пропускается, дробь - строкой, без оформления фрагментов нет`() {
    val content = parseMText("{\\fArial|b0;Первая}\\P\\S1^2; %%c5", 1.0)
    assertEquals("Первая\n1/2 ⌀5", content.text)
    assertNull(content.runs)
    // снятие форматирования спецкоды %% не трогает
    assertEquals("a %%c5", stripMTextFormatting("{\\C1;a} %%c5"))
  }

  @Test
  fun `MTEXT - фрагменты в подписи, H2x удваивает высоту строки`() {
    val red = readGeometry(*mtext("{\\C1;красный} обычный")).labels.single()
    assertEquals("красный обычный", red.text)
    val runs = assertNotNull(red.runs)
    assertEquals(listOf(7, 15), runs.map { it.end })
    assertEquals(listOf(0xFF0000, TEXT_COLOR_ENTITY), runs.map { it.color })

    val big = readGeometry(*mtext("a{\\H2x;b}")).labels.single()
    assertEquals(2.0, big.height, 1e-9)
    assertEquals(listOf(0.5, 1.0), assertNotNull(big.runs).map { it.scale })

    assertNull(readGeometry(*mtext("просто")).labels.single().runs)
  }

  @Test
  fun `строка с H выше - и шаг до неё больше`() {
    val labels = readGeometry(*mtext("a\\P\\H2x;b")).labels
    assertEquals(listOf(1.0, 2.0), labels.map { it.height })
    // первая строка: база на −1; вторая — ниже на 5/3 своей высоты
    assertEquals(-1.0, labels[0].y, 1e-9)
    assertEquals(-1.0 - 2.0 * 5.0 / 3.0, labels[1].y, 1e-9)
  }

  @Test
  fun `в рамке - одна подпись, при переносе строки фрагменты сохраняются`() {
    // рамка 5: «aaa {\C1;bbb} ccc» → «aaa bbb» и «ccc»
    val geometry = readGeometry(*mtext("aaa {\\C1;bbb} \\H2x;ccc", "41", "5"))
    val label = geometry.labels.single()
    assertNotNull(label.frame)
    assertNotNull(label.runs)
    val lines = layoutMTextLabels(buildRenderScene(geometry).labels, atlas)
    assertEquals(listOf("aaa bbb", "ccc"), lines.map { it.text })
    assertEquals(listOf(4, 7), assertNotNull(lines[0].runs).map { it.end })
    assertEquals(listOf(TEXT_COLOR_ENTITY, 0xFF0000), lines[0].runs!!.map { it.color })
    // вторая строка вдвое выше: без фрагментов, высота подписи 2, шаг — 5/3 её высоты
    assertNull(lines[1].runs)
    assertEquals(listOf(1f, 2f), lines.map { it.height })
    assertEquals(2f * 5f / 3f, lines[1].z - lines[0].z, 1e-4f)
  }

  @Test
  fun `фон MTEXT - флаги 90, цвет 63 и 421, поле 45`() {
    val own = readGeometry(*mtext("a", "90", "1", "63", "1", "45", "1.25", "41", "10")).labels.single().background
    assertNotNull(own)
    assertEquals(0xFF0000, own.color)
    assertEquals(0.25, own.margin, 1e-9)
    assertEquals(10.0, own.width, 1e-9)
    val trueColor = readGeometry(*mtext("a", "90", "1", "63", "1", "421", "65280")).labels.single().background
    assertEquals(0x00FF00, trueColor?.color)
    val window = readGeometry(*mtext("a", "90", "3", "63", "1")).labels.single().background
    assertEquals(TEXT_BACKGROUND_WINDOW, window?.color)
    assertNull(readGeometry(*mtext("a")).labels.single().background)
  }

  @Test
  fun `сетка подписи - плашки под буквами, буквы своего цвета, линия`() {
    val scene = buildRenderScene(readGeometry(*mtext("{\\C1;\\La}b\\Pab", "90", "1", "63", "5", "41", "10")))
    val mesh = buildTextMeshes(scene, atlas).labels.single()
    // две плашки, затем «a», подчёркивание, «b» и вторая строка
    assertEquals(7, mesh.quadCount)
    fun quadColor(q: Int) = mesh.colors.let { c ->
      ((c[q * 16].toInt() and 0xFF) shl 16) or ((c[q * 16 + 1].toInt() and 0xFF) shl 8) or (c[q * 16 + 2].toInt() and 0xFF)
    }
    // зелёный (ACI 3) на светлом фоне затемнён до читаемого — DxfColors.onBackground
    val green = DxfColors.onBackground(0x00FF00)
    assertEquals(listOf(0x0000FF, 0x0000FF, 0xFF0000, 0xFF0000, green, green, green), (0 until 7).map { quadColor(it) })
  }
}
