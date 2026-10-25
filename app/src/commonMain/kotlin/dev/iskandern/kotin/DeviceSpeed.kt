package dev.iskandern.kotin

import dev.iskandern.kotin.scene.drawingOutlineOf
import kotlin.time.TimeSource

/**
 * Скорость устройства — для решений «как часто пересчитывать». Мерится та же работа, что делает приложение: контур
 * чертежа по [BENCH_POINTS] точкам, лучшее из [BENCH_RUNS] прогонов (первый — разогрев JIT). Один раз за запуск.
 */
object DeviceSpeed {
  private var measured: Long? = null

  /** Время замера, мкс: первый вызов меряет, дальше — из памяти. Звать не из главного потока, если он занят кадрами. */
  fun benchMicros(): Long = measured ?: measure().also { measured = it }

  /**
   * Слабое устройство: замер дольше [SLOW_MICROS]. Без фонового потока (браузер) замер шёл бы в главном и держал
   * кадры запуска, пока грузится сцена, — такое устройство без замера считается быстрым.
   */
  fun isSlow(): Boolean = hasBackgroundThread && benchMicros() > SLOW_MICROS

  private fun measure(): Long {
    // точки вразброс, без генератора случайных чисел: замер одинаков от запуска к запуску
    val points = FloatArray(BENCH_POINTS * 3) { ((it * 7919L) % 1000).toFloat() }
    var best = Long.MAX_VALUE
    repeat(BENCH_RUNS) {
      val mark = TimeSource.Monotonic.markNow()
      drawingOutlineOf(points)
      best = minOf(best, mark.elapsedNow().inWholeMicroseconds)
    }
    return best
  }

  private const val BENCH_POINTS = 100_000
  private const val BENCH_RUNS = 4
  private const val SLOW_MICROS = 6_000L
}

/** Есть ли поток, кроме главного: `Dispatchers.Default` в браузере (wasmJs) — тот же главный поток. */
internal expect val hasBackgroundThread: Boolean
