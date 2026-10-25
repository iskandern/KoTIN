package dev.iskandern.kotin

import dev.iskandern.kotin.scene.buildRenderScene
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HomeFrameTest {

  private fun line(x1: Double, y1: Double, x2: Double, y2: Double) = arrayOf(
    "0", "LINE", "8", "A", "10", "$x1", "20", "$y1", "30", "0", "11", "$x2", "21", "$y2", "31", "0",
  )

  private fun point(x: Double, y: Double) = arrayOf("0", "POINT", "8", "A", "10", "$x", "20", "$y", "30", "0")

  private fun doc(entities: List<Array<String>>) = arrayOf(
    "0", "SECTION", "2", "ENTITIES", *entities.flatMap { it.toList() }.toTypedArray(), "0", "ENDSEC", "0", "EOF",
  )

  // 100 отрезков: решётка 50 × 50 у (1000, 2000)
  private val grid = (0 until 50).map { i -> line(1000.0 + i, 2000.0, 1000.0 + i, 2050.0) } +
    (0 until 50).map { j -> line(1000.0, 2000.0 + j, 1050.0, 2000.0 + j) }

  private val tanHalf = tan(22.5 * PI / 180).toFloat()

  @Test
  fun `далёкий отрезок не сжимает вид — кадр по основной массе`() {
    val scene = buildRenderScene(readGeometry(*doc(grid + listOf(line(90000.0, 2000.0, 90010.0, 2000.0)))))
    val home = assertNotNull(scene.home)
    // кадр — решётка 50 × 50, а не 89 км
    assertEquals(25f, home.halfX, 0.01f)
    assertEquals(25f, home.halfZ, 0.01f)
    val eye = homeEye(scene, 1f)
    assertEquals(home.x, eye.x)
    assertEquals(home.z, eye.z)
    assertTrue(eye.y * tanHalf in 25f..40f, "${eye.y}")
    val pose = homePose(scene, 1f)
    assertEquals(home.x, pose.pivotX)
    assertEquals(home.z, pose.pivotZ)
    // центр и размер сцены прежние — по всем объектам
    assertTrue(scene.halfX > 40000f)
    // далёкий отрезок — своя группа, основная масса — свой контур
    assertEquals(1, home.outliers.size)
    assertEquals(1, home.outliers[0].count)
    assertTrue(home.outliers[0].outline.hull.all { abs(it) > 40000f || abs(it) < 100f })
    assertTrue(assertNotNull(home.outline).hull.let { h -> (0 until h.size / 2).all { abs(h[it * 2] - home.x) < 30f } })
  }

  @Test
  fun `без выбросов вид прежний`() {
    val scene = buildRenderScene(readGeometry(*doc(grid + listOf(point(1025.0, 2025.0)))))
    assertNull(scene.home)
    val eye = homeEye(scene, 1f)
    assertEquals(0f, eye.x)
    assertEquals(0f, eye.z)
    assertTrue(eye.y * tanHalf in 25f..30f, "${eye.y}")
  }

  @Test
  fun `протяжённый чертёж без выбросов не режется`() {
    // трасса: 200 отрезков подряд на 20 км — крайние элементы далеко, но площадь без них почти та же
    val road = (0 until 200).map { i -> line(i * 100.0, 0.0, i * 100.0 + 100, 0.0) }
    assertNull(buildRenderScene(readGeometry(*doc(road))).home)
  }

  @Test
  fun `точка в нуле и забытый блок — две группы по разные стороны`() {
    val scene = buildRenderScene(readGeometry(File("src/jvmTest/resources/dxf/outliers.dxf")))
    val home = assertNotNull(scene.home)
    assertEquals(2, home.outliers.size)
    assertTrue(home.outliers.all { it.count == 1 })
    assertTrue(home.halfX in 55f..70f && home.halfZ in 40f..50f, "${home.halfX} × ${home.halfZ}")
    // в исходном виде основная масса в кадре, обе группы — нет: на каждую своя плашка
    val pose = homePose(scene, 16f / 10f)
    assertNull(pose.offscreenDrawing(45.0, assertNotNull(home.outline), 1f, 1280f, 800f))
    val pointers = outlierPointers(home.outliers, 0.1f) { pose.offscreenDrawing(45.0, it.outline, 1f, 1280f, 800f) }
    assertEquals(2, pointers.size)
    // расстояние до точки в 0,0 — от середины участка, около 1,4 км
    val point = home.outliers.minBy { it.outline.hull[0] }
    val distance = assertNotNull(pose.offscreenDrawing(45.0, point.outline, 1f, 1280f, 800f)).distance
    assertTrue(distance in 1300f..1500f, "$distance")
  }

  @Test
  fun `переключатель выключен — плашки на далёкие объекты не строятся, плашка Чертёж остаётся`() {
    val scene = buildRenderScene(readGeometry(File("src/jvmTest/resources/dxf/outliers.dxf")))
    val home = assertNotNull(scene.home)
    val pose = homePose(scene, 16f / 10f)
    var calls = 0
    val offscreen = { g: dev.iskandern.kotin.scene.OutlierGroup -> calls++; pose.offscreenDrawing(45.0, g.outline, 1f, 1280f, 800f) }
    assertTrue(pointerTargets(false, false, home.outliers, 0.1f, offscreen).isEmpty())
    assertEquals(0, calls)
    assertEquals(2, pointerTargets(false, true, home.outliers, 0.1f, offscreen).size)
    assertEquals(DRAWING_POINTERS, pointerTargets(true, false, home.outliers, 0.1f, offscreen))
    assertTrue(ViewSettings().showOutlierPointers)
    assertTrue(AvailableControls(scene).outliers)
    assertTrue(!AvailableControls(buildRenderScene(readGeometry(File("src/jvmTest/resources/dxf/proxy-gayka.dxf")))).outliers)
  }

  @Test
  fun `длинная линия из основной массы уходит за край без плашки`() {
    val scene = buildRenderScene(readGeometry(File("src/jvmTest/resources/dxf/linetypes.dxf")))
    val home = assertNotNull(scene.home)
    assertTrue(home.halfX < 200f, "${home.halfX}")
    val pose = homePose(scene, 16f / 10f)
    assertTrue(home.outliers.none { pose.offscreenDrawing(45.0, it.outline, 1f, 1280f, 800f) != null })
  }

  @Test
  fun `группы в близких направлениях — одна плашка на ближнюю`() {
    // восток 89 км, север 28 км, юг 62 км
    val scene = buildRenderScene(
      readGeometry(*doc(grid + listOf(point(90000.0, 2025.0), point(1025.0, 30000.0), point(1025.0, -60000.0)))),
    )
    val home = assertNotNull(scene.home)
    assertEquals(3, home.outliers.size)
    val pose = homePose(scene, 1f)
    val offscreen = { g: dev.iskandern.kotin.scene.OutlierGroup -> pose.offscreenDrawing(45.0, g.outline, 1f, 800f, 800f) }
    assertEquals(3, outlierPointers(home.outliers, 0.1f, offscreen).size)
    // порог в 2 рад: восток (90° от севера) уходит в плашку ближнего севера, юг (180°) — отдельно
    val merged = outlierPointers(home.outliers, 2f, offscreen)
    assertEquals(listOf(2, 1), merged.map { it.count })
    assertTrue(assertNotNull(offscreen(home.outliers[merged[0].group])).dirY < -0.9f)
  }
}
