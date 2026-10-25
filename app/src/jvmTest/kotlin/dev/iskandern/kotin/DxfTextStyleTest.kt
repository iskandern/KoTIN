package dev.iskandern.kotin

import dev.iskandern.kotin.dxf.DxfDocumentReader
import dev.iskandern.kotin.dxf.TEXT_ALIGN_UNDER_DESCENT
import dev.iskandern.kotin.io.asByteInput
import dev.iskandern.kotin.dxf.FACE_DEFAULT
import dev.iskandern.kotin.scene.ATLAS_FONT_PX
import dev.iskandern.kotin.scene.ATLAS_PAD
import dev.iskandern.kotin.scene.FACE_CAP_HEIGHT
import dev.iskandern.kotin.scene.Glyph
import dev.iskandern.kotin.scene.GlyphAtlas
import dev.iskandern.kotin.scene.RenderLabel
import dev.iskandern.kotin.scene.buildRenderScene
import dev.iskandern.kotin.scene.buildTextMeshes
import dev.iskandern.kotin.scene.labelShape
import java.io.File
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DxfTextStyleTest {

  private val styles = arrayOf(
    "0", "SECTION", "2", "TABLES", "0", "TABLE", "2", "STYLE", "70", "3",
    "0", "STYLE", "2", "Standard", "70", "0", "40", "0", "41", "1", "50", "0", "3", "arial.ttf",
    "0", "STYLE", "2", "Narrow", "70", "0", "40", "0", "41", "0.5", "50", "0", "3", "arial.ttf",
    "0", "STYLE", "2", "Italic", "70", "0", "40", "0", "41", "1", "50", "20", "3", "arial.ttf",
    "0", "STYLE", "2", "FixedH", "70", "0", "40", "2.5", "41", "1", "50", "0", "3", "arial.ttf",
    "0", "ENDTAB", "0", "ENDSEC",
  )

  private fun doc(vararg entities: String) =
    arrayOf(*styles, "0", "SECTION", "2", "ENTITIES", *entities, "0", "ENDSEC", "0", "EOF")

  private fun text(value: String, vararg extra: String) =
    arrayOf("0", "TEXT", "8", "T", "10", "1", "20", "2", "30", "0", "1", value, *extra)

  @Test
  fun `таблица STYLE разбирается`() {
    val bytes = doc().joinToString("\n", postfix = "\n").byteInputStream()
    val styles = DxfDocumentReader(bytes.asByteInput()).read().textStyles
    assertEquals(setOf("STANDARD", "NARROW", "ITALIC", "FIXEDH"), styles.keys)
    assertEquals(0.5, styles.getValue("NARROW").widthFactor)
    assertEquals(20.0, styles.getValue("ITALIC").obliqueDeg)
    assertEquals(2.5, styles.getValue("FIXEDH").height)
    assertEquals(1.0, styles.getValue("STANDARD").widthFactor)
  }

  @Test
  fun `ширина, наклон и высота - из сущности или из стиля`() {
    val labels = readGeometry(
      *doc(
        *text("a", "40", "1", "7", "Narrow"),
        *text("b", "40", "1", "7", "italic"),
        // код 41 и 51 у сущности важнее стиля
        *text("c", "40", "1", "7", "Narrow", "41", "1.5", "51", "-10"),
        // без кода 40 — фиксированная высота стиля
        *text("d", "7", "FixedH"),
        // неизвестный стиль — как Standard
        *text("e", "40", "1", "7", "Nope"),
      )
    ).labels.associateBy { it.text }
    assertEquals(0.5, labels.getValue("a").widthFactor, 1e-9)
    assertEquals(20.0, labels.getValue("b").obliqueDeg, 1e-9)
    assertEquals(1.5, labels.getValue("c").widthFactor, 1e-9)
    assertEquals(-10.0, labels.getValue("c").obliqueDeg, 1e-9)
    assertEquals(2.5, labels.getValue("d").height, 1e-9)
    assertEquals(1.0, labels.getValue("e").widthFactor, 1e-9)
    assertEquals(0.0, labels.getValue("e").obliqueDeg, 1e-9)
  }

  @Test
  fun `неравномерный масштаб блока меняет ширину букв`() {
    val labels = readGeometry(
      *styles,
      "0", "SECTION", "2", "BLOCKS", "0", "BLOCK", "8", "0", "2", "B", "10", "0", "20", "0", "30", "0",
      *text("x", "40", "1", "7", "Narrow"),
      "0", "ENDBLK", "0", "ENDSEC",
      "0", "SECTION", "2", "ENTITIES",
      "0", "INSERT", "8", "0", "2", "B", "10", "0", "20", "0", "30", "0", "41", "4", "42", "2",
      "0", "ENDSEC", "0", "EOF",
    ).labels
    val label = labels.single()
    assertEquals(2.0, label.height, 1e-9)
    assertEquals(1.0, label.widthFactor, 1e-9)
  }

  @Test
  fun `ALIGNED и FIT - от первой точки ко второй`() {
    val labels = readGeometry(
      *doc(
        "0", "TEXT", "8", "T", "10", "10", "20", "0", "30", "0", "40", "1", "1", "FIT",
        "72", "5", "11", "10", "21", "30", "31", "0",
        "0", "TEXT", "8", "T", "10", "0", "20", "0", "30", "0", "40", "1", "1", "ALIGNED",
        "72", "3", "11", "20", "21", "0", "31", "0",
        // вторая точка совпадает с первой — обычный текст слева
        "0", "TEXT", "8", "T", "10", "5", "20", "5", "30", "0", "40", "1", "1", "BAD",
        "72", "5", "11", "5", "21", "5", "31", "0",
      )
    ).labels.associateBy { it.text }
    val fit = labels.getValue("FIT")
    assertEquals(5, fit.alignH)
    assertEquals(10.0, fit.x, 1e-9)
    assertEquals(0.0, fit.y, 1e-9)
    assertEquals(90.0, fit.rotationDeg, 1e-9)
    assertEquals(30.0, fit.fitLength, 1e-9)
    val aligned = labels.getValue("ALIGNED")
    assertEquals(3, aligned.alignH)
    assertEquals(20.0, aligned.fitLength, 1e-9)
    assertEquals(0.0, aligned.rotationDeg, 1e-9)
    val bad = labels.getValue("BAD")
    assertEquals(0, bad.alignH)
    assertEquals(0.0, bad.fitLength, 1e-9)
  }

  @Test
  fun `MTEXT выравнивается по горизонтали при раскладке и берёт стиль`() {
    val labels = readGeometry(
      *doc(
        "0", "MTEXT", "8", "T", "10", "7", "20", "3", "30", "0", "40", "1", "71", "6", "7", "Italic", "1", "R",
        "0", "MTEXT", "8", "T", "10", "7", "20", "3", "30", "0", "40", "1", "71", "2", "1", "C",
      )
    ).labels.associateBy { it.text }
    val right = labels.getValue("R")
    assertEquals(2, right.alignH)
    // точка привязки по горизонтали не сдвигается: сдвиг — по настоящей ширине строки
    assertEquals(7.0, right.x, 1e-9)
    assertEquals(20.0, right.obliqueDeg, 1e-9)
    assertEquals(1, labels.getValue("C").alignH)
  }

  @Test
  fun `подгонка строки - ALIGNED меняет высоту, FIT ширину`() {
    val atlas = GlyphAtlas(1, 1, ByteArray(4), mapOf('A' to Glyph(0f, 0f, 1f, 1f, 30, 20f)), 50, 40f)
    fun label(alignH: Int, fitLength: Float, widthFactor: Float = 1f) = RenderLabel(
      "T", 0, 0f, 0f, 0f, 1f, 1f, 0f, "AA", alignH = alignH, widthFactor = widthFactor, fitLength = fitLength,
    )
    // обычная ширина строки «AA»: 40 px атласа × кегль (1 / высота заглавных) / 40 px
    val natural = 40f / FACE_CAP_HEIGHT[FACE_DEFAULT] / ATLAS_FONT_PX
    val fit = labelShape(label(5, natural * 3, 0.5f), atlas)
    assertEquals(1f, fit.height, 1e-5f)
    assertEquals(3f, fit.widthFactor, 1e-4f)
    val aligned = labelShape(label(3, natural * 2), atlas)
    assertEquals(2f, aligned.height, 1e-4f)
    assertEquals(1f, aligned.widthFactor, 1e-5f)
    val plain = labelShape(label(0, 0f, 0.5f), atlas)
    assertEquals(0.5f, plain.widthFactor)
  }

  /** Габариты подписи на плане DXF относительно точки вставки (minX, maxX, minY, maxY), px атласа. */
  private fun planBox(entity: Array<String>): DoubleArray {
    val glyph = Glyph(0f, 0f, 1f, 1f, 32, 20f)
    val atlas = GlyphAtlas(1, 1, ByteArray(4), mapOf('A' to glyph, 'B' to glyph, 'C' to glyph), 50, 40f)
    val scene = buildRenderScene(readGeometry(*doc(*entity)))
    val mesh = buildTextMeshes(scene, atlas).labels.single()
    val box = doubleArrayOf(Double.MAX_VALUE, -Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE)
    for (v in 0 until mesh.quadCount * 4) {
      val cx = mesh.corners[v * 4].toDouble()
      val cy = mesh.corners[v * 4 + 1].toDouble()
      val ax = mesh.along[v * 2].toDouble()
      val az = mesh.along[v * 2 + 1].toDouble()
      // «вверх» строки на плане сцены — (az, −ax); ось z сцены — это −y DXF
      val x = cx * ax + cy * az
      val y = -(cx * az - cy * ax)
      box[0] = minOf(box[0], x); box[1] = maxOf(box[1], x)
      box[2] = minOf(box[2], y); box[3] = maxOf(box[3], y)
    }
    return box
  }

  @Test
  fun `код 71 - отражённый текст левее точки, перевёрнутый ниже`() {
    fun abc(vararg extra: String) = arrayOf("0", "TEXT", "8", "T", "10", "0", "20", "0", "30", "0", "40", "1", "1", "ABC", *extra)
    // поля SDF вокруг символа выходят за строку на ATLAS_PAD
    val pad = ATLAS_PAD + 0.5
    val plain = planBox(abc())
    assertTrue(plain[0] >= -pad && plain[1] > 50 && plain[2] > -20 && plain[3] > 20)
    val mirrored = planBox(abc("71", "2"))
    assertTrue(mirrored[1] <= pad && mirrored[0] < -50, mirrored.toList().toString())
    assertTrue(mirrored[3] > 20)
    val upsideDown = planBox(abc("71", "4"))
    assertTrue(upsideDown[3] <= 20 && upsideDown[2] < -20, upsideDown.toList().toString())
    assertTrue(upsideDown[0] >= -pad && upsideDown[1] > 50, upsideDown.toList().toString())
    val both = planBox(abc("71", "6"))
    assertTrue(both[1] <= pad && both[0] < -50 && both[2] < -20, both.toList().toString())
    // отражение по X меняет выравнивание «справа» на «слева»
    val right = planBox(abc("72", "2", "11", "0", "21", "0", "31", "0", "71", "2"))
    assertTrue(right[0] >= -pad && right[1] > 50, right.toList().toString())
  }

  @Test
  fun `код 71 - перевёрнутая строка между точками под линией, выравнивание отражается`() {
    val labels = readGeometry(
      *doc(
        "0", "TEXT", "8", "T", "10", "0", "20", "0", "30", "0", "40", "1", "1", "FIT",
        "72", "5", "11", "10", "21", "0", "31", "0", "71", "6",
        "0", "TEXT", "8", "T", "10", "0", "20", "0", "30", "0", "40", "1", "1", "BOTTOM",
        "72", "1", "73", "1", "11", "5", "21", "5", "31", "0", "71", "4",
        "0", "TEXT", "8", "T", "10", "0", "20", "0", "30", "0", "40", "1", "1", "TOPRIGHT",
        "72", "2", "73", "3", "11", "5", "21", "5", "31", "0", "71", "6",
      )
    ).labels.associateBy { it.text }
    val fit = labels.getValue("FIT")
    assertEquals(0.0, fit.x, 1e-9)
    assertEquals(0.0, fit.rotationDeg, 1e-9)
    assertEquals(5, fit.alignH)
    assertEquals(3, fit.alignV)
    assertEquals(10.0, fit.fitLength, 1e-9)
    val bottom = labels.getValue("BOTTOM")
    assertEquals(1, bottom.alignH)
    assertEquals(TEXT_ALIGN_UNDER_DESCENT, bottom.alignV)
    val topRight = labels.getValue("TOPRIGHT")
    assertEquals(0, topRight.alignH)
    assertEquals(0, topRight.alignV)
    assertEquals(5.0, topRight.x, 1e-9)
  }

  @Test
  fun `тестовый файл стилей доходит до сцены`() {
    val geometry = readGeometry(File(testDxfDir, "text-style.dxf"))
    val byText = geometry.labels.associateBy { it.text }
    assertEquals(0.5, byText.getValue("Стиль Narrow: ширина и наклон").widthFactor, 1e-9)
    assertEquals(2.0, byText.getValue("Стиль Wide: ширина и наклон").widthFactor, 1e-9)
    assertEquals(20.0, byText.getValue("Стиль Italic: ширина и наклон").obliqueDeg, 1e-9)
    val scene = buildRenderScene(geometry)
    val italic = scene.labels.single { it.text == "Стиль Italic: ширина и наклон" }
    assertEquals(tan(Math.toRadians(20.0)).toFloat(), italic.shear, 1e-6f)
    assertTrue(scene.labels.single { it.text == "FIT" }.fitLength > 29f)
  }
}
