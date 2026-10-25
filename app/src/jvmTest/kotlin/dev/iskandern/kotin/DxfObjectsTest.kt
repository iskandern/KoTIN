package dev.iskandern.kotin

import dev.iskandern.kotin.model.swappedXY
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DxfObjectsTest {

  private fun line(x1: Int, layer: String = "0") = arrayOf("0", "LINE", "8", layer, "10", "$x1", "20", "0", "11", "${x1 + 1}", "21", "1")

  private val block = arrayOf(
    "0", "SECTION", "2", "BLOCKS",
    "0", "BLOCK", "8", "0", "2", "B", "70", "0", "10", "0", "20", "0", "30", "0",
    "0", "LINE", "8", "0", "10", "0", "20", "0", "11", "1", "21", "0",
    "0", "LINE", "8", "0", "10", "0", "20", "1", "11", "1", "21", "1",
    "0", "ENDBLK", "8", "0",
    "0", "ENDSEC",
  )

  private fun read(vararg entities: Array<String>) = readGeometry(
    *block, "0", "SECTION", "2", "ENTITIES", *entities.flatten().toTypedArray(), "0", "ENDSEC", "0", "EOF",
  )

  @Test
  fun `каждая сущность верхнего уровня - свой объект, отрезки одного пакета различаются`() {
    val geometry = read(line(0), line(10), line(20))
    val batch = geometry.lines.single()
    val ids = batch.objectIds!!
    assertEquals(3, ids.size)
    assertEquals(3, ids.toSet().size)
    assertTrue(ids.all { geometry.objects[it].kind == "LINE" })
  }

  @Test
  fun `вставка блока - один объект на все её отрезки`() {
    val insert = arrayOf("0", "INSERT", "8", "0", "2", "B", "10", "0", "20", "0", "30", "0")
    val geometry = read(line(0), insert, insert, line(5))
    val ids = geometry.lines.single().objectIds!!
    // line, 2 + 2 отрезка двух вставок, line
    assertEquals(6, ids.size)
    assertEquals(ids[1], ids[2])
    assertEquals(ids[3], ids[4])
    assertNotEquals(ids[1], ids[3])
    assertEquals("INSERT", geometry.objects[ids[1]].kind)
    assertEquals("B", geometry.objects[ids[1]].name)
  }

  @Test
  fun `3DFACE модели - одна поверхность на слой, подпись и точка получают свой объект`() {
    val face = arrayOf(
      "0", "3DFACE", "8", "TIN",
      "10", "0", "20", "0", "30", "0", "11", "1", "21", "0", "31", "0", "12", "0", "22", "1", "32", "1", "13", "0", "23", "1", "33", "1",
    )
    val text = arrayOf("0", "TEXT", "8", "0", "10", "0", "20", "0", "30", "0", "40", "1", "1", "abc")
    val point = arrayOf("0", "POINT", "8", "0", "10", "3", "20", "3", "30", "0")
    val geometry = read(face, face, text, point)
    val mesh = geometry.meshes.single()
    val surface = geometry.objects[mesh.objectOf(0)]
    assertEquals("3DFACE", surface.kind)
    assertEquals("TIN", surface.name)
    assertEquals(mesh.objectOf(0), mesh.objectOf(mesh.triangleCount - 1))
    assertEquals("abc", geometry.objects[geometry.labels.single().objectId].name)
    assertEquals("POINT", geometry.objects[geometry.points.single().objectId].kind)
  }

  @Test
  fun `строки MTEXT - один объект`() {
    val mtext = arrayOf("0", "MTEXT", "8", "0", "10", "0", "20", "0", "30", "0", "40", "1", "1", "abc\\Pdef")
    val geometry = read(mtext, line(10))
    assertEquals(2, geometry.labels.size)
    assertEquals(1, geometry.labels.map { it.objectId }.toSet().size)
    assertEquals(listOf("MTEXT", "LINE"), geometry.objects.map { it.kind })
  }

  @Test
  fun `LEADER со стрелкой - один объект`() {
    val leader = arrayOf(
      "0", "LEADER", "8", "0", "71", "1", "10", "0", "20", "0", "30", "0", "10", "10", "20", "0", "30", "0",
    )
    val geometry = read(leader, line(20))
    assertEquals(listOf("LEADER", "LINE"), geometry.objects.map { it.kind })
    val ids = geometry.lines.flatMap { it.objectIds!!.toList() } + geometry.fills.flatMap { it.objectIds!!.toList() }
    assertEquals(setOf(0, 1), ids.toSet())
    assertTrue(geometry.fills.isNotEmpty())
  }

  @Test
  fun `сущность без примитивов объект не заводит`() {
    val missing = arrayOf("0", "INSERT", "8", "0", "2", "NOPE", "10", "0", "20", "0", "30", "0")
    val geometry = read(missing, line(0))
    assertEquals(listOf("LINE"), geometry.objects.map { it.kind })
  }

  @Test
  fun `перестановка X и Y сохраняет объекты`() {
    val text = arrayOf("0", "TEXT", "8", "0", "10", "0", "20", "0", "30", "0", "40", "1", "1", "abc")
    val geometry = read(line(0), line(10), text)
    val swapped = geometry.swappedXY()
    assertEquals(geometry.objects.size, swapped.objects.size)
    assertContentEquals(geometry.lines.single().objectIds, swapped.lines.single().objectIds)
    assertEquals(geometry.labels.single().objectId, swapped.labels.single().objectId)
  }
}
