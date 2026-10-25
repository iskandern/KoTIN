package dev.iskandern.kotin

import dev.iskandern.kotin.scene.GlyphAtlas
import dev.iskandern.kotin.scene.RenderLabel
import dev.iskandern.kotin.scene.RenderScene
import dev.iskandern.kotin.scene.buildTextMeshes
import dev.iskandern.kotin.scene.cellGlyph
import kotlin.test.Test
import kotlin.test.assertEquals

class GlyphUvTest {

  /**
   * Весь путь V: ячейка атласа → UV квада в TextMesh → `flipUV` материала (v ↦ 1 − v) → строка текстуры. Первая строка
   * данных — v = 0, поэтому верх символа должен брать верхнюю строку своей ячейки, низ — нижнюю. Ячейка на второй полке:
   * при лишнем перевороте символ берётся из зеркальной по вертикали ячейки атласа.
   */
  @Test
  fun `квад символа берёт строки своей ячейки атласа сверху вниз`() {
    val atlasWidth = 128
    val atlasHeight = 64
    val (x, y, w, h) = listOf(16, 40, 20, 24)
    val glyph = cellGlyph(x, y, w, h, atlasWidth, atlasHeight, advance = 20f)
    val atlas = GlyphAtlas(atlasWidth, atlasHeight, ByteArray(atlasWidth * atlasHeight * 4), mapOf('A' to glyph), 50, 40f)
    val scene = RenderScene(
      surfaces = emptyList(), lines = emptyList(), fills = emptyList(),
      labels = listOf(RenderLabel("T", 0, 0f, 0f, 0f, 1f, 1f, 0f, "A")),
      points = emptyList(), layerInfos = emptyList(), truncated = false, drawingFlattened = false,
      originX = 0.0, originY = 0.0, originZ = 0.0, halfX = 1f, halfY = 1f, halfZ = 1f,
    )
    val mesh = buildTextMeshes(scene, atlas).labels.single()
    assertEquals(1, mesh.quadCount)
    // углы квада: 0 — верх-лево, 1 — верх-право, 2 — низ-право, 3 — низ-лево; в шейдере v ↦ 1 − v
    fun sampledRow(corner: Int) = (1f - mesh.uvs[corner * 2 + 1]) * atlasHeight
    fun sampledColumn(corner: Int) = mesh.uvs[corner * 2] * atlasWidth
    assertEquals(y.toFloat(), sampledRow(0), 1e-3f)
    assertEquals(y.toFloat(), sampledRow(1), 1e-3f)
    assertEquals((y + h).toFloat(), sampledRow(2), 1e-3f)
    assertEquals((y + h).toFloat(), sampledRow(3), 1e-3f)
    assertEquals(x.toFloat(), sampledColumn(0), 1e-3f)
    assertEquals((x + w).toFloat(), sampledColumn(1), 1e-3f)
  }
}
