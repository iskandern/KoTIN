package dev.iskandern.kotin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LabelLoadTest {

  private fun text(quads: Int, labels: Int = 1) = LabelLoad(labels, quads, markerQuads = 0, markerDp = 0f)

  @Test
  fun `тяжёлый файл на телефоне — подписи выключены`() {
    // ACAD-surface_and_alignment.dxf: 35 647 подписей, ~59 тыс. символов
    assertEquals(LabelTier.OFF, labelTier(text(59_000, 35_647), density = 3f, viewWidth = 1080, viewHeight = 2400))
  }

  @Test
  fun `тот же файл на десктопе — прореживать`() {
    assertEquals(LabelTier.THIN, labelTier(text(59_000, 35_647), density = 1f, viewWidth = 1920, viewHeight = 1080))
  }

  @Test
  fun `небольшой файл — все подписи`() {
    // 1 тыс. подписей ACAD-surface_and_alignment.dxf — 1 658 символов
    assertEquals(LabelTier.ALL, labelTier(text(1_658, 1_000), density = 3f, viewWidth = 1080, viewHeight = 2400))
  }

  @Test
  fun `пороги по замеру на планшете`() {
    // планшет 1280×800 (вид 1280×752), 1,1125 px на dp; подмножества подписей ACAD-surface_and_alignment.dxf
    fun tablet(quads: Int, labels: Int) = labelTier(text(quads, labels), density = 1.1125f, viewWidth = 1280, viewHeight = 752)
    // 3 тыс. — все держат 60 FPS
    assertEquals(LabelTier.ALL, tablet(4_947, 3_000))
    // 4 тыс. — все уже 48 FPS: прореживать
    assertEquals(LabelTier.THIN, tablet(6_607, 4_000))
    // 35 тыс. — прореживание держит 60 FPS
    assertEquals(LabelTier.THIN, tablet(58_992, 35_647))
  }

  @Test
  fun `без подписей — все, сколько бы ни было точек`() {
    assertEquals(LabelTier.ALL, labelTier(LabelLoad(0, 0, markerQuads = 100_000, markerDp = 21f), 3f, 1080, 2400))
  }

  @Test
  fun `значки точек добавляют нагрузку`() {
    val labels = text(1_000)
    val withPoints = LabelLoad(1, 1_000, markerQuads = 20_000, markerDp = 21f)
    assertTrue(labelOverdraw(withPoints, 1f, 1920, 1080) > labelOverdraw(labels, 1f, 1920, 1080))
    assertEquals(LabelTier.OFF, labelTier(withPoints, 3f, 1080, 2400))
  }

  @Test
  fun `слишком много вершин — выключены и на большом экране`() {
    assertEquals(LabelTier.OFF, labelTier(text(300_000), density = 0.1f, viewWidth = 7680, viewHeight = 4320))
  }

  @Test
  fun `ступень легче`() {
    assertEquals(LabelTier.THIN, LabelTier.ALL.lighter())
    assertEquals(LabelTier.OFF, LabelTier.THIN.lighter())
    assertEquals(LabelTier.OFF, LabelTier.OFF.lighter())
  }

  @Test
  fun `медиана интервалов кадров`() {
    // интервалы 16, 16, 40, 16 мс — медиана 16
    val ms = 1_000_000L
    val frames = longArrayOf(0, 16 * ms, 32 * ms, 72 * ms, 88 * ms)
    assertEquals(16.0, medianFrameMillis(frames))
    assertTrue(!framesTooSlow(medianFrameMillis(frames)))
    assertTrue(framesTooSlow(33.0))
  }

  @Test
  fun `время кадра — по кадрам, которые нарисовал Filament`() {
    val ms = 1_000_000L
    // Compose идёт по 16 мс, а Filament рисует каждый девятый кадр (GPU не успевает) — медленно
    val drawn = longArrayOf(0, 150 * ms, 300 * ms, 450 * ms)
    assertEquals(150.0, drawnFrameMillis(drawn, windowMillis = 500.0, composeMillis = 16.7))
    assertTrue(framesTooSlow(drawnFrameMillis(drawn, 500.0, 16.7)))
    // за окно нарисован один кадр — всё окно на кадр
    assertEquals(500.0, drawnFrameMillis(longArrayOf(5 * ms), 500.0, 16.7))
    // истории кадров нет — по кадрам Compose
    assertEquals(16.7, drawnFrameMillis(null, 500.0, 16.7))
  }
}
