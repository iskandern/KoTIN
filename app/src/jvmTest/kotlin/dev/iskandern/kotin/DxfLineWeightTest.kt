package dev.iskandern.kotin

import dev.iskandern.kotin.scene.buildRibbon
import dev.iskandern.kotin.scene.lineWidthPx
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DxfLineWeightTest {

  private fun line(layer: String, weight: Int?, y: Int) = arrayOf("0", "LINE", "8", layer) +
    (if (weight != null) arrayOf("370", "$weight") else emptyArray()) +
    arrayOf("10", "0", "20", "$y", "30", "0", "11", "10", "21", "$y", "31", "0")

  private fun doc(vararg entities: Array<String>) = arrayOf(
    "0", "SECTION", "2", "TABLES", "0", "TABLE", "2", "LAYER",
    "0", "LAYER", "2", "THICK", "70", "0", "62", "7", "370", "100",
    "0", "LAYER", "2", "PLAIN", "70", "0", "62", "7",
    "0", "ENDTAB", "0", "ENDSEC",
    "0", "SECTION", "2", "BLOCKS", "0", "BLOCK", "8", "0", "2", "B", "10", "0", "20", "0", "30", "0",
    *line("0", -2, 5), *line("0", null, 6),
    "0", "ENDBLK", "0", "ENDSEC",
    "0", "SECTION", "2", "ENTITIES", *entities.flatMap { it.toList() }.toTypedArray(), "0", "ENDSEC", "0", "EOF",
  )

  private fun weights(vararg entities: Array<String>) =
    readGeometry(*doc(*entities)).lines.map { it.layer to it.lineWeight }.toSet()

  @Test
  fun `толщина сущности, слоя и умолчание раскладываются по разным пакетам`() {
    val result = weights(
      line("PLAIN", 50, 0),   // явная 0,5 мм
      line("THICK", -1, 1),   // BYLAYER → 1 мм слоя
      line("THICK", null, 2), // по умолчанию тоже BYLAYER
      line("PLAIN", -3, 3),   // DEFAULT
      line("PLAIN", null, 4), // слой без толщины
    )
    assertEquals(setOf("PLAIN" to 50, "THICK" to 100, "PLAIN" to 0), result)
  }

  @Test
  fun `BYBLOCK берёт толщину вхождения, BYLAYER в блоке - слоя вхождения`() {
    val insert = { weight: Int?, layer: String ->
      arrayOf("0", "INSERT", "8", layer, "2", "B", "10", "0", "20", "0", "30", "0") +
        (if (weight != null) arrayOf("370", "$weight") else emptyArray())
    }
    val g = readGeometry(*doc(insert(200, "THICK")))
    // слой «0» блока подменяется слоем вхождения; BYBLOCK = 2 мм, BYLAYER = толщина THICK (1 мм)
    assertEquals(setOf(200, 100), g.lines.map { it.lineWeight }.toSet())
    val bare = readGeometry(*doc(insert(null, "PLAIN")))
    // вхождение BYLAYER на слое без толщины: BYBLOCK-линии → 0, BYLAYER → 0
    assertEquals(setOf(0), bare.lines.map { it.lineWeight }.toSet())
  }

  @Test
  fun `ширина на экране ограничена 1-8 px`() {
    assertEquals(1f, lineWidthPx(0))
    assertEquals(1f, lineWidthPx(13))
    assertEquals(1.5f, lineWidthPx(50))
    assertEquals(3f, lineWidthPx(100))
    assertEquals(6.5f, lineWidthPx(211))
    assertEquals(8f, lineWidthPx(1000))
  }

  @Test
  fun `лента отрезка - квад из четырёх вершин с противоположными сторонами`() {
    val positions = floatArrayOf(0f, 0f, 0f, 10f, 0f, 0f)
    val ribbon = buildRibbon(positions, intArrayOf(0, 1))
    assertEquals(4, ribbon.positions.size / 3)
    assertEquals(6, ribbon.indices.size)
    // вектор к другому концу: с A — вперёд, с B — назад
    assertEquals(10f, ribbon.deltas[0])
    assertEquals(-10f, ribbon.deltas[2 * 4])
    // A+ и B+ лежат по одну физическую сторону: знак на B обратный из-за обратного направления; модуль 2 — конец B
    assertEquals(1f, ribbon.deltas[3])
    assertEquals(-1f, ribbon.deltas[1 * 4 + 3])
    assertEquals(-2f, ribbon.deltas[2 * 4 + 3])
    assertEquals(2f, ribbon.deltas[3 * 4 + 3])
    assertTrue(ribbon.indices.all { it in 0..3 })
  }
}
