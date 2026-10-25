package dev.iskandern.kotin

import dev.iskandern.kotin.scene.FACE_SYMBOLS
import dev.iskandern.kotin.dxf.ARIAL_NARROW_WIDTH
import dev.iskandern.kotin.dxf.DxfDocumentReader
import dev.iskandern.kotin.dxf.FACE_ARIMO
import dev.iskandern.kotin.dxf.FACE_CARLITO
import dev.iskandern.kotin.dxf.FACE_DEFAULT
import dev.iskandern.kotin.dxf.FACE_NARROW
import dev.iskandern.kotin.dxf.FACE_TINOS
import dev.iskandern.kotin.dxf.NARROW_FACE_WIDTH
import dev.iskandern.kotin.io.asByteInput
import dev.iskandern.kotin.dxf.dxfFont
import dev.iskandern.kotin.scene.ATLAS_FONT_PX
import dev.iskandern.kotin.scene.FACE_CAP_HEIGHT
import dev.iskandern.kotin.scene.FACE_DESCENT
import dev.iskandern.kotin.scene.Glyph
import dev.iskandern.kotin.scene.GlyphAtlas
import dev.iskandern.kotin.scene.GlyphFace
import dev.iskandern.kotin.scene.RenderLabel
import dev.iskandern.kotin.scene.buildRenderScene
import dev.iskandern.kotin.scene.fontCharacters
import dev.iskandern.kotin.scene.labelShape
import dev.iskandern.kotin.scene.labelWidth
import java.awt.Font
import java.awt.font.FontRenderContext
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DxfFontTest {

  private val fontDir = File("src/commonMain/composeResources/font")
  private val frc = FontRenderContext(null, false, true)

  private fun loadFont(name: String, size: Float): Font =
    Font.createFont(Font.TRUETYPE_FONT, File(fontDir, "$name.ttf")).deriveFont(size)

  private val styles = arrayOf(
    "0", "SECTION", "2", "TABLES", "0", "TABLE", "2", "STYLE", "70", "5",
    "0", "STYLE", "2", "Standard", "70", "0", "40", "0", "41", "1", "50", "0", "3", "txt",
    "0", "STYLE", "2", "A", "70", "0", "40", "0", "41", "1", "50", "0", "3", "arial.ttf",
    "0", "STYLE", "2", "AN", "70", "0", "40", "0", "41", "1", "50", "0", "3", "arialn.ttf",
    // TTF без файла: гарнитура — в XDATA ACAD
    "0", "STYLE", "2", "TNR", "70", "0", "40", "0", "41", "1", "50", "0", "3", "",
    "1001", "ACAD", "1000", "Times New Roman", "1071", "0",
    "0", "STYLE", "2", "X", "70", "0", "40", "0", "41", "0.8", "50", "0", "3", "mystery.ttf",
    "0", "ENDTAB", "0", "ENDSEC",
  )

  @Test
  fun `шрифт стиля - файл или гарнитура TTF`() {
    val bytes = (styles + arrayOf("0", "EOF")).joinToString("\n", postfix = "\n").byteInputStream()
    val read = DxfDocumentReader(bytes.asByteInput()).read().textStyles
    assertEquals(FACE_NARROW, read.getValue("STANDARD").font.face)
    assertEquals(FACE_ARIMO, read.getValue("A").font.face)
    assertEquals(1.0, read.getValue("A").font.widthScale)
    assertEquals(FACE_ARIMO, read.getValue("AN").font.face)
    assertEquals(ARIAL_NARROW_WIDTH, read.getValue("AN").font.widthScale)
    assertEquals(FACE_TINOS, read.getValue("TNR").font.face)
    assertEquals(FACE_DEFAULT, read.getValue("X").font.face)
    assertEquals(1.0, read.getValue("X").font.widthScale)
  }

  @Test
  fun `имена шрифтов сопоставляются без пути, расширения и регистра`() {
    assertEquals(FACE_CARLITO, dxfFont("C:\\Windows\\Fonts\\CALIBRI.TTF", "").face)
    assertEquals(ARIAL_NARROW_WIDTH, dxfFont("", "Arial Narrow").widthScale)
    // гарнитура XDATA важнее файла
    assertEquals(FACE_TINOS, dxfFont("arial.ttf", "Times New Roman").face)
    assertEquals(FACE_NARROW, dxfFont("GOST 2304A.ttf", "").face)
    assertEquals(FACE_NARROW, dxfFont("russia-gost_topo.shx", "").face)
    // SHX шире узкого PT Sans Narrow: строка растягивается
    assertEquals(0.95 / NARROW_FACE_WIDTH, dxfFont("romans.shx", "").widthScale, 1e-9)
    assertEquals(FACE_DEFAULT, dxfFont("", "").face)
  }

  @Test
  fun `ГОСТ и неизвестные SHX дают узкую гарнитуру`() {
    for (name in listOf("GOST.shx", "CS_Gost2304.shx", "gostcommon.shx", "foo.shx")) {
      assertEquals(FACE_NARROW, dxfFont(name, "").face, name)
    }
    assertEquals(0.8 / NARROW_FACE_WIDTH, dxfFont("GOST.shx", "").widthScale, 1e-9)
    assertEquals(0.9 / NARROW_FACE_WIDTH, dxfFont("foo.shx", "").widthScale, 1e-9)
    // неизвестный TTF — как раньше
    assertEquals(FACE_DEFAULT, dxfFont("foo.ttf", "").face)
  }

  @Test
  fun `метрики гарнитур совпадают с файлами шрифтов`() {
    val ref = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyzАБВГДЕЖЗИКЛМНОПРСТУФХЦЧШЩЭЮЯабвгдежзиклмнопрстуфхцчшщэюя"
    // все гарнитуры подписей, в том числе Golos Text (FACE_DEFAULT) и JetBrains Mono
    // знаки (FACE_SYMBOLS) — не гарнитура подписей, своих метрик у них нет
    for ((face, name) in LABEL_FONTS.filter { it.face != FACE_SYMBOLS }.map { it.face to it.file }) {
      val font = loadFont(name, 1000f)
      val cap = -font.createGlyphVector(frc, "H").visualBounds.y / 1000
      val p = font.createGlyphVector(frc, "p").visualBounds
      assertEquals(cap, FACE_CAP_HEIGHT[face].toDouble(), 2e-3, name)
      assertEquals((p.y + p.height) / 1000, FACE_DESCENT[face].toDouble(), 2e-3, name)
      if (face == FACE_NARROW) {
        val average = ref.sumOf { font.createGlyphVector(frc, it.toString()).logicalBounds.width } / ref.length / 1000
        assertEquals(average / cap, NARROW_FACE_WIDTH, 5e-3)
      }
    }
  }

  @Test
  fun `символы шрифта по cmap совпадают с файлом`() {
    for (name in listOf("arimo", "tinos", "carlito", "pt_sans_narrow")) {
      val chars = fontCharacters(File(fontDir, "$name.ttf").readBytes())
      val font = loadFont(name, 12f)
      for (ch in "AzПя0+№°±⌀∅") assertEquals(font.canDisplay(ch), ch in chars, "$name: $ch")
      assertTrue('Ж' in chars && '⌀' !in chars, name)
    }
    assertTrue(fontCharacters(ByteArray(10)).isEmpty())
  }

  @Test
  fun `спецсимволы DXF есть в цепочке запасных шрифтов любой гарнитуры`() {
    // %%c, %%d, %%p, ∅, знаки допусков (DxfTolerance) и греческие: Golos и замены AutoCAD их не содержат (на web
    // шрифта платформы нет — был бы квадрат), их рисует kotin_symbols
    val symbols = fontCharacters(File(fontDir, "kotin_symbols.ttf").readBytes())
    val golos = fontCharacters(File(fontDir, "golos_text_regular.ttf").readBytes())
    val needed = "⌀°±∅×≈²³⊥∠◎○▱◚◠☰➚ØΩΔαβγμπ"
    for (font in LABEL_FONTS.filter { it.face != FACE_SYMBOLS }) {
      val own = fontCharacters(File(fontDir, "${font.file}.ttf").readBytes())
      // тот же порядок, что в buildGlyphAtlas: гарнитура, Golos, знаки
      val missing = needed.filter { it !in own && it !in golos && it !in symbols }
      assertTrue(missing.isEmpty(), "${font.file}: нет $missing")
    }
    assertTrue('⌀' in symbols && '⌀' !in golos)
  }

  /** Атлас с шагами пера Arimo из файла шрифта при кегле атласа. */
  private fun arimoAtlas(text: String): GlyphAtlas {
    val font = loadFont("arimo", ATLAS_FONT_PX.toFloat())
    val glyphs = (text + " ").associateWith { ch ->
      Glyph(0f, 0f, 1f, 1f, 30, font.createGlyphVector(frc, ch.toString()).logicalBounds.width.toFloat())
    }
    val face = GlyphFace(glyphs, 50, 40f, FACE_CAP_HEIGHT[FACE_ARIMO], FACE_DESCENT[FACE_ARIMO])
    return GlyphAtlas(1, 1, ByteArray(4), emptyMap(), 50, 40f, mapOf(FACE_ARIMO to face))
  }

  /** Мировая длина строки подписи. */
  private fun worldWidth(label: RenderLabel, atlas: GlyphAtlas): Float {
    val face = atlas.face(label.face)
    return labelWidth(label, face) * labelShape(label, atlas).widthFactor * label.height / face.capHeight / ATLAS_FONT_PX
  }

  @Test
  fun `ПК0+00 в arialn уже, чем в arial, в отношении гарнитур`() {
    val text = "ПК0+00"
    val entities = arrayOf(
      "0", "SECTION", "2", "ENTITIES",
      "0", "TEXT", "8", "T", "10", "0", "20", "0", "30", "0", "40", "2", "1", text, "7", "A",
      "0", "TEXT", "8", "T", "10", "0", "20", "10", "30", "0", "40", "2", "1", text, "7", "AN",
      "0", "ENDSEC", "0", "EOF",
    )
    val scene = buildRenderScene(readGeometry(*styles, *entities))
    val atlas = arimoAtlas(text)
    val arial = worldWidth(scene.labels.single { it.z == scene.labels.maxOf { l -> l.z } }, atlas)
    val narrow = worldWidth(scene.labels.single { it.z == scene.labels.minOf { l -> l.z } }, atlas)
    assertEquals(0.82f, narrow / arial, 1e-3f)
    // Arimo — метрики Arial: «ПК0+00» высотой 2 по ширине знаков Arial
    val font = loadFont("arimo", 1000f)
    val expected = font.createGlyphVector(frc, text).logicalBounds.width / 1000 * 2 / FACE_CAP_HEIGHT[FACE_ARIMO]
    assertEquals(expected.toFloat(), arial, 1e-3f)
  }
}
