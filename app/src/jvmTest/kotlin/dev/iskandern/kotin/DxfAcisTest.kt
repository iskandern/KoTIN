package dev.iskandern.kotin

import dev.iskandern.kotin.model.SceneGeometry
import dev.iskandern.kotin.model.SceneMesh
import dev.iskandern.kotin.model.MeshBuilder
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 3DSOLID, REGION, BODY: плоские грани из SAT (файлы — `scripts/gen_test_dxf.py`, task48). */
class DxfAcisTest {

  private val sat: SceneGeometry by lazy { readGeometry(File(testDxfDir, "acis-sat.dxf")) }

  private fun SceneMesh.area(filter: (DoubleArray) -> Boolean = { true }): Double {
    var sum = 0.0
    for (t in 0 until triangleCount) {
      val p = DoubleArray(9) { coords[triangles[t * 3 + it / 3] * 3 + it % 3] }
      if (!filter(p)) continue
      val ax = p[3] - p[0]; val ay = p[4] - p[1]; val az = p[5] - p[2]
      val bx = p[6] - p[0]; val by = p[7] - p[1]; val bz = p[8] - p[2]
      sum += hypot(hypot(ay * bz - az * by, az * bx - ax * bz), ax * by - ay * bx) / 2
    }
    return sum
  }

  // треугольник лежит в плоскости z = level
  private fun atZ(level: Double): (DoubleArray) -> Boolean = { p -> abs(p[2] - level) < 1e-9 && abs(p[5] - level) < 1e-9 && abs(p[8] - level) < 1e-9 }

  @Test
  fun `куб из 3DSOLID и BODY - по 12 треугольников, каркас без диагоналей`() {
    val solids = sat.meshes.single { it.layer == "Solids" }
    // куб 10 и куб 6
    assertEquals(24, solids.triangleCount)
    assertEquals(6 * 100.0 + 6 * 36.0, solids.area(), 1e-6)
    // у каждой грани свои вершины: 6 граней × 4 угла × 2 куба
    assertEquals(48, solids.vertexCount)
    // в каркасе по 2 ребра из 3 у каждого треугольника: диагональ квадрата скрыта
    val mask = solids.edgeMask!!
    assertTrue(mask.all { Integer.bitCount(it.toInt()) == 2 }, mask.joinToString())
    // transform тела: куб 10 стоит в 0…10
    assertEquals(0.0, solids.minX, 1e-6)
    assertEquals(0.0, solids.minZ, 1e-6)
    assertEquals(10.0, solids.maxZ, 1e-6)
  }

  @Test
  fun `плита с отверстием - верхняя и нижняя грани с дыркой`() {
    val plates = sat.meshes.single { it.layer == "Plates" }
    val hole = PI * 16
    // плита в модели: x 20…50, y 0…20, отверстие R4 в (35, 10)
    val top = plates.area { atZ(4.0)(it) && it[0] < 55 }
    val bottom = plates.area { atZ(0.0)(it) && it[0] < 55 }
    assertEquals(600 - hole, top, hole * 0.01)
    assertEquals(600 - hole, bottom, hole * 0.01)
    // ни один треугольник верха не заходит в отверстие
    for (t in 0 until plates.triangleCount) {
      val p = DoubleArray(9) { plates.coords[plates.triangles[t * 3 + it / 3] * 3 + it % 3] }
      if (!atZ(4.0)(p) || p[0] > 55) continue
      val cx = (p[0] + p[3] + p[6]) / 3
      val cy = (p[1] + p[4] + p[7]) / 3
      assertTrue(hypot(cx - 35, cy - 10) > 3.5, "треугольник в отверстии: $cx, $cy")
    }
    // боковые грани: 2 × (30 + 20) × 4 (стенка отверстия — в DxfAcisConeTest)
    val sides = plates.area { p ->
      !(atZ(4.0)(p) || atZ(0.0)(p)) && (2 until 9 step 3).all { p[it] <= 4.0 } && hypot((p[0] + p[3] + p[6]) / 3 - 35, (p[1] + p[4] + p[7]) / 3 - 10) > 5
    }
    assertEquals(400.0, sides, 1e-6)
    // цилиндр отверстия рисуется с KoTIN-94
    assertEquals(0, sat.hiddenBodyFaces)
    assertEquals(0, sat.unreadBodies)
  }

  @Test
  fun `плита в блоке - поворот и подъём вставки`() {
    val plates = sat.meshes.single { it.layer == "Plates" }
    // вставка в (60, 0, 15): низ плиты на 15, верх на 19
    assertEquals(19.0, plates.maxZ, 1e-9)
    val lifted = plates.area { atZ(19.0)(it) }
    assertEquals(600 - PI * 16, lifted, PI * 16 * 0.01)
  }

  @Test
  fun `REGION - грань с квадратным отверстием, каркас по обеим петлям`() {
    val region = sat.meshes.single { it.layer == "Regions" }
    assertEquals(400.0 - 64.0, region.area(), 1e-9)
    // 8 рёбер петель видны, каждое ровно в одном треугольнике
    val visible = region.edgeMask!!.sumOf { Integer.bitCount(it.toInt()) }
    assertEquals(8, visible)
  }

  @Test
  fun `сетка - вершины группы не склеиваются с прежними`() {
    val builder = MeshBuilder("A")
    builder.addTriangle(0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0, 0.0)
    builder.beginGroup()
    builder.addTriangle(0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0)
    builder.addTriangle(0.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 1.0, 1.0)
    builder.endGroup()
    builder.addTriangle(0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 1.0, 1.0, 0.0)
    // 3 первых + 4 своих у группы + 1 новая после неё
    assertEquals(8, builder.build().vertexCount)
  }
}
