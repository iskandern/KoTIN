package dev.iskandern.kotin.landxml

import java.lang.management.ManagementFactory
import java.lang.management.MemoryType

/**
 * Стенд замеров: прогрев, медиана нескольких прогонов, печать МБ/с, мс и пика кучи.
 * Время в CI не проверяется (шумит) — только печатается и сравнивается с допуском.
 */
object Bench {

  class Result(val name: String, val bytes: Long, val medianMs: Double, val peakHeapMb: Double) {
    val mbPerSecond: Double get() = bytes / 1e6 / (medianMs / 1000)

    override fun toString() =
      "%-34s %7.1f МБ %8.1f мс %8.1f МБ/с  пик кучи %7.1f МБ".format(name, bytes / 1e6, medianMs, mbPerSecond, peakHeapMb)
  }

  fun run(name: String, bytes: Long, warmup: Int = 2, runs: Int = 5, block: () -> Unit): Result {
    repeat(warmup) { block() }
    val times = DoubleArray(runs)
    resetPeak()
    for (i in 0 until runs) {
      val start = System.nanoTime()
      block()
      times[i] = (System.nanoTime() - start) / 1e6
    }
    times.sort()
    return Result(name, bytes, times[runs / 2], peakHeapMb()).also { println(it) }
  }

  private fun heapPools() = ManagementFactory.getMemoryPoolMXBeans().filter { it.type == MemoryType.HEAP }

  private fun resetPeak() {
    System.gc()
    heapPools().forEach { it.resetPeakUsage() }
  }

  private fun peakHeapMb(): Double = heapPools().sumOf { it.peakUsage.used } / 1e6
}
