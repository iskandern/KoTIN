package dev.iskandern.kotin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DxfAttributeTest {

  private fun attdef(tag: String, value: String, flags: Int, x: String = "1") =
    arrayOf("0", "ATTDEF", "8", "0", "10", x, "20", "0", "30", "0", "40", "1", "1", value, "2", tag, "70", "$flags")

  private fun doc(blockEntities: Array<String>, vararg entities: String) = arrayOf(
    "0", "SECTION", "2", "BLOCKS", "0", "BLOCK", "8", "0", "2", "B", "10", "0", "20", "0", "30", "0",
    *blockEntities,
    "0", "ENDBLK", "0", "ENDSEC",
    "0", "SECTION", "2", "ENTITIES", *entities, "0", "ENDSEC", "0", "EOF",
  )

  private fun insert(x: String) = arrayOf("0", "INSERT", "8", "0", "2", "B", "10", x, "20", "0", "30", "0")

  @Test
  fun `константный ATTDEF - подпись у каждой вставки`() {
    val labels = readGeometry(
      *doc(attdef("PK", "ПК", flags = 2), *insert("0"), *insert("10"))
    ).labels
    assertEquals(listOf("ПК", "ПК"), labels.map { it.text })
    assertEquals(setOf(1.0, 11.0), labels.map { it.x }.toSet())
  }

  @Test
  fun `неконстантный и невидимый ATTDEF без ATTRIB - ничего`() {
    val labels = readGeometry(
      *doc(arrayOf(*attdef("NAME", "имя", flags = 0), *attdef("HID", "скрыт", flags = 3)), *insert("0"))
    ).labels
    assertTrue(labels.isEmpty(), labels.map { it.text }.toString())
  }

  @Test
  fun `ATTDEF в модели не читается`() {
    val labels = readGeometry(*doc(emptyArray(), *attdef("PK", "ПК", flags = 2))).labels
    assertTrue(labels.isEmpty())
  }

  @Test
  fun `многострочный ATTRIB - две подписи`() {
    val labels = readGeometry(
      *doc(
        emptyArray(),
        *insert("0"), "66", "1",
        // до 101 — первая строка и свои координаты, после — вложенный MTEXT
        "0", "ATTRIB", "8", "0", "10", "5", "20", "5", "30", "0", "40", "1", "1", "Строка 1", "2", "INFO", "70", "0",
        "101", "Embedded Object", "10", "1", "20", "10", "30", "0", "40", "0.5", "41", "0", "71", "1",
        "1", "Строка 1\\PСтрока 2",
        "0", "SEQEND",
      )
    ).labels.sortedByDescending { it.y }
    assertEquals(listOf("Строка 1", "Строка 2"), labels.map { it.text })
    assertTrue(labels.all { it.x == 1.0 && it.height == 0.5 })
    // привязка «сверху слева»: первая строка под точкой вставки, вторая ниже
    assertTrue(labels[0].y < 10.0 && labels[1].y < labels[0].y)
  }

  @Test
  fun `константный многострочный ATTDEF`() {
    val labels = readGeometry(
      *doc(
        arrayOf(
          "0", "ATTDEF", "8", "0", "10", "0", "20", "0", "30", "0", "40", "1", "1", "а", "2", "N", "70", "2",
          "101", "Embedded Object", "10", "2", "20", "0", "30", "0", "40", "1", "1", "а\\Pб",
        ),
        *insert("10"),
      )
    ).labels
    assertEquals(setOf("а", "б"), labels.map { it.text }.toSet())
    assertTrue(labels.all { it.x == 12.0 })
  }
}
