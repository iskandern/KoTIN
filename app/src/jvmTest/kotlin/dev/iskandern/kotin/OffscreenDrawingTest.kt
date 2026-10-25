package dev.iskandern.kotin

import dev.iskandern.kotin.scene.DrawingOutline
import dev.iskandern.kotin.scene.RenderView
import dev.iskandern.kotin.scene.buildRenderScene
import dev.iskandern.kotin.scene.drawingOutlineOf
import dev.iskandern.kotin.scene.showsDrawing
import kotlin.math.PI
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OffscreenDrawingTest {

  private val fov = 45.0
  private val width = 800f
  private val height = 600f

  // строго сверху: вправо — +X, вверх по экрану — север (−Z сцены)
  private fun topDown(x: Float, z: Float, distance: Float = 20f) = CameraPose(x, 0f, z, distance, 0f, (PI / 2).toFloat())

  // квадрат ±[half] в плане, плоский или высотой ±[halfY]
  private fun square(half: Float, halfY: Float = 0f, halfZ: Float = half) =
    DrawingOutline(floatArrayOf(-half, -halfZ, half, -halfZ, half, halfZ, -half, halfZ), -halfY, halfY)

  private fun CameraPose.offscreen(outline: DrawingOutline = square(5f)) = offscreenDrawing(fov, outline, 1f, width, height)

  @Test
  fun drawingInFrameHasNoPointer() {
    assertNull(topDown(0f, 0f).offscreen())
    // край чертежа ещё в кадре
    assertNull(topDown(12f, 0f).offscreen())
  }

  @Test
  fun pointerLooksAtDrawingAndMeasuresPlanDistance() {
    // камера восточнее чертежа — он слева
    val west = assertNotNull(topDown(100f, 0f).offscreen())
    assertEquals(-1f, west.dirX, 1e-4f)
    assertEquals(0f, west.dirY, 1e-4f)
    assertEquals(95f, west.distance, 1e-3f)

    // камера южнее — чертёж сверху экрана
    val north = assertNotNull(topDown(0f, 100f).offscreen())
    assertEquals(0f, north.dirX, 1e-4f)
    assertEquals(-1f, north.dirY, 1e-4f)

    // по диагонали — расстояние до угла габаритов
    val corner = assertNotNull(topDown(105f, 105f).offscreen())
    assertEquals(141.42f, corner.distance, 0.01f)
  }

  @Test
  fun drawingBehindCameraPointsWhereToTurn() {
    // смотрим горизонтально на север (азимут 0 — камера южнее pivot), чертёж позади, южнее камеры
    val pose = CameraPose(0f, 0f, -200f, 20f, 0f, 0f)
    val behind = assertNotNull(pose.offscreen())
    assertEquals(195f, behind.distance, 1e-3f)
    assertEquals(1f, behind.dirY, 1e-4f)
  }

  @Test
  fun drawingPartlyBehindButOutOfFrameStillPointed() {
    // наклонный вид, чертёж уехал под нижний край и частью ушёл за камеру
    val pose = CameraPose(0f, 0f, -30f, 20f, 0f, (PI / 6).toFloat())
    val below = assertNotNull(pose.offscreen(square(5f, halfZ = 20f)))
    assertTrue(below.dirY > 0.99f)
  }

  @Test
  fun cameraInsideDrawingCountsAsVisible() {
    // глаз внутри габаритов: часть углов за камерой
    assertNull(CameraPose(0f, 0f, 0f, 2f, 0f, 0f).offscreen(square(5f, halfY = 5f)))
  }

  @Test
  fun diagonalStripLeavesFrameBeforeItsBoundingBox() {
    // дорога по диагонали: полоса шириной 2 от (−100, −100) до (100, 100)
    val strip = assertNotNull(drawingOutlineOf(floatArrayOf(-101f, 0f, -99f, -99f, 0f, -101f, 101f, 0f, 99f, 99f, 0f, 101f)))
    // над углом габаритов (80, −80): прямоугольник габаритов ещё в кадре, а сама полоса — нет
    val pointer = assertNotNull(topDown(80f, -80f).offscreen(strip))
    // середина полосы — в начале координат, южнее и западнее камеры: влево-вниз по экрану (север — вверх)
    assertTrue(pointer.dirX < 0f && pointer.dirY > 0f)
    assertEquals(112.0f, pointer.distance, 0.5f)
    // над самой полосой — в кадре
    assertNull(topDown(50f, 50f).offscreen(strip))
  }

  @Test
  fun outlineWrapsAllPointsTightly() {
    val outline = assertNotNull(drawingOutlineOf(floatArrayOf(-10f, 1f, -4f, 10f, 3f, -4f, 10f, 2f, 4f, -10f, 0f, 4f)))
    assertEquals(16, outline.size)
    assertEquals(0f, outline.minY)
    assertEquals(3f, outline.maxY)
    // описанный многоугольник не уже прямоугольника и шире его не больше чем на ~8 % по углам
    val xs = (0 until outline.size).map { outline.hull[it * 2] }
    val zs = (0 until outline.size).map { outline.hull[it * 2 + 1] }
    assertTrue(xs.max() >= 10f - 1e-3f && xs.max() < 10.9f)
    assertTrue(zs.min() <= -4f + 1e-3f && zs.min() > -4.9f)
    assertNull(drawingOutlineOf(FloatArray(0)))
  }

  @Test
  fun flyToNearestPartKeepsViewDirectionAndZoom() {
    val tilted = CameraPose(300f, 0f, 0f, 40f, 0.7f, 0.5f)
    val outline = square(20f, halfY = 2f)
    assertNotNull(tilted.offscreen(outline))
    // ближайшая точка — на восточном краю; камера встаёт над ней и чуть внутрь, к середине
    val target = tilted.towardDrawing(fov, floatArrayOf(20f, 1f, 0f), outline, 1f)
    assertEquals(tilted.yaw, target.yaw)
    assertEquals(tilted.pitch, target.pitch)
    assertEquals(tilted.distance, target.distance)
    assertEquals(20f - 40f * tan(PI / 8).toFloat() * 0.4f, target.pivotX, 1e-3f)
    assertEquals(0f, target.pivotZ, 1e-4f)
    assertEquals(1f, target.pivotY)
    assertNull(target.offscreen(outline))
  }

  @Test
  fun nearestPointLiesOnLongLineNotAtItsEnds() {
    // линии (0, 0)–(100, 0) и (0, 50)–(100, 50); центр сцены — (50, 25), север — −Z
    val scene = buildRenderScene(
      readGeometry(
        "0", "SECTION", "2", "ENTITIES",
        "0", "LINE", "8", "0", "10", "0", "20", "0", "11", "100", "21", "0",
        "0", "LINE", "8", "0", "10", "0", "20", "50", "11", "100", "21", "50",
        "0", "ENDSEC", "0", "EOF",
      ),
    )
    // точка интереса южнее чертежа, напротив середины нижней линии: DXF (50, −30); опорные точки — по сетке 64 × 64,
    // клетка ~1,6 × 0,8, поэтому точность — клетка
    val nearest = assertNotNull(assertNotNull(scene.outline).nearest(0f, 55f))
    assertEquals(0f, nearest[0], 1.6f)
    assertEquals(25f, nearest[2], 1e-3f)
  }

  @Test
  fun pointerDistanceIsRoundedCoarser() {
    assertNull(roundedDistance(0.4f))
    assertEquals(37.0, roundedDistance(37.4f))
    assertEquals(340.0, roundedDistance(343f))
    assertEquals(1200.0, roundedDistance(1234f))
  }

  @Test
  fun savedViewMustLandOnDrawing() {
    // чертёж — вершины по краям квадрата ±50, в том числе середины левой и правой сторон
    val drawing = drawingOutlineOf(floatArrayOf(-50f, 0f, -50f, -50f, 0f, 0f, -50f, 0f, 50f, 50f, 0f, -50f, 50f, 0f, 0f, 50f, 0f, 50f))
    assertTrue(RenderView(0f, 0f, 20f).showsDrawing(drawing, 50f, 50f))
    // центр кадра сбоку от чертежа, но линия в середине кадра
    assertTrue(RenderView(53f, 0f, 20f).showsDrawing(drawing, 50f, 50f))
    // кадр лишь задевает чертёж краем — на узком экране его не видно
    assertFalse(RenderView(65f, 0f, 20f).showsDrawing(drawing, 50f, 50f))
    // вид за километры от чертежа
    assertFalse(RenderView(5_000f, -3_000f, 20f).showsDrawing(drawing, 50f, 50f))
    // чертёж в таком виде — точка
    assertFalse(RenderView(0f, 0f, 100_000f).showsDrawing(drawing, 50f, 50f))
    assertFalse(RenderView(0f, 0f, 20f).showsDrawing(null, 50f, 50f))
  }
}
