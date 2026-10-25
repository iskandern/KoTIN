package dev.iskandern.kotin

import dev.iskandern.kotin.dxf.FACE_DEFAULT
import dev.iskandern.kotin.scene.ATLAS_FONT_PX
import dev.iskandern.kotin.scene.FACE_CAP_HEIGHT
import dev.iskandern.kotin.scene.Glyph
import dev.iskandern.kotin.scene.GlyphAtlas
import dev.iskandern.kotin.scene.RenderLabel
import dev.iskandern.kotin.scene.buildRenderScene
import dev.iskandern.kotin.scene.layoutMTextLabels
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DxfMTextWrapTest {

  // все символы шириной 20 px атласа: при высоте букв 1 это 20 / 40 / 0,711 ≈ 0,70 ед.
  private val advance = 20f
  private val atlas = GlyphAtlas(
    1, 1, ByteArray(4),
    ("абвгдежзиклмнопрстуфхцчшщыьэюяABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789" + ' ').associateWith {
      Glyph(0f, 0f, 1f, 1f, if (it == ' ') 0 else 30, advance)
    },
    50, 40f,
  )
  private val charWidth = advance / FACE_CAP_HEIGHT[FACE_DEFAULT] / ATLAS_FONT_PX

  private fun doc(vararg entities: String) =
    arrayOf("0", "SECTION", "2", "ENTITIES", *entities, "0", "ENDSEC", "0", "EOF")

  private fun mtext(value: String, vararg extra: String) =
    arrayOf("0", "MTEXT", "8", "T", "10", "0", "20", "0", "30", "0", "40", "1", *extra, "1", value)

  private fun lines(vararg pairs: String): List<RenderLabel> = layoutMTextLabels(buildRenderScene(readGeometry(*pairs)).labels, atlas)

  private fun RenderLabel.width() = text.length * charWidth

  @Test
  fun `абзац переносится по ширине рамки`() {
    // 30 символов с пробелами, рамка 10 при высоте 1
    val text = "AAAA BBBB CCCC DDDD EEEE FFFFF"
    assertEquals(30, text.length)
    val labels = lines(*doc(*mtext(text, "41", "10")))
    assertTrue(labels.size >= 3, "строк ${labels.size}")
    labels.forEach { assertTrue(it.width() <= 10f + 1e-4f, "«${it.text}» шире рамки: ${it.width()}") }
    assertEquals(text, labels.joinToString(" ") { it.text })
    // строки идут вниз с шагом 5/3 высоты, привязка слева сверху: начало строки — на точке вставки
    assertEquals(0f, labels[0].x, 1e-4f)
    assertEquals(5f / 3f, labels[1].z - labels[0].z, 1e-4f)
  }

  @Test
  fun `без ширины рамки строка не переносится`() {
    val geometry = readGeometry(*doc(*mtext("AAAA BBBB CCCC DDDD EEEE FFFFF")))
    assertNull(geometry.labels.single().frame)
    assertEquals(1, layoutMTextLabels(buildRenderScene(geometry).labels, atlas).size)
  }

  @Test
  fun `неразрывный пробел не переносится, абзацы - с новой строки`() {
    // «AAA BBB» по 3 символа: «AAA\~BBB» шириной 7 символов при рамке на 5 остаётся одной строкой
    val labels = lines(*doc(*mtext("AAA\\~BBB CC\\PDD", "41", "${5 * charWidth}")))
    assertEquals(listOf("AAA BBB", "CC", "DD"), labels.map { it.text })
  }

  @Test
  fun `привязка по центру и справа - от рамки`() {
    val labels = lines(*doc(*mtext("AAAA BBBB CCCC", "41", "6", "71", "2"), *mtext("AAAA BBBB CCCC", "41", "6", "71", "9")))
    assertTrue(labels.filter { it.alignH == 1 }.size >= 2)
    // строки центрируются на точке вставки (середина рамки), низ последней строки — на точке вставки снизу
    val bottom = labels.filter { it.alignH == 2 }
    assertEquals(0f, bottom.last().z, 1e-4f)
  }

  @Test
  fun `колонки - текст продолжается в следующей`() {
    // колонки шириной 4 символа, высота 3 строки (1 + 2 × 5/3), промежуток 1
    val w = 4 * charWidth
    val labels = lines(
      *doc(
        "0", "MTEXT", "8", "T", "10", "0", "20", "0", "30", "0", "40", "1", "41", "$w", "46", "${1 + 2 * 5.0 / 3}",
        "71", "1", "1", "AAA BBB CCC DDD EEE FFF GGG",
        "101", "Embedded Object", "70", "1", "10", "1", "20", "0", "30", "0", "11", "0", "21", "0", "31", "0",
        "40", "$w", "41", "${1 + 2 * 5.0 / 3}", "42", "${3 * w + 2}", "43", "4", "71", "2", "72", "0",
        "44", "$w", "45", "1", "73", "1", "74", "0",
      )
    )
    assertEquals(7, labels.size)
    // первые три строки — в первой колонке, следующие три — во второй со сдвигом на ширину и промежуток
    assertEquals(0f, labels[2].x, 1e-4f)
    assertEquals(w + 1f, labels[3].x, 1e-4f)
    assertEquals(labels[0].z, labels[3].z, 1e-4f)
    assertEquals(2 * (w + 1f), labels[6].x, 1e-4f)
  }

  @Test
  fun `колонки из XDATA и разрыв колонки`() {
    val labels = lines(
      *doc(
        "0", "MTEXT", "8", "T", "10", "0", "20", "0", "30", "0", "40", "1", "41", "8", "46", "20", "71", "1",
        "1", "AAA\\NBBB",
        "1001", "ACAD", "1000", "ACAD_MTEXT_COLUMN_INFO_BEGIN",
        "1070", "75", "1070", "1", "1070", "79", "1070", "0", "1070", "76", "1070", "2", "1070", "78", "1070", "0",
        "1070", "48", "1040", "8", "1070", "49", "1040", "2",
        "1000", "ACAD_MTEXT_COLUMN_INFO_END",
      )
    )
    assertEquals(listOf("AAA", "BBB"), labels.map { it.text })
    assertEquals(10f, labels[1].x - labels[0].x, 1e-4f)
    assertEquals(labels[0].z, labels[1].z, 1e-4f)
  }

  @Test
  fun `рамка масштабируется вставкой блока`() {
    val geometry = readGeometry(
      "0", "SECTION", "2", "BLOCKS", "0", "BLOCK", "8", "0", "2", "B", "10", "0", "20", "0", "30", "0",
      *mtext("AAAA BBBB", "41", "10"),
      "0", "ENDBLK", "0", "ENDSEC",
      "0", "SECTION", "2", "ENTITIES",
      "0", "INSERT", "8", "0", "2", "B", "10", "0", "20", "0", "30", "0", "41", "2", "42", "2",
      "0", "ENDSEC", "0", "EOF",
    )
    val frame = assertNotNull(geometry.labels.single().frame)
    assertEquals(20.0, frame.width, 1e-9)
    assertEquals(2 * 5.0 / 3, frame.pitch, 1e-9)
  }

  @Test
  fun `тестовый файл рамок`() {
    val scene = buildRenderScene(readGeometry(File(testDxfDir, "mtext-wrap.dxf")))
    val framed = scene.labels.filter { it.frame != null }
    assertTrue(framed.size >= 7, "MTEXT с рамкой: ${framed.size}")
    assertTrue(framed.count { it.frame!!.columnWidth > 0 } >= 3)
    // точка вставки колонок не затёрта кодами Embedded Object
    assertTrue(framed.any { it.frame!!.columnWidth > 0 && it.x == scene.labels.minOf { l -> l.x } })
    assertTrue(layoutMTextLabels(scene.labels, atlas).size > scene.labels.size * 2)
  }
}
