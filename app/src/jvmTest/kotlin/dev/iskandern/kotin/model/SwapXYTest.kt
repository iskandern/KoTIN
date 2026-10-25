package dev.iskandern.kotin.model

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class SwapXYTest {

  @Test
  fun swapsAxesAndFlipsWinding() {
    val mesh = SceneMesh("m", doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0), intArrayOf(0, 1, 2), 1.0, 2.0, 3.0, 7.0, 8.0, 9.0, byteArrayOf(1))
    val geometry = SceneGeometry(
      meshes = listOf(mesh),
      lines = listOf(LineBatch("l", 0, false, doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0))),
      fills = emptyList(), labels = emptyList(),
      points = listOf(PointMark("p", 0, 10.0, 20.0, 30.0, 30.0)),
      layers = emptyList(), truncated = false,
    )
    val swapped = geometry.swappedXY()
    assertContentEquals(doubleArrayOf(2.0, 1.0, 3.0, 5.0, 4.0, 6.0), swapped.lines[0].coords)
    assertContentEquals(doubleArrayOf(2.0, 1.0, 3.0, 5.0, 4.0, 6.0, 8.0, 7.0, 9.0), swapped.meshes[0].coords)
    assertContentEquals(intArrayOf(0, 2, 1), swapped.meshes[0].triangles)
    // ребро a-b стало c-a
    assertEquals(4, swapped.meshes[0].edgeMask!![0].toInt())
    assertEquals(2.0, swapped.meshes[0].minX)
    assertEquals(20.0, swapped.points[0].x)
    assertEquals(10.0, swapped.points[0].y)
    assertEquals(60.0, swapped.points[0].rotationDeg)
    // двойная перестановка возвращает исходное
    assertContentEquals(geometry.lines[0].coords, swapped.swappedXY().lines[0].coords)
  }
}
