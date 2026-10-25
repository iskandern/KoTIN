package dev.iskandern.kotin

import dev.iskandern.kotin.dxf.POINT_MODE_UNSET
import dev.iskandern.kotin.dxf.DxfColors
import dev.iskandern.kotin.dxf.DxfDocumentReader
import dev.iskandern.kotin.dxf.DxfFlattener
import dev.iskandern.kotin.model.SceneGeometry
import dev.iskandern.kotin.model.MeshBuilder
import dev.iskandern.kotin.io.asByteInput
import dev.iskandern.kotin.dxf.interpolateFitPoints
import dev.iskandern.kotin.scene.buildRenderScene
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DxfGeometryTest {

  private fun section(name: String, vararg body: String) = arrayOf("0", "SECTION", "2", name, *body, "0", "ENDSEC")

  private fun dxf(vararg sections: Array<String>): Array<String> = arrayOf(*sections.flatten().toTypedArray(), "0", "EOF")

  @Test
  fun `все тестовые DXF открываются`() {
    // TODO(KoTIN-13): бинарные файлы пока не читаются — убрать фильтр, когда появится бинарный токенайзер
    val files = testDxfDir.walkTopDown()
      .filter { it.extension.equals("dxf", ignoreCase = true) && (!it.name.contains("-binary-") || it.name.contains("ascii-twin")) }
      .toList()
    assertTrue(files.isNotEmpty())
    files.forEach { buildRenderScene(readGeometry(it)) }
  }

  @Test
  fun `polyface-сетка из POLYLINE`() {
    val geometry = readGeometry(
      *dxf(section("ENTITIES",
        "0", "POLYLINE", "8", "PF", "66", "1", "70", "64", "71", "4", "72", "2",
        "0", "VERTEX", "8", "PF", "10", "0", "20", "0", "30", "0", "70", "192",
        "0", "VERTEX", "8", "PF", "10", "1", "20", "0", "30", "0", "70", "192",
        "0", "VERTEX", "8", "PF", "10", "1", "20", "1", "30", "1", "70", "192",
        "0", "VERTEX", "8", "PF", "10", "0", "20", "1", "30", "1", "70", "192",
        "0", "VERTEX", "8", "PF", "10", "0", "20", "0", "30", "0", "70", "128", "71", "1", "72", "2", "73", "3", "74", "-4",
        "0", "SEQEND",
      ))
    )
    val mesh = geometry.meshes.single()
    assertEquals("PF", mesh.layer)
    assertEquals(4, mesh.vertexCount)
    assertEquals(2, mesh.triangleCount)
  }

  @Test
  fun `MESH-куб - 12 треугольников, шестиугольник - веер из 4`() {
    val cube = arrayOf(
      "0", "MESH", "8", "M", "100", "AcDbSubDMesh", "71", "2", "72", "0", "91", "0", "92", "8",
      "10", "0", "20", "0", "30", "0", "10", "1", "20", "0", "30", "0", "10", "1", "20", "1", "30", "0", "10", "0", "20", "1", "30", "0",
      "10", "0", "20", "0", "30", "1", "10", "1", "20", "0", "30", "1", "10", "1", "20", "1", "30", "1", "10", "0", "20", "1", "30", "1",
      "93", "30",
      "90", "4", "90", "0", "90", "3", "90", "2", "90", "1", "90", "4", "90", "4", "90", "5", "90", "6", "90", "7",
      "90", "4", "90", "0", "90", "1", "90", "5", "90", "4", "90", "4", "90", "1", "90", "2", "90", "6", "90", "5",
      "90", "4", "90", "2", "90", "3", "90", "7", "90", "6", "90", "4", "90", "3", "90", "0", "90", "4", "90", "7",
      "94", "1", "90", "0", "90", "1", "95", "1", "140", "3.0", "90", "0",
    )
    val geometry = readGeometry(*dxf(section("ENTITIES", *cube)))
    val mesh = geometry.meshes.single()
    assertEquals("M", mesh.layer)
    assertEquals(12, mesh.triangleCount)

    val hexagon = readGeometry(
      *dxf(section("ENTITIES",
        "0", "MESH", "8", "H", "92", "6",
        "10", "1", "20", "0", "30", "0", "10", "2", "20", "0", "30", "0", "10", "3", "20", "1", "30", "0",
        "10", "2", "20", "2", "30", "0", "10", "1", "20", "2", "30", "0", "10", "0", "20", "1", "30", "0",
        "93", "7", "90", "6", "90", "0", "90", "1", "90", "2", "90", "3", "90", "4", "90", "5",
      ))
    )
    assertEquals(4, hexagon.meshes.single().triangleCount)
  }

  private fun subdCube(level: Int, vararg tail: String) = arrayOf(
    "0", "MESH", "8", "M", "100", "AcDbSubDMesh", "71", "2", "72", "0", "91", "$level", "92", "8",
    "10", "0", "20", "0", "30", "0", "10", "1", "20", "0", "30", "0", "10", "1", "20", "1", "30", "0", "10", "0", "20", "1", "30", "0",
    "10", "0", "20", "0", "30", "1", "10", "1", "20", "0", "30", "1", "10", "1", "20", "1", "30", "1", "10", "0", "20", "1", "30", "1",
    "93", "30",
    "90", "4", "90", "0", "90", "3", "90", "2", "90", "1", "90", "4", "90", "4", "90", "5", "90", "6", "90", "7",
    "90", "4", "90", "0", "90", "1", "90", "5", "90", "4", "90", "4", "90", "1", "90", "2", "90", "6", "90", "5",
    "90", "4", "90", "2", "90", "3", "90", "7", "90", "6", "90", "4", "90", "3", "90", "0", "90", "4", "90", "7",
    *tail,
  )

  @Test
  fun `MESH с подразделением - Catmull-Clark`() {
    // уровень 1: 6 граней × 4 = 24 четырёхугольника = 48 треугольников
    val one = readGeometry(*dxf(section("ENTITIES", *subdCube(1)))).meshes.single()
    assertEquals(48, one.triangleCount)
    assertEquals(26, one.vertexCount)
    // угол (0,0,0): (F + 2R + (n − 3)P) / n, F = 1/3, R = 1/6, n = 3 → 2/9 по каждой оси
    val corner = (0 until one.vertexCount).any { i -> (0..2).all { abs(one.coords[i * 3 + it] - 2.0 / 9) < 1e-12 } }
    assertTrue(corner)
    // точки граней лежат на исходных гранях
    assertEquals(0.0, one.minX, 1e-12)
    assertEquals(1.0, one.maxZ, 1e-12)

    val two = readGeometry(*dxf(section("ENTITIES", *subdCube(2)))).meshes.single()
    assertEquals(192, two.triangleCount)
    // уровень выше трёх ограничивается тремя
    assertEquals(768, readGeometry(*dxf(section("ENTITIES", *subdCube(9)))).meshes.single().triangleCount)
  }

  @Test
  fun `MESH - рёбра со складкой остаются острыми`() {
    // все 12 рёбер куба — складки −1 (всегда острые): сетка остаётся кубом
    val edges = arrayOf(0 to 1, 1 to 2, 2 to 3, 3 to 0, 4 to 5, 5 to 6, 6 to 7, 7 to 4, 0 to 4, 1 to 5, 2 to 6, 3 to 7)
    val tail = arrayOf("94", "12") + edges.flatMap { (a, b) -> listOf("90", "$a", "90", "$b") } +
      arrayOf("95", "12") + edges.flatMap { listOf("140", "-1") } + arrayOf("90", "0")
    val sharp = readGeometry(*dxf(section("ENTITIES", *subdCube(2, *tail)))).meshes.single()
    assertEquals(192, sharp.triangleCount)
    assertEquals(0.0, sharp.minX, 1e-12)
    assertEquals(1.0, sharp.maxZ, 1e-12)
    // все вершины на гранях куба
    for (i in 0 until sharp.vertexCount) {
      val p = (0..2).map { sharp.coords[i * 3 + it] }
      assertTrue(p.any { abs(it) < 1e-12 || abs(it - 1) < 1e-12 }, "$p")
    }

    // складка только на ребре нижней грани 0-1: его вершины остаются на ребре y = 0, z = 0
    val one = readGeometry(*dxf(section("ENTITIES",
      *subdCube(1, "94", "1", "90", "0", "90", "1", "95", "1", "140", "3", "90", "0")))).meshes.single()
    val onEdge = (0 until one.vertexCount).count { abs(one.coords[it * 3 + 1]) < 1e-12 && abs(one.coords[it * 3 + 2]) < 1e-12 }
    assertEquals(1, onEdge) // середина ребра; концы с одним острым ребром сглаживаются
  }

  @Test
  fun `вхождение блока - перенос, поворот, масштаб, слой 0 и цвет BYBLOCK`() {
    val geometry = readGeometry(
      *dxf(
        section("TABLES", "0", "TABLE", "2", "LAYER", "0", "LAYER", "2", "Оси", "62", "3", "0", "ENDTAB"),
        section("BLOCKS",
          "0", "BLOCK", "2", "B", "10", "1", "20", "0", "30", "0",
          // отрезок в блоке на слое 0 цветом BYBLOCK
          "0", "LINE", "8", "0", "62", "0", "10", "1", "20", "0", "30", "0", "11", "2", "21", "0", "31", "0",
          // грань блока тоже раскрывается в сетку
          "0", "3DFACE", "8", "TIN", "10", "1", "20", "0", "30", "5", "11", "2", "21", "0", "31", "5",
          "12", "1", "22", "1", "32", "5", "13", "1", "23", "1", "33", "5",
          "0", "ENDBLK",
        ),
        section("ENTITIES",
          "0", "INSERT", "8", "Оси", "62", "1", "2", "B", "10", "100", "20", "200", "30", "0", "41", "2", "42", "2", "43", "1", "50", "90",
        ),
      )
    )
    val batch = geometry.lines.single()
    assertEquals("Оси", batch.layer)
    assertEquals(0xFF0000, batch.color)
    // (1,0) → после сдвига на базу (0,0) → ×2 → поворот на 90° → (0,0) + (100,200); (2,0) → (100, 202)
    assertClose(doubleArrayOf(100.0, 200.0, 0.0, 100.0, 202.0, 0.0), batch.coords)
    assertTrue(batch.flat)
    assertEquals(1, geometry.meshes.single { it.layer == "TIN" }.triangleCount)
  }

  @Test
  fun `вложенный блок и массив MINSERT`() {
    val geometry = readGeometry(
      *dxf(
        section("BLOCKS",
          "0", "BLOCK", "2", "Inner", "10", "0", "20", "0", "30", "0",
          "0", "POINT", "8", "P", "10", "1", "20", "1", "30", "0",
          "0", "ENDBLK",
          "0", "BLOCK", "2", "Outer", "10", "0", "20", "0", "30", "0",
          "0", "INSERT", "8", "0", "2", "Inner", "10", "10", "20", "0", "30", "0",
          "0", "ENDBLK",
        ),
        section("ENTITIES",
          "0", "INSERT", "8", "L", "2", "Outer", "10", "0", "20", "0", "30", "0", "70", "2", "71", "1", "44", "100",
        ),
      )
    )
    assertEquals(listOf(11.0 to 1.0, 111.0 to 1.0), geometry.points.map { it.x to it.y })
  }

  @Test
  fun `блок, вставляющий сам себя, не зацикливается`() {
    val geometry = readGeometry(
      *dxf(
        section("BLOCKS",
          "0", "BLOCK", "2", "Loop", "10", "0", "20", "0", "30", "0",
          "0", "INSERT", "8", "0", "2", "Loop", "10", "1", "20", "0", "30", "0",
          "0", "LINE", "8", "0", "10", "0", "20", "0", "30", "0", "11", "1", "21", "0", "31", "0",
          "0", "ENDBLK",
        ),
        section("ENTITIES", "0", "INSERT", "8", "L", "2", "Loop", "10", "0", "20", "0", "30", "0"),
      )
    )
    assertEquals(1, geometry.lines.sumOf { it.segmentCount })
  }

  // сеть POLYLINE M×N: вершина (i, j) в точке (j, i, i·j)
  private fun polygonMesh(m: Int, n: Int, flags: Int): Array<String> {
    val vertices = (0 until m).flatMap { i ->
      (0 until n).flatMap { j -> listOf("0", "VERTEX", "8", "S", "10", "$j", "20", "$i", "30", "${i * j}", "70", "64") }
    }
    return arrayOf(
      "0", "POLYLINE", "8", "S", "66", "1", "70", "$flags", "71", "$m", "72", "$n",
      *vertices.toTypedArray(), "0", "SEQEND",
    )
  }

  @Test
  fun `сеть POLYLINE 3×4 - 6 четырёхугольников без ломаной`() {
    val geometry = readGeometry(*dxf(section("ENTITIES", *polygonMesh(3, 4, flags = 16))))
    assertEquals(12, geometry.meshes.single().triangleCount)
    assertTrue(geometry.lines.isEmpty())
  }

  @Test
  fun `сеть POLYLINE, замкнутая по M и по N`() {
    // по M (70 & 1) строки замыкаются последняя с первой: ещё N − 1 = 3 четырёхугольника
    val closedM = readGeometry(*dxf(section("ENTITIES", *polygonMesh(3, 4, flags = 16 or 1))))
    assertEquals(18, closedM.meshes.single().triangleCount)
    // по N (70 & 32) каждая строка замыкается в кольцо: ещё M − 1 = 2 четырёхугольника
    val closedN = readGeometry(*dxf(section("ENTITIES", *polygonMesh(3, 4, flags = 16 or 32))))
    assertEquals(16, closedN.meshes.single().triangleCount)
    assertTrue(closedN.lines.isEmpty())
  }

  @Test
  fun `POLYLINE со сглаживанием сплайном рисует кривую без рамки`() {
    val frame = listOf(0.0 to 0.0, 10.0 to 10.0, 20.0 to -10.0, 30.0 to 0.0).flatMap { (x, y) ->
      listOf("0", "VERTEX", "8", "C", "10", "$x", "20", "$y", "30", "0", "70", "16")
    }
    // вершины кривой — на параболе y = 100 + x² / 4 вдали от рамки
    val curve = (0 until 20).flatMap { k ->
      listOf("0", "VERTEX", "8", "C", "10", "$k", "20", "${100 + k * k / 4.0}", "30", "0", "70", "8")
    }
    val geometry = readGeometry(
      *dxf(section("ENTITIES",
        "0", "POLYLINE", "8", "C", "66", "1", "70", "4", "75", "6",
        *frame.toTypedArray(), *curve.toTypedArray(), "0", "SEQEND",
      ))
    )
    val line = geometry.lines.single()
    assertEquals(19, line.segmentCount)
    assertTrue((1 until line.coords.size step 3).all { line.coords[it] > 50 })
  }

  @Test
  fun `LWPOLYLINE с bulge даёт полуокружность`() {
    // bulge = 1 — полуокружность от (0,0) до (2,0), центр (1,0), радиус 1
    val geometry = readGeometry(
      *dxf(section("ENTITIES",
        "0", "LWPOLYLINE", "8", "A", "90", "2", "70", "0", "10", "0", "20", "0", "42", "1", "10", "2", "20", "0",
      ))
    )
    val coords = geometry.lines.single().coords
    assertTrue(coords.size / 6 > 10)
    for (i in 0 until coords.size / 3) {
      assertEquals(1.0, hypot(coords[i * 3] - 1, coords[i * 3 + 1]), 1e-9)
    }
    // против часовой стрелки от (0,0): через нижнюю точку (1,-1)
    assertTrue((0 until coords.size / 3).any { abs(coords[it * 3 + 1] + 1) < 1e-2 })
  }

  @Test
  fun `LEADER из трёх вершин даёт два отрезка`() {
    val geometry = readGeometry(
      *dxf(section("ENTITIES",
        "0", "LEADER", "8", "A", "71", "0", "72", "0",
        "10", "0", "20", "0", "30", "0", "10", "1", "20", "1", "30", "0", "10", "3", "20", "1", "30", "2",
      ))
    )
    val coords = geometry.lines.single().coords
    assertEquals(2, geometry.lines.single().segmentCount)
    assertEquals(3.0, coords[coords.size - 6 + 3], 1e-12)
    assertEquals(2.0, coords[coords.size - 1], 1e-12)
    // без стрелки (71 = 0) заливки нет
    assertTrue(geometry.fills.isEmpty())
  }

  @Test
  fun `стрелка LEADER длиной DIMASZ × DIMSCALE стиля размеров`() {
    val geometry = readGeometry(
      *dxf(
        section("HEADER", "9", "\$DIMASZ", "40", "0.5", "9", "\$DIMSCALE", "40", "3"),
        section("TABLES",
          "0", "TABLE", "2", "DIMSTYLE", "70", "1",
          "0", "DIMSTYLE", "105", "1A", "2", "ISO", "41", "2.5", "40", "2",
          "0", "ENDTAB",
        ),
        section("ENTITIES",
          "0", "LEADER", "8", "A", "3", "ISO", "71", "1", "72", "0",
          "10", "0", "20", "0", "30", "0", "10", "10", "20", "0", "30", "0",
          // без стиля в таблице — $DIMASZ × $DIMSCALE из заголовка
          "0", "LEADER", "8", "B", "3", "NONE", "71", "1",
          "10", "0", "20", "5", "30", "0", "10", "0", "20", "15", "30", "0",
          // переопределение в XDATA: DIMASZ (41) = 1, DIMSCALE (40) = 4; handle блока стрелки (341) не мешает
          "0", "LEADER", "8", "C", "3", "ISO", "71", "1",
          "10", "20", "20", "0", "30", "0", "10", "30", "20", "0", "30", "0",
          "1001", "ACAD", "1000", "DSTYLE", "1002", "{", "1070", "341", "1005", "2F",
          "1070", "41", "1040", "1", "1070", "40", "1040", "4", "1002", "}",
        ),
      )
    )
    fun arrow(layer: String) = geometry.fills.single { it.layer == layer }.coords
    // ISO: 2,5 × 2 = 5 вдоль X, полуширина 5/6; остриё на первой вершине
    val a = arrow("A")
    assertEquals(9, a.size)
    assertEquals(0.0, a[0], 1e-12)
    assertEquals(0.0, a[1], 1e-12)
    assertEquals(5.0, a[3], 1e-12)
    assertEquals(5.0, a[6], 1e-12)
    assertEquals(5.0 / 3, abs(a[4] - a[7]), 1e-12)
    // заголовок: 0,5 × 3 = 1,5 вдоль Y
    val b = arrow("B")
    assertEquals(6.5, b[4], 1e-12)
    assertEquals(0.5, abs(b[3] - b[6]), 1e-12)
    // XDATA: 1 × 4 = 4
    assertEquals(24.0, arrow("C")[3], 1e-12)
  }

  @Test
  fun `сплайновый LEADER проходит через вершины плавной кривой`() {
    val geometry = readGeometry(
      *dxf(section("ENTITIES",
        "0", "LEADER", "8", "A", "71", "1", "72", "1",
        "10", "0", "20", "0", "30", "0", "10", "4", "20", "3", "30", "0", "10", "8", "20", "0", "30", "0",
      ))
    )
    val line = geometry.lines.single { it.segmentCount > 3 }
    val c = line.coords
    assertTrue(line.segmentCount > 10)
    // через среднюю вершину (4, 3), выше неё не уходит
    assertTrue((0 until c.size / 3).any { hypot(c[it * 3] - 4, c[it * 3 + 1] - 3) < 1e-6 })
    assertTrue((0 until c.size / 3).all { c[it * 3 + 1] <= 3 + 1e-9 })
    // стрелка по касательной в начале: круче хорды к (4, 3)
    val tip = geometry.fills.single().coords
    val midX = (tip[3] + tip[6]) / 2
    val midY = (tip[4] + tip[7]) / 2
    assertEquals(0.18, hypot(midX, midY), 1e-9)
    assertTrue(midY / midX > 3.0 / 4)
  }

  @Test
  fun `стрелки MULTILEADER — по коду 42, иначе по стилю MLEADERSTYLE`() {
    fun mleader(layer: String, tail: Array<String>) = arrayOf(
      "0", "MULTILEADER", "8", layer, "100", "AcDbMLeader",
      "300", "CONTEXT_DATA{", "40", "1", "41", "1", "140", "9", "290", "0",
      "302", "LEADER{", "290", "1", "291", "0", "10", "10", "20", "0", "30", "0",
      "304", "LEADER_LINE{", "10", "0", "20", "0", "30", "0", "305", "}",
      "304", "LEADER_LINE{", "10", "0", "20", "5", "30", "0", "305", "}", "303", "}", "301", "}",
      *tail,
    )
    val geometry = readGeometry(
      *dxf(
        section("ENTITIES",
          *mleader("A", arrayOf("340", "2C", "42", "2")),
          *mleader("B", arrayOf("340", "2C")),
        ),
        section("OBJECTS", "0", "MLEADERSTYLE", "5", "2C", "100", "AcDbMLeaderStyle", "44", "3"),
      )
    )
    // по стрелке на каждую линию, остриё — на её первой вершине, ось — к точке выноски (10, 0)
    val a = geometry.fills.single { it.layer == "A" }
    assertEquals(2, a.triangleCount)
    assertEquals(0.0, a.coords[0], 1e-12)
    assertEquals(2.0, (a.coords[3] + a.coords[6]) / 2, 1e-12)
    assertEquals(5.0, a.coords[10], 1e-12)
    val b = geometry.fills.single { it.layer == "B" }.coords
    assertEquals(3.0, (b[3] + b[6]) / 2, 1e-12)
  }

  @Test
  fun `MULTILEADER даёт ломаную выноски с полкой и подпись`() {
    val geometry = readGeometry(
      *dxf(section("ENTITIES",
        "0", "MULTILEADER", "8", "A", "100", "AcDbMLeader",
        "300", "CONTEXT_DATA{", "40", "1", "10", "-10", "20", "5.5", "30", "0", "41", "4", "290", "1", "304", "Hello",
        "12", "-8", "22", "6", "32", "0", "13", "1", "23", "0", "33", "0",
        "302", "LEADER{", "290", "1", "291", "1", "10", "-18", "20", "5.5", "30", "0", "11", "1", "21", "0", "31", "0", "40", "8",
        "304", "LEADER_LINE{", "10", "0", "20", "0", "30", "0", "305", "}", "303", "}", "301", "}",
        "10", "1", "20", "1", "30", "1",
      ))
    )
    val line = geometry.lines.single()
    // два отрезка выноски и три — контур стрелки
    assertEquals(5, line.segmentCount)
    val c = line.coords
    // кончик (0,0) → точка выноски (-18,5.5) → конец полки (-10,5.5)
    assertEquals(-18.0, c[3], 1e-12)
    assertEquals(-10.0, c[9], 1e-12)
    assertEquals(5.5, c[10], 1e-12)
    assertEquals("Hello", geometry.labels.single().text)
    assertEquals(1, geometry.fills.single().triangleCount)
  }

  @Test
  fun `ширина текста MULTILEADER (43) даёт рамку для переноса, нулевая — нет`() {
    fun label(width: String) = readGeometry(
      *dxf(section("ENTITIES",
        "0", "MULTILEADER", "8", "A", "100", "AcDbMLeader",
        "300", "CONTEXT_DATA{", "40", "1", "41", "2", "290", "1", "304", "Long text here",
        "12", "0", "22", "0", "32", "0", "13", "1", "23", "0", "33", "0", "43", width, "301", "}",
      ))
    ).labels.single()
    assertEquals(15.0, label("15").frame!!.width, 1e-12)
    assertEquals(null, label("0").frame)
  }

  @Test
  fun `TOLERANCE из двух ячеек даёт прямоугольник с перегородкой и две подписи`() {
    val geometry = readGeometry(
      *dxf(
        section("TABLES",
          "0", "TABLE", "2", "DIMSTYLE", "70", "1",
          "0", "DIMSTYLE", "105", "1A", "2", "ISO", "140", "2.5", "147", "0.5", "40", "2",
          "0", "ENDTAB",
        ),
        section("ENTITIES",
          "0", "TOLERANCE", "8", "T", "100", "AcDbFcf", "3", "ISO",
          "10", "10", "20", "20", "30", "0", "1", "{\\Fgdt;j}%%v0.05", "11", "1", "21", "0", "31", "0",
        ),
      )
    )
    // текст 2,5 × 2, зазор 0,5 × 2: строка высотой 5 + 2 = 7
    val labels = geometry.labels
    assertEquals(listOf("⌖", "0.05"), labels.map { it.text })
    assertTrue(labels.all { it.height == 5.0 && it.alignH == 1 && it.alignV == 2 })
    // символ — квадратная ячейка 7 × 7, подпись по её центру
    assertEquals(13.5, labels[0].x, 1e-9)
    assertEquals(20.0, labels[0].y, 1e-9)
    assertTrue(labels[1].x > 17.0)
    // рамка 4 отрезка + перегородка 1
    assertEquals(5, geometry.lines.sumOf { it.segmentCount })
    val coords = geometry.lines.flatMap { it.coords.toList() }
    val xs = coords.filterIndexed { i, _ -> i % 3 == 0 }
    val ys = coords.filterIndexed { i, _ -> i % 3 == 1 }
    assertEquals(10.0, xs.min(), 1e-9)
    assertEquals(16.5, ys.min(), 1e-9)
    assertEquals(23.5, ys.max(), 1e-9)
    assertTrue(xs.any { abs(it - 17.0) < 1e-9 })
  }

  @Test
  fun `строка TOLERANCE делится на строки рамки и ячейки, шрифт GDT — символами`() {
    assertEquals(
      listOf(listOf("⌖", "Ø0.1(M)", "A", "B"), listOf("—", "0.02")),
      dev.iskandern.kotin.dxf.parseTolerance(
        "{\\Fgdt;j}%%v{\\Fgdt;n}0.1{\\Fgdt;m}%%vA%%vB%%v^J{\\Fgdt;u}%%v0.02",
      ),
    )
  }

  @Test
  fun `окружность и дуга`() {
    val geometry = readGeometry(
      *dxf(section("ENTITIES",
        "0", "CIRCLE", "8", "C", "10", "5", "20", "5", "30", "0", "40", "2",
        "0", "ARC", "8", "C", "10", "0", "20", "0", "30", "0", "40", "1", "50", "0", "51", "90",
      ))
    )
    val coords = geometry.lines.single().coords
    val points = (0 until coords.size / 3).map { doubleArrayOf(coords[it * 3], coords[it * 3 + 1]) }
    val circle = points.filter { hypot(it[0] - 5, it[1] - 5) in 1.99..2.01 }
    val arc = points.filter { hypot(it[0], it[1]) in 0.99..1.01 }
    assertEquals(points.size, circle.size + arc.size)
    assertTrue(arc.all { it[0] >= -1e-9 && it[1] >= -1e-9 })
  }

  @Test
  fun `число шагов окружности растёт с радиусом`() {
    fun segments(radius: String): Int {
      val geometry = readGeometry(
        *dxf(section("ENTITIES",
          "0", "LINE", "8", "L", "10", "0", "20", "0", "30", "0", "11", "1000", "21", "0", "31", "0",
          "0", "CIRCLE", "8", "C", "10", "0", "20", "0", "30", "0", "40", radius,
        ))
      )
      return geometry.lines.single { it.layer == "C" }.coords.size / 6
    }
    val tiny = segments("0.01")
    val big = segments("500")
    assertEquals(16, tiny)
    assertTrue(big > 64 && big <= 512, "big=$big")
  }

  @Test
  fun `цвета ACI`() {
    assertEquals(0xFF0000, DxfColors.aciToRgb(1))
    assertEquals(0xFFFFFF, DxfColors.aciToRgb(7))
    assertEquals(0xFF0000, DxfColors.aciToRgb(10))
    assertEquals(0xFF7F7F, DxfColors.aciToRgb(11))
    assertEquals(0xA50000, DxfColors.aciToRgb(12))
    assertEquals(0x00FF00, DxfColors.aciToRgb(90))
    assertEquals(0x333333, DxfColors.aciToRgb(250))
  }

  @Test
  fun `MTEXT - снимается форматирование, строки отдельно`() {
    val geometry = readGeometry(
      *dxf(section("ENTITIES",
        "0", "MTEXT", "8", "T", "10", "0", "20", "0", "30", "0", "40", "2", "71", "1",
        "1", "{\\fArial|b0;Первая}\\PВторая %%c100",
      ))
    )
    assertEquals(listOf("Первая", "Вторая ⌀100"), geometry.labels.map { it.text })
    assertTrue(geometry.labels.all { it.height == 2.0 })
  }

  @Test
  fun `размер рисуется своим анонимным блоком`() {
    val geometry = readGeometry(
      *dxf(
        section("BLOCKS",
          "0", "BLOCK", "2", "*D1", "10", "0", "20", "0", "30", "0",
          "0", "LINE", "8", "0", "10", "5", "20", "5", "30", "0", "11", "6", "21", "5", "31", "0",
          "0", "ENDBLK",
        ),
        section("ENTITIES", "0", "DIMENSION", "8", "Dim", "2", "*D1", "10", "100", "20", "100", "30", "0"),
      )
    )
    assertClose(doubleArrayOf(5.0, 5.0, 0.0, 6.0, 5.0, 0.0), geometry.lines.single().coords)
  }

  @Test
  fun `ARC_DIMENSION и LARGE_RADIAL_DIMENSION рисуются анонимным блоком, без блока - ничего`() {
    val block = section("BLOCKS",
      "0", "BLOCK", "2", "*D1", "10", "0", "20", "0", "30", "0",
      "0", "LINE", "8", "0", "10", "5", "20", "5", "30", "0", "11", "6", "21", "5", "31", "0",
      "0", "LINE", "8", "0", "10", "6", "20", "5", "30", "0", "11", "6", "21", "7", "31", "0",
      "0", "ENDBLK",
    )
    for (type in listOf("ARC_DIMENSION", "LARGE_RADIAL_DIMENSION")) {
      val geometry = readGeometry(
        *dxf(block, section("ENTITIES",
          "0", type, "8", "Dim", "100", "AcDbEntity", "100", "AcDbDimension", "2", "*D1",
          "10", "100", "20", "100", "30", "0", "13", "1", "23", "2", "33", "0", "40", "3",
        ))
      )
      assertEquals(2, geometry.lines.sumOf { it.segmentCount }, type)
      val coords = geometry.lines.flatMap { it.coords.copyOf(it.segmentCount * 6).toList() }.toDoubleArray()
      assertClose(doubleArrayOf(5.0, 5.0, 0.0, 6.0, 5.0, 0.0, 6.0, 5.0, 0.0, 6.0, 7.0, 0.0), coords)

      val empty = readGeometry(
        *dxf(block, section("ENTITIES", "0", type, "8", "Dim", "10", "100", "20", "100", "30", "0"))
      )
      assertTrue(empty.lines.isEmpty(), type)
    }
  }

  @Test
  fun `SOLID с четырьмя и тремя углами заливается, контур остаётся`() {
    val geometry = readGeometry(
      *dxf(section("ENTITIES",
        // углы 1-2-4-3: квадрат 2x2
        "0", "SOLID", "8", "S", "62", "1", "10", "0", "20", "0", "30", "0", "11", "2", "21", "0", "31", "0",
        "12", "0", "22", "2", "32", "0", "13", "2", "23", "2", "33", "0",
        // 4-й угол не задан — один треугольник
        "0", "SOLID", "8", "S", "62", "1", "10", "10", "20", "0", "30", "0", "11", "12", "21", "0", "31", "0",
        "12", "10", "22", "2", "32", "0",
      ))
    )
    val fill = geometry.fills.single()
    assertEquals(3, fill.triangleCount)
    assertTrue(fill.flat)
    // квадрат: (1, 2, 4) и (1, 4, 3)
    assertClose(
      doubleArrayOf(0.0, 0.0, 0.0, 2.0, 0.0, 0.0, 2.0, 2.0, 0.0, 0.0, 0.0, 0.0, 2.0, 2.0, 0.0, 0.0, 2.0, 0.0),
      fill.coords.copyOf(18),
    )
    assertClose(doubleArrayOf(10.0, 0.0, 0.0, 12.0, 0.0, 0.0, 10.0, 2.0, 0.0), fill.coords.copyOfRange(18, 27))
    // контур: квадрат 4 отрезка + треугольник 3
    assertEquals(7, geometry.lines.sumOf { it.segmentCount })
  }

  @Test
  fun `SOLID в блоке с поворотом и масштабом - заливка совпадает с контуром`() {
    val geometry = readGeometry(
      *dxf(
        section("BLOCKS",
          "0", "BLOCK", "2", "B", "10", "0", "20", "0", "30", "0",
          "0", "SOLID", "8", "0", "62", "3", "10", "0", "20", "0", "30", "0", "11", "1", "21", "0", "31", "0",
          "12", "0", "22", "1", "32", "0", "13", "1", "23", "1", "33", "0",
          "0", "ENDBLK",
        ),
        section("ENTITIES",
          "0", "INSERT", "8", "L", "2", "B", "10", "100", "20", "200", "30", "0", "41", "2", "42", "2", "43", "1", "50", "90",
        ),
      )
    )
    val fill = geometry.fills.single()
    assertEquals("L", fill.layer)
    assertEquals(DxfColors.aciToRgb(3), fill.color)
    assertEquals(2, fill.triangleCount)
    // поворот на 90° и масштаб 2: (x, y) → (100 − 2y, 200 + 2x)
    assertClose(
      doubleArrayOf(100.0, 200.0, 0.0, 100.0, 202.0, 0.0, 98.0, 202.0, 0.0, 100.0, 200.0, 0.0, 98.0, 202.0, 0.0, 98.0, 200.0, 0.0),
      fill.coords,
    )
    // каждая точка заливки есть и среди точек контура
    val outline = geometry.lines.single().coords
    val outlinePoints = (0 until outline.size / 3).map { Triple(outline[it * 3], outline[it * 3 + 1], outline[it * 3 + 2]) }.toSet()
    for (i in 0 until fill.coords.size / 3) {
      assertTrue(Triple(fill.coords[i * 3], fill.coords[i * 3 + 1], fill.coords[i * 3 + 2]) in outlinePoints)
    }
    assertEquals(2, buildRenderScene(geometry).fills.single().triangleCount)
  }

  @Test
  fun `LWDISPLAY из HEADER доходит до сцены, без переменной веса включены`() {
    fun scene(vararg header: String) = buildRenderScene(
      readGeometry(
        "0", "SECTION", "2", "HEADER", *header, "0", "ENDSEC",
        "0", "SECTION", "2", "ENTITIES", "0", "LINE", "8", "L", "10", "0", "20", "0", "11", "1", "21", "1", "0", "ENDSEC", "0", "EOF",
      ),
    )
    assertEquals(false, scene("9", "\$LWDISPLAY", "290", "0").lineWeightDisplay)
    assertEquals(true, scene("9", "\$LWDISPLAY", "290", "1").lineWeightDisplay)
    assertEquals(true, scene().lineWeightDisplay)
  }

  @Test
  fun `PDMODE и PDSIZE из HEADER доходят до сцены`() {
    val withMode = readGeometry(
      "0", "SECTION", "2", "HEADER", "9", "\$PDMODE", "70", "35", "9", "\$PDSIZE", "40", "-8.0", "0", "ENDSEC",
      "0", "SECTION", "2", "ENTITIES", "0", "POINT", "8", "P", "10", "1", "20", "2", "30", "0", "0", "ENDSEC", "0", "EOF",
    )
    val scene = buildRenderScene(withMode)
    assertEquals(35, scene.pointMode)
    assertEquals(-8f, scene.pointSize)
    assertEquals(1, scene.points.size)

    val without = readGeometry(
      "0", "SECTION", "2", "ENTITIES", "0", "POINT", "8", "P", "10", "1", "20", "2", "30", "0", "0", "ENDSEC", "0", "EOF",
    )
    assertEquals(POINT_MODE_UNSET, buildRenderScene(without).pointMode)
  }

  @Test
  fun `угол значка POINT (код 50) доходит до сцены и учитывает поворот вставки`() {
    val geometry = readGeometry(
      *dxf(
        section("BLOCKS",
          "0", "BLOCK", "2", "PT", "10", "0", "20", "0", "30", "0",
          "0", "POINT", "8", "P", "10", "0", "20", "0", "30", "0", "50", "15",
          "0", "ENDBLK",
        ),
        section("ENTITIES",
          "0", "POINT", "8", "P", "10", "0", "20", "0", "30", "0",
          "0", "POINT", "8", "P", "10", "1", "20", "0", "30", "0", "50", "45",
          "0", "INSERT", "8", "P", "2", "PT", "10", "2", "20", "0", "30", "0", "50", "30",
        ),
      )
    )
    for ((point, expected) in geometry.points.zip(listOf(0.0, 45.0, 45.0))) assertEquals(expected, point.rotationDeg, 1e-9)
    val scene = buildRenderScene(geometry)
    // ось X значка без угла — ось X чертежа; 45° — между X и севером (−Z сцены)
    assertEquals(1f, scene.points[0].alongX, 1e-6f)
    assertEquals(0f, scene.points[0].alongZ, 1e-6f)
    assertEquals(sqrt(0.5).toFloat(), scene.points[1].alongX, 1e-6f)
    assertEquals(-sqrt(0.5).toFloat(), scene.points[1].alongZ, 1e-6f)
  }

  private fun quarterSpline(weights: List<Double>?): SceneGeometry {
    val body = mutableListOf("0", "SPLINE", "8", "S", "70", "8", "71", "2", "72", "6", "73", "3")
    for (k in listOf(0, 0, 0, 1, 1, 1)) body += listOf("40", "$k")
    weights?.forEach { body += listOf("41", "$it") }
    for ((x, y) in listOf(10 to 0, 10 to 10, 0 to 10)) body += listOf("10", "$x", "20", "$y", "30", "0")
    return readGeometry(*dxf(section("ENTITIES", *body.toTypedArray())))
  }

  private fun radii(geometry: SceneGeometry): List<Double> {
    val coords = geometry.lines.single().coords
    return (0 until coords.size / 3).map { hypot(coords[it * 3], coords[it * 3 + 1]) }
  }

  @Test
  fun `рациональная четверть окружности лежит на окружности`() {
    val r = radii(quarterSpline(listOf(1.0, kotlin.math.sqrt(0.5), 1.0)))
    assertTrue(r.all { abs(it - 10.0) < 1e-2 }, "радиусы: ${r.maxOf { abs(it - 10.0) }}")
  }

  @Test
  fun `сплайн без весов и с единичными весами не меняется`() {
    val plain = quarterSpline(null).lines.single().coords
    val ones = quarterSpline(listOf(1.0, 1.0, 1.0)).lines.single().coords
    assertClose(plain, ones)
    // без весов середина параболы заметно дальше от центра, чем окружность
    assertTrue(radii(quarterSpline(null)).max() > 10.5)
  }

  @Test
  fun `HELIX рисуется по данным AcDbSpline, коды AcDbHelix не мешают`() {
    // 3 витка радиуса 5 с шагом 2: по кубической Безье на четверть витка
    val r = 5.0
    val segments = 12
    val k = r * 4.0 / 3.0 * tan(PI / 8)
    val dz = 2.0 / 4
    val body = mutableListOf("0", "HELIX", "100", "AcDbEntity", "8", "H", "100", "AcDbSpline", "70", "0", "71", "3")
    body += listOf("40", "0", "40", "0", "40", "0", "40", "0")
    for (i in 1 until segments) repeat(3) { body += listOf("40", "$i") }
    body += List(4) { listOf("40", "$segments") }.flatten()
    fun point(x: Double, y: Double, z: Double) { body += listOf("10", "$x", "20", "$y", "30", "$z") }
    for (i in 0 until segments) {
      val a0 = i * PI / 2
      val a1 = a0 + PI / 2
      val z0 = i * dz
      if (i == 0) point(r * cos(a0), r * sin(a0), z0)
      point(r * cos(a0) - k * sin(a0), r * sin(a0) + k * cos(a0), z0 + dz / 3)
      point(r * cos(a1) + k * sin(a1), r * sin(a1) - k * cos(a1), z0 + dz * 2 / 3)
      point(r * cos(a1), r * sin(a1), z0 + dz)
    }
    // AcDbHelix: основание оси, начальная точка, ось, радиус, витки, шаг — прочитанные как сплайн, испортили бы кривую
    body += listOf("100", "AcDbHelix", "90", "29", "91", "63", "10", "0", "20", "0", "30", "0", "11", "5", "21", "0",
      "31", "0", "12", "0", "22", "0", "32", "1", "40", "5", "41", "3", "42", "2", "290", "1", "280", "1")
    val geometry = readGeometry(*dxf(section("ENTITIES", *body.toTypedArray())))
    val coords = geometry.lines.single().coords
    val n = coords.size / 3
    val radii = (0 until n).map { hypot(coords[it * 3], coords[it * 3 + 1]) }
    assertTrue(radii.all { abs(it - r) < r * 0.01 }, "отклонение радиуса: ${radii.maxOf { abs(it - r) }}")
    assertEquals(0.0, coords[2], 1e-9)
    assertEquals(6.0, coords[(n - 1) * 3 + 2], 1e-9)
  }

  /** SPLINE только с опорными точками на окружности радиуса 10 (углы в градусах) и касательными [tangents] (12, 13). */
  private fun fitSpline(angles: List<Double>, tangents: Pair<Pair<Double, Double>, Pair<Double, Double>>?, closed: Boolean = false): SceneGeometry {
    val body = mutableListOf("0", "SPLINE", "8", "S", "70", if (closed) "9" else "8", "71", "3", "72", "0", "73", "0", "74", "${angles.size}")
    tangents?.let { (s, e) ->
      body += listOf("12", "${s.first}", "22", "${s.second}", "32", "0", "13", "${e.first}", "23", "${e.second}", "33", "0")
    }
    for (a in angles) {
      val r = Math.toRadians(a)
      body += listOf("11", "${10 * kotlin.math.cos(r)}", "21", "${10 * kotlin.math.sin(r)}", "31", "0")
    }
    return readGeometry(*dxf(section("ENTITIES", *body.toTypedArray())))
  }

  private fun assertPassesThrough(coords: DoubleArray, angles: List<Double>) {
    for (a in angles) {
      val r = Math.toRadians(a)
      val x = 10 * kotlin.math.cos(r)
      val y = 10 * kotlin.math.sin(r)
      val nearest = (0 until coords.size / 3).minOf { hypot(coords[it * 3] - x, coords[it * 3 + 1] - y) }
      assertTrue(nearest < 1e-9, "точка $a°: $nearest")
    }
  }

  @Test
  fun `сплайн по опорным точкам проходит через них и лежит на окружности`() {
    val angles = listOf(0.0, 45.0, 90.0, 135.0, 180.0)
    // касательные не единичные: важна только их сторона
    val g = fitSpline(angles, (0.0 to 2.0) to (0.0 to -3.0))
    val coords = g.lines.single().coords
    assertTrue(coords.size / 3 > 20, "кривая, а не ломаная по 5 точкам")
    assertPassesThrough(coords, angles)
    val r = radii(g)
    assertTrue(r.all { abs(it - 10.0) < 0.1 }, "отклонение от окружности: ${r.maxOf { abs(it - 10.0) }}")
    // касательная на концах — направление от крайней контрольной точки к соседней: совпадает с 12 и 13
    val fit = DoubleArray(angles.size * 3)
    for ((i, a) in angles.withIndex()) {
      fit[i * 3] = 10 * kotlin.math.cos(Math.toRadians(a))
      fit[i * 3 + 1] = 10 * kotlin.math.sin(Math.toRadians(a))
    }
    val c = interpolateFitPoints(fit, doubleArrayOf(0.0, 2.0, 0.0), doubleArrayOf(0.0, -3.0, 0.0), closed = false)!!.control
    val n = c.size
    val start = hypot(c[3] - c[0], c[4] - c[1])
    assertEquals(0.0, (c[3] - c[0]) / start, 1e-12)
    assertEquals(1.0, (c[4] - c[1]) / start, 1e-12)
    val end = hypot(c[n - 3] - c[n - 6], c[n - 2] - c[n - 5])
    assertEquals(0.0, (c[n - 3] - c[n - 6]) / end, 1e-12)
    assertEquals(-1.0, (c[n - 2] - c[n - 5]) / end, 1e-12)
    // и отрисованная кривая отходит от концов вдоль них (хорда первого шага — в пределах половины шага по углу)
    val m = coords.size
    assertTrue(abs((coords[3] - coords[0]) / hypot(coords[3] - coords[0], coords[4] - coords[1])) < 0.06)
    assertTrue(abs((coords[m - 3] - coords[m - 6]) / hypot(coords[m - 3] - coords[m - 6], coords[m - 2] - coords[m - 5])) < 0.06)
  }

  @Test
  fun `сплайн по опорным точкам без касательных — естественные концы, замкнутый — периодический`() {
    val open = listOf(0.0, 45.0, 90.0, 135.0, 180.0)
    val natural = fitSpline(open, null).lines.single().coords
    assertTrue(natural.size / 3 > 20)
    assertPassesThrough(natural, open)

    val ring = (0 until 8).map { it * 45.0 }
    val g = fitSpline(ring, null, closed = true)
    val coords = g.lines.single().coords
    assertPassesThrough(coords, ring)
    val r = radii(g)
    assertTrue(r.all { abs(it - 10.0) < 0.1 }, "отклонение от окружности: ${r.maxOf { abs(it - 10.0) }}")
  }

  @Test
  fun `при контрольных и опорных точках сплайн строится по контрольным`() {
    val plain = quarterSpline(null).lines.single().coords
    val body = mutableListOf("0", "SPLINE", "8", "S", "70", "8", "71", "2", "72", "6", "73", "3", "74", "2")
    for (k in listOf(0, 0, 0, 1, 1, 1)) body += listOf("40", "$k")
    for ((x, y) in listOf(10 to 0, 10 to 10, 0 to 10)) body += listOf("10", "$x", "20", "$y", "30", "0")
    body += listOf("11", "10", "21", "0", "31", "0", "11", "0", "21", "10", "31", "0")
    assertClose(plain, readGeometry(*dxf(section("ENTITIES", *body.toTypedArray()))).lines.single().coords)
  }

  private fun lineTypes() = section("TABLES",
    "0", "TABLE", "2", "LTYPE",
    "0", "LTYPE", "2", "CONTINUOUS", "70", "0", "72", "65", "73", "0", "40", "0.0",
    "0", "LTYPE", "2", "DASHED", "70", "0", "72", "65", "73", "2", "40", "3.0", "49", "2.0", "74", "0", "49", "-1.0", "74", "0",
    "0", "LTYPE", "2", "DOTTED", "70", "0", "72", "65", "73", "2", "40", "1.0", "49", "0.0", "74", "0", "49", "-1.0", "74", "0",
    // как GAS_LINE из acad.lin ×10: штрих 5, пробел 2 с текстом в конце (высота 1, смещения -1 и -0,5), пробел 2,5
    "0", "LTYPE", "2", "GAS", "70", "0", "72", "65", "73", "3", "40", "9.5", "49", "5.0", "74", "0",
    "49", "-2.0", "74", "2", "75", "0", "340", "1A", "46", "1.0", "50", "0.0", "44", "-1.0", "45", "-0.5", "9", "ГАЗ",
    "49", "-2.5", "74", "0",
    // то же с абсолютным поворотом 90° и фигурой SHX во втором пробеле
    "0", "LTYPE", "2", "GAS_ABS", "70", "0", "72", "65", "73", "3", "40", "9.5", "49", "5.0", "74", "0",
    "49", "-2.0", "74", "3", "75", "0", "340", "1A", "46", "1.0", "50", "1.5707963267948966", "44", "0", "45", "0", "9", "ГАЗ",
    "49", "-2.5", "74", "4", "75", "130", "340", "1B", "46", "1.0", "50", "0", "44", "0", "45", "0",
    "0", "ENDTAB",
    "0", "TABLE", "2", "STYLE",
    "0", "STYLE", "5", "1A", "2", "NARROW", "70", "0", "40", "0", "41", "0.8", "50", "0",
    "0", "ENDTAB",
    "0", "TABLE", "2", "LAYER",
    "0", "LAYER", "2", "L_DASH", "70", "0", "62", "7", "6", "DASHED",
    "0", "ENDTAB")

  private fun line(length: Int, vararg extra: String) =
    arrayOf("0", "LINE", "8", "L_DASH", *extra, "10", "0", "20", "0", "30", "0", "11", "$length", "21", "0", "31", "0")

  private fun dashLengths(geometry: SceneGeometry): List<Double> = geometry.lines.flatMap { batch ->
    (0 until batch.segmentCount).map { i ->
      hypot(batch.coords[i * 6 + 3] - batch.coords[i * 6], batch.coords[i * 6 + 4] - batch.coords[i * 6 + 1])
    }
  }

  @Test
  fun `штриховая линия режется на штрихи, фаза идёт вдоль ломаной`() {
    // период 3: штрих 2, пробел 1; на длине 30 — 10 штрихов
    val geometry = readGeometry(*dxf(lineTypes(), section("ENTITIES", *line(30, "6", "DASHED"))))
    val lengths = dashLengths(geometry)
    assertEquals(10, lengths.size)
    assertTrue(lengths.all { abs(it - 2.0) < 1e-9 })

    // PLINEGEN (70 & 128), две вершины подряд по 1,5: штрих 2 пересекает вершину, но фаза не сбрасывается
    val polyline = readGeometry(*dxf(lineTypes(), section("ENTITIES",
      "0", "LWPOLYLINE", "8", "L_DASH", "6", "DASHED", "90", "3", "70", "128",
      "10", "0", "20", "0", "10", "1.5", "20", "0", "10", "1.5", "20", "1.5")))
    // 0..1.5 штрих, 1.5..2 штрих (продолжение), 2..3 пробел → второй штрих 3..5 не помещается, остаётся сегмент 1.5
    assertEquals(listOf(1.5, 0.5), dashLengths(polyline).map { Math.round(it * 10) / 10.0 })
  }

  @Test
  fun `без PLINEGEN паттерн начинается заново в каждой вершине`() {
    fun polyline(type: String, flags: String) = readGeometry(*dxf(lineTypes(), section("ENTITIES",
      "0", type, "8", "L_DASH", "6", "DASHED", "70", flags,
      *if (type == "LWPOLYLINE") {
        arrayOf("90", "2", "10", "0", "20", "0", "10", "2.5", "20", "0", "10", "2.5", "20", "2.5")
      } else {
        arrayOf("10", "0", "20", "0", "30", "0",
          "0", "VERTEX", "8", "L_DASH", "10", "0", "20", "0", "30", "0",
          "0", "VERTEX", "8", "L_DASH", "10", "2.5", "20", "0", "30", "0",
          "0", "VERTEX", "8", "L_DASH", "10", "2.5", "20", "2.5", "30", "0",
          "0", "SEQEND")
      })))
    // два сегмента по 2,5: в каждом штрих 2 с начала и пробел 0,5
    assertEquals(listOf(2.0, 2.0), dashLengths(polyline("LWPOLYLINE", "0")).map { Math.round(it * 10) / 10.0 })
    assertEquals(listOf(2.0, 2.0), dashLengths(polyline("POLYLINE", "0")).map { Math.round(it * 10) / 10.0 })
    // с PLINEGEN: 0..2 штрих, 2..3 пробел (через вершину), 3..5 штрих — во втором сегменте 0,5..2,5
    assertEquals(listOf(2.0, 2.0), dashLengths(polyline("LWPOLYLINE", "128")).map { Math.round(it * 10) / 10.0 })
    val continuous = polyline("POLYLINE", "128").lines.single().coords
    assertEquals(0.5, continuous[7], 1e-9)
    val restarted = polyline("POLYLINE", "0").lines.single().coords
    assertEquals(0.0, restarted[7], 1e-9)
  }

  @Test
  fun `текст в типе линии - пробел под него и подпись вдоль линии`() {
    // длина 19: два периода по 9,5 → штрихи 0..5 и 9,5..14,5, текст в 7 и 16,5 со смещениями (-1; -0,5)
    val geometry = readGeometry(*dxf(lineTypes(), section("ENTITIES", *line(19, "6", "GAS"))))
    assertEquals(listOf(5.0, 5.0), dashLengths(geometry).map { Math.round(it * 10) / 10.0 })
    val labels = geometry.labels
    assertEquals(listOf("ГАЗ", "ГАЗ"), labels.map { it.text })
    assertEquals(listOf(6.0, 15.5), labels.map { Math.round(it.x * 1e6) / 1e6 })
    assertTrue(labels.all { abs(it.y + 0.5) < 1e-9 && it.height == 1.0 && it.rotationDeg == 0.0 })
    // стиль по handle 340: ширина букв 0,8
    assertTrue(labels.all { it.widthFactor == 0.8 })

    // вертикальная линия: подпись поворачивается вдоль неё, смещение поперёк — вправо от направления (+x)
    val vertical = readGeometry(*dxf(lineTypes(), section("ENTITIES",
      "0", "LINE", "8", "L_DASH", "6", "GAS", "10", "0", "20", "0", "30", "0", "11", "0", "21", "10", "31", "0")))
    val label = vertical.labels.single()
    assertEquals(90.0, label.rotationDeg, 1e-9)
    assertEquals(0.5, label.x, 1e-9)
    assertEquals(6.0, label.y, 1e-9)

    // масштаб паттерна растит и пробелы, и текст
    val scaled = readGeometry(*dxf(lineTypes(), section("ENTITIES", *line(38, "6", "GAS", "48", "2")))).labels
    assertEquals(listOf(12.0, 31.0), scaled.map { Math.round(it.x * 1e6) / 1e6 })
    assertTrue(scaled.all { it.height == 2.0 && abs(it.y + 1.0) < 1e-9 })
  }

  @Test
  fun `текст с абсолютным поворотом, фигура SHX оставляет пробел без подписи`() {
    val geometry = readGeometry(*dxf(lineTypes(), section("ENTITIES",
      "0", "LINE", "8", "L_DASH", "6", "GAS_ABS", "10", "0", "20", "0", "30", "0", "11", "-19", "21", "0", "31", "0")))
    assertEquals(listOf(5.0, 5.0), dashLengths(geometry).map { Math.round(it * 10) / 10.0 })
    // линия влево, но поворот абсолютный — 90° от оси X
    assertEquals(listOf(90.0, 90.0), geometry.labels.map { Math.round(it.rotationDeg * 1e6) / 1e6 })
    assertEquals(listOf(-7.0, -16.5), geometry.labels.map { Math.round(it.x * 1e6) / 1e6 })
  }

  @Test
  fun `тип линии слоя, BYLAYER и CONTINUOUS`() {
    val byLayer = readGeometry(*dxf(lineTypes(), section("ENTITIES", *line(30))))
    assertEquals(10, byLayer.lines.sumOf { it.segmentCount })
    val continuous = readGeometry(*dxf(lineTypes(), section("ENTITIES", *line(30, "6", "CONTINUOUS"))))
    assertEquals(1, continuous.lines.sumOf { it.segmentCount })
    val unknown = readGeometry(*dxf(lineTypes(), section("ENTITIES", *line(30, "6", "NO_SUCH"))))
    assertEquals(1, unknown.lines.sumOf { it.segmentCount })
  }

  @Test
  fun `масштаб типа линии - LTSCALE, код 48 и масштаб блока`() {
    val scaled = readGeometry(*dxf(lineTypes(), section("ENTITIES", *line(30, "6", "DASHED", "48", "3"))))
    assertEquals(4, dashLengths(scaled).size)

    val header = section("HEADER", "9", "\$LTSCALE", "40", "0.5")
    assertEquals(20, readGeometry(*dxf(header, lineTypes(), section("ENTITIES", *line(30, "6", "DASHED")))).lines.sumOf { it.segmentCount })

    // BYBLOCK: тип берётся у вхождения, паттерн растёт вместе с масштабом блока
    val blocks = section("BLOCKS", "0", "BLOCK", "8", "0", "2", "B", "70", "0", "10", "0", "20", "0", "30", "0",
      "0", "LINE", "8", "0", "6", "BYBLOCK", "10", "0", "20", "0", "30", "0", "11", "10", "21", "0", "31", "0",
      "0", "ENDBLK")
    fun inserted(scale: String) = readGeometry(*dxf(lineTypes(), blocks, section("ENTITIES",
      "0", "INSERT", "8", "L_DASH", "6", "DASHED", "2", "B", "10", "0", "20", "0", "30", "0", "41", scale, "42", scale, "43", scale)))
    assertEquals(4, inserted("1").lines.sumOf { it.segmentCount })
    assertEquals(4, inserted("3").lines.sumOf { it.segmentCount })
  }

  @Test
  fun `слишком мелкий паттерн рисуется сплошным`() {
    // период 0,0015 на длине 300 — 400 000 штрихов, больше лимита на ломаную: сплошная
    val tiny = readGeometry(*dxf(lineTypes(), section("ENTITIES", *line(300, "6", "DASHED", "48", "0.0005"))))
    assertEquals(1, tiny.lines.sumOf { it.segmentCount })
    // паттерн с текстом — так же, и без подписей
    val texted = readGeometry(*dxf(lineTypes(), section("ENTITIES", *line(300_000, "6", "GAS", "48", "0.01"))))
    assertEquals(1, texted.lines.sumOf { it.segmentCount })
    assertTrue(texted.labels.isEmpty())
    // 2 000 000 ед. при периоде 0,6 — лимит штрихов на ломаную
    val huge = readGeometry(*dxf(lineTypes(), section("ENTITIES", *line(2_000_000, "6", "DASHED", "48", "0.2"))))
    assertEquals(1, huge.lines.sumOf { it.segmentCount })
  }

  @Test
  fun `точки паттерна дают короткие штрихи`() {
    val geometry = readGeometry(*dxf(lineTypes(), section("ENTITIES", *line(10, "6", "DOTTED"))))
    val lengths = dashLengths(geometry)
    assertEquals(10, lengths.size)
    assertTrue(lengths.all { it < 0.1 })
  }

  @Test
  fun `лимит отрезков действует на штрихи`() {
    val document = DxfDocumentReader(dxf(lineTypes(), section("ENTITIES", *line(30, "6", "DASHED")))
      .joinToString("\n", postfix = "\n").byteInputStream().asByteInput()).read()
    val geometry = DxfFlattener(document, maxSegments = 4).flatten()
    assertTrue(geometry.truncated)
    assertEquals(4, geometry.lines.sumOf { it.segmentCount })
  }

  // 3DFACE с углами (x, y) на отметке 0; [extra] — доп. пары (код 70 и т. п.)
  private fun face(vararg corners: Pair<Int, Int>, extra: Array<String> = emptyArray()): Array<String> {
    val pairs = ArrayList(listOf("0", "3DFACE", "8", "F", *extra))
    corners.forEachIndexed { i, (x, y) -> pairs += listOf("1$i", "$x", "2$i", "$y", "3$i", "0") }
    return pairs.toTypedArray()
  }

  // рёбра каркаса как пары углов (x, y) по возрастанию
  private fun wireEdges(geometry: SceneGeometry): Set<Set<Pair<Int, Int>>> {
    val mesh = geometry.meshes.single()
    val edges = buildRenderScene(geometry).surfaces.single().edges
    fun corner(v: Int) = mesh.coords[v * 3].toInt() to mesh.coords[v * 3 + 1].toInt()
    return (0 until edges.size / 2).map { setOf(corner(edges[it * 2]), corner(edges[it * 2 + 1])) }.toSet()
  }

  @Test
  fun `четырёхугольный 3DFACE — два треугольника без диагонали в каркасе`() {
    val square = readGeometry(*dxf(section("ENTITIES", *face(0 to 0, 1 to 0, 1 to 1, 0 to 1))))
    assertEquals(2, square.meshes.single().triangleCount)
    assertEquals(4, wireEdges(square).size)
    assertTrue(setOf(0 to 0, 1 to 1) !in wireEdges(square))
  }

  @Test
  fun `3DFACE с 4-м углом, равным 3-му или 1-му, — один треугольник`() {
    val sameThird = readGeometry(*dxf(section("ENTITIES", *face(0 to 0, 1 to 0, 1 to 1, 1 to 1))))
    assertEquals(1, sameThird.meshes.single().triangleCount)
    assertEquals(3, wireEdges(sameThird).size)
    val sameFirst = readGeometry(*dxf(section("ENTITIES", *face(0 to 0, 1 to 0, 1 to 1, 0 to 0))))
    assertEquals(1, sameFirst.meshes.single().triangleCount)
    // у треугольного TIN маски рёбер нет
    assertEquals(null, sameFirst.meshes.single().edgeMask)
    // 4-го угла в записи нет
    val noFourth = readGeometry(*dxf(section("ENTITIES", *face(0 to 0, 1 to 0, 1 to 1))))
    assertEquals(1, noFourth.meshes.single().triangleCount)
  }

  @Test
  fun `невидимые рёбра 3DFACE не попадают в каркас`() {
    // 70 = 5: скрыты 1-2 и 3-4
    val square = readGeometry(*dxf(section("ENTITIES", *face(0 to 0, 1 to 0, 1 to 1, 0 to 1, extra = arrayOf("70", "5")))))
    assertEquals(2, square.meshes.single().triangleCount)
    assertEquals(setOf(setOf(1 to 0, 1 to 1), setOf(0 to 1, 0 to 0)), wireEdges(square))
    // треугольник (4-й = 3-му), 70 = 8: скрыто 4-1, то есть 3-1
    val triangle = readGeometry(*dxf(section("ENTITIES", *face(0 to 0, 1 to 0, 1 to 1, 1 to 1, extra = arrayOf("70", "8")))))
    assertEquals(setOf(setOf(0 to 0, 1 to 0), setOf(1 to 0, 1 to 1)), wireEdges(triangle))
  }

  @Test
  fun `четырёхугольный 3DFACE в блоке`() {
    val geometry = readGeometry(
      *dxf(
        section("BLOCKS",
          "0", "BLOCK", "2", "B", "10", "0", "20", "0", "30", "0",
          *face(0 to 0, 1 to 0, 1 to 1, 0 to 1, extra = arrayOf("70", "5")),
          "0", "ENDBLK",
        ),
        section("ENTITIES", "0", "INSERT", "8", "0", "2", "B", "10", "10", "20", "0", "30", "0"),
      )
    )
    assertEquals(2, geometry.meshes.single().triangleCount)
    assertEquals(setOf(setOf(11 to 0, 11 to 1), setOf(10 to 1, 10 to 0)), wireEdges(geometry))
  }

  @Test
  fun `рост маски рёбер не скрывает рёбра треугольников без маски`() {
    val builder = MeshBuilder("F")
    // 70 = 5 — заводит маску
    builder.addFace(doubleArrayOf(0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 1.0, 1.0, 0.0, 0.0, 1.0, 0.0), 5)
    // много треугольников без маски (как polyface), чтобы массивы выросли
    for (i in 0 until 3000) builder.addTriangle(i.toDouble(), 5.0, 0.0, i + 1.0, 5.0, 0.0, i.toDouble(), 6.0, 0.0)
    builder.addFace(doubleArrayOf(0.0, 9.0, 0.0, 1.0, 9.0, 0.0, 1.0, 10.0, 0.0, 0.0, 10.0, 0.0), 5)
    val mask = builder.build().edgeMask!!
    assertEquals(3004, mask.size)
    for (i in 2 until 3002) assertEquals(7, mask[i].toInt(), "треугольник $i")
  }

  private fun assertClose(expected: DoubleArray, actual: DoubleArray) {
    assertEquals(expected.size, actual.size, "размер")
    for (i in expected.indices) assertEquals(expected[i], actual[i], 1e-9, "элемент $i")
  }
}
