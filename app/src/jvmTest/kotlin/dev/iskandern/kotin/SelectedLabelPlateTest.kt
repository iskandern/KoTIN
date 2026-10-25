package dev.iskandern.kotin

import dev.iskandern.kotin.scene.ATLAS_PAD
import dev.iskandern.kotin.scene.Glyph
import dev.iskandern.kotin.scene.GlyphAtlas
import dev.iskandern.kotin.scene.MARKER_FILL
import dev.iskandern.kotin.scene.RenderLabel
import dev.iskandern.kotin.scene.TextLayerMesh
import dev.iskandern.kotin.scene.buildSelectedLabelsMesh
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Плашка выбранной подписи — ровно вокруг букв: у подписей рядом со значком (сдвиг [RenderLabel.shiftDp]) она стояла
 * без сдвига и съезжала с текста на ширину сдвига (15.png: «ГГС-12» слева от значка, «101,20» справа).
 */
class SelectedLabelPlateTest {
  // символ шириной 20 px атласа, ячейка с полями ATLAS_PAD
  private val glyph = Glyph(0f, 0f, 1f, 1f, 20 + 2 * ATLAS_PAD, 20f)
  private val atlas = GlyphAtlas(
    1, 1, ByteArray(4), "АБВ".associateWith { glyph } + mapOf(MARKER_FILL to Glyph(0f, 0f, 1f, 1f, 56, 0f)), 50, 40f,
  )

  private fun mesh(alignH: Int, shiftDp: Float): TextLayerMesh {
    val label = RenderLabel("L", 0, 0f, 0f, 0f, -9f, 1f, 0f, "АБВ", alignH = alignH, alignV = 2, screenHorizontal = true, shiftDp = shiftDp)
    return buildSelectedLabelsMesh(listOf(label), 0x15202B, 0xF5C400, atlas)!!
  }

  // квады: кант, плашка, затем буквы; угол v квада — corners[(quad * 4 + v) * 4] (x) и + 1 (y)
  private fun range(mesh: TextLayerMesh, quads: IntRange, axis: Int) =
    quads.flatMap { q -> (0 until 4).map { mesh.corners[(q * 4 + it) * 4 + axis] } }.let { it.min()..it.max() }

  private fun plateAndText(alignH: Int, shiftDp: Float): Pair<ClosedFloatingPointRange<Float>, ClosedFloatingPointRange<Float>> {
    val mesh = mesh(alignH, shiftDp)
    val plate = range(mesh, 1..1, 0)
    // видимые буквы — без полей ячейки
    val letters = range(mesh, 2 until mesh.quadCount, 0).let { (it.start + ATLAS_PAD)..(it.endInclusive - ATLAS_PAD) }
    return plate to letters
  }

  @Test
  fun `кант — под плашкой, шире её на одно и то же поле со всех сторон`() {
    val mesh = mesh(0, 0f)
    assertEquals(0x15.toByte(), mesh.colors[0], "кант цвета текста")
    assertEquals(0xF5.toByte(), mesh.colors[16], "плашка жёлтая")
    val edgeX = range(mesh, 0..0, 0)
    val plateX = range(mesh, 1..1, 0)
    val edgeY = range(mesh, 0..0, 1)
    val plateY = range(mesh, 1..1, 1)
    val left = plateX.start - edgeX.start
    assertEquals(true, left > 0f)
    assertEquals(left, edgeX.endInclusive - plateX.endInclusive, 0.01f)
    assertEquals(left, plateY.start - edgeY.start, 0.01f)
    assertEquals(left, edgeY.endInclusive - plateY.endInclusive, 0.01f)
  }

  @Test
  fun `плашка обнимает подпись со сдвигом от значка — слева и справа`() {
    for ((alignH, shift) in listOf(0 to 8f, 2 to -8f, 0 to 0f)) {
      val (plate, letters) = plateAndText(alignH, shift)
      val left = letters.start - plate.start
      val right = plate.endInclusive - letters.endInclusive
      assertEquals(left, right, 0.01f, "поля слева и справа при alignH=$alignH, сдвиг $shift")
      assertEquals(true, left > 0f)
    }
  }

  @Test
  fun `строки одного абзаца - одна плашка - канты под заливками, ширина по длинной строке`() {
    // мировые строки друг под другом: «АБВ» и «А»
    val first = RenderLabel("L", 0, 0f, 0f, 0f, 1f, 1f, 0f, "АБВ", objectId = 3)
    val second = RenderLabel("L", 0, 0f, 0f, 1.6f, 1f, 1f, 0f, "А", objectId = 3)
    val mesh = buildSelectedLabelsMesh(listOf(first, second), 0x15202B, 0xF5C400, atlas)!!
    // квады: два канта, две заливки, затем буквы
    assertEquals(0x15.toByte(), mesh.colors[1 * 16], "второй квад — кант")
    assertEquals(0xF5.toByte(), mesh.colors[2 * 16], "третий квад — плашка")
    assertEquals(range(mesh, 2..2, 0), range(mesh, 3..3, 0), "плашки строк одной ширины")
    assertEquals(range(mesh, 0..0, 0), range(mesh, 1..1, 0), "канты строк одной ширины")
  }
}
