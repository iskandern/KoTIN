package dev.iskandern.kotin

import dev.iskandern.kotin.dxf.dxfTransparencyAlpha
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DxfTransparencyTest {

  private fun solid(layer: String, transparency: Int?, x: Int) = arrayOf("0", "SOLID", "8", layer) +
    (if (transparency != null) arrayOf("440", "$transparency") else emptyArray()) +
    arrayOf(
      "10", "$x", "20", "0", "30", "0", "11", "${x + 1}", "21", "0", "31", "0",
      "12", "$x", "22", "1", "32", "0", "13", "${x + 1}", "23", "1", "33", "0",
    )

  // слой GLASS с прозрачностью в XDATA AcCmTransparency (альфа 0x3F), слой PLAIN без неё; блок B — SOLID BYBLOCK и BYLAYER
  private fun doc(vararg entities: Array<String>) = arrayOf(
    "0", "SECTION", "2", "TABLES", "0", "TABLE", "2", "LAYER",
    "0", "LAYER", "2", "GLASS", "70", "0", "62", "5", "1001", "AcCmTransparency", "1071", "${0x0200003F}",
    "0", "LAYER", "2", "PLAIN", "70", "0", "62", "7",
    "0", "ENDTAB", "0", "ENDSEC",
    "0", "SECTION", "2", "BLOCKS", "0", "BLOCK", "8", "0", "2", "B", "10", "0", "20", "0", "30", "0",
    *solid("0", 0x01000000, 0), *solid("PLAIN", null, 2),
    "0", "ENDBLK", "0", "ENDSEC",
    "0", "SECTION", "2", "ENTITIES", *entities.flatMap { it.toList() }.toTypedArray(), "0", "ENDSEC", "0", "EOF",
  )

  private fun alphas(vararg entities: Array<String>) =
    readGeometry(*doc(*entities)).fills.map { it.layer to it.alpha }.toSet()

  @Test
  fun `код 440 - альфа в младшем байте, BYBLOCK и мусор - не альфа`() {
    assertEquals(127, dxfTransparencyAlpha(0x0200007F))
    assertEquals(0, dxfTransparencyAlpha(0x02000000))
    assertEquals(255, dxfTransparencyAlpha(0x020000FF))
    assertNull(dxfTransparencyAlpha(0x01000000))
    assertNull(dxfTransparencyAlpha(127))
  }

  @Test
  fun `прозрачность сущности, слоя и умолчание раскладываются по разным пакетам заливок`() {
    val result = alphas(
      solid("PLAIN", 0x0200007F, 0), // явная альфа 127
      solid("GLASS", null, 2),       // BYLAYER → альфа слоя 63
      solid("PLAIN", null, 4),       // слой без прозрачности — непрозрачно
      solid("PLAIN", 0x01000000, 6), // BYBLOCK вне блока — непрозрачно
    )
    assertEquals(setOf("PLAIN" to 127, "GLASS" to 63, "PLAIN" to 255), result)
  }

  @Test
  fun `BYBLOCK берёт прозрачность вхождения, BYLAYER в блоке - слоя`() {
    val insert = arrayOf("0", "INSERT", "8", "GLASS", "2", "B", "10", "0", "20", "0", "30", "0", "440", "${0x02000040}")
    // SOLID слоя «0» с BYBLOCK уходит на слой вхождения GLASS с альфой вхождения; SOLID на PLAIN — непрозрачный
    assertEquals(setOf("GLASS" to 0x40, "PLAIN" to 255), alphas(insert))
    // вхождение без 440 — BYLAYER, то есть альфа слоя GLASS; BYBLOCK-заливка её наследует
    val bare = arrayOf("0", "INSERT", "8", "GLASS", "2", "B", "10", "0", "20", "0", "30", "0")
    assertEquals(setOf("GLASS" to 63, "PLAIN" to 255), alphas(bare))
  }

  @Test
  fun `тестовый файл задачи - хэтчи, блок и SOLID со своей прозрачностью`() {
    val g = readGeometry(File(testDxfDir, "transparency.dxf"))
    assertEquals(setOf(255, 127, 63, 0x40, 0xB0), g.fills.map { it.alpha }.toSet())
    // линии непрозрачны и по прозрачности не делятся: белая сетка и контур SOLID
    assertEquals(2, g.lines.size)
  }
}
