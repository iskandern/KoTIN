package dev.iskandern.kotin.landxml

import dev.iskandern.kotin.dxf.DxfDocumentReader
import dev.iskandern.kotin.dxf.DxfFlattener
import dev.iskandern.kotin.model.SceneGeometry
import dev.iskandern.kotin.io.asByteInput
import dev.iskandern.kotin.scene.buildRenderScene
import dev.iskandern.kotin.io.DrawingFormat
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Нагрузочный замер одного файла в отдельной JVM: `LandXmlLoad xml|dxf <файл> [read]`.
 * Читает как приложение (`parseGeometryInline`: читателю передаётся размер файла), затем готовит сцену
 * (с `read` — только чтение); печатает строку [Measure]. Пик кучи — [minHeapMb]: наименьший `-Xmx`, с которым чтение
 * проходит (счётчики пулов при большой куче показывают и мусор, а не то, что нужно телефону).
 */
object LandXmlLoad {

  class Measure(val readMs: Double, val sceneMs: Double, val triangles: Int, val progressBytes: Long, val truncated: Boolean) {
    fun line() = "MEASURE $readMs $sceneMs $triangles $progressBytes $truncated"

    companion object {
      fun parse(line: String): Measure {
        val p = line.removePrefix("MEASURE ").split(' ')
        return Measure(p[0].toDouble(), p[1].toDouble(), p[2].toInt(), p[3].toLong(), p[4].toBoolean())
      }
    }
  }

  @JvmStatic
  fun main(args: Array<String>) {
    val kind = args[0]
    val file = File(args[1])
    val readOnly = args.getOrNull(2) == "read"
    var progress = 0L
    val start = System.nanoTime()
    val geometry: SceneGeometry = if (kind == "xml") {
      val detected = DrawingFormat.detect(file.inputStream().asByteInput())
      // LANDXML_NOSIZE=1 — без размера файла, для сравнения «до»
      val size = if (System.getenv("LANDXML_NOSIZE") != null) null else file.length()
      detected.input.use { LandXmlReader(it, sizeBytes = size).read { bytes -> progress = bytes } }
    } else {
      val document = file.inputStream().asByteInput().use { DxfDocumentReader(it).read { bytes -> progress = bytes } }
      DxfFlattener(document).flatten()
    }
    val readMs = (System.nanoTime() - start) / 1e6
    var sceneMs = 0.0
    if (!readOnly) {
      val sceneStart = System.nanoTime()
      check(buildRenderScene(geometry).radius.isFinite())
      sceneMs = (System.nanoTime() - sceneStart) / 1e6
    }
    println(Measure(readMs, sceneMs, geometry.meshes.sumOf { it.triangleCount }, progress, geometry.truncated).line())
  }

  /** Замер [file] в новой JVM с кучей [heapMb]; null — чтение не уложилось в кучу. [extraArgs] — например, JFR. */
  fun measure(kind: String, file: File, heapMb: Int, readOnly: Boolean = false, extraArgs: List<String> = emptyList()): Measure? {
    val java = File(System.getProperty("java.home"), "bin/java").path
    val command = listOf(java, "-Xmx${heapMb}m", "-XX:+UseSerialGC", "-Xmn16m") + extraArgs +
      listOf("-cp", System.getProperty("java.class.path"), LandXmlLoad::class.java.name, kind, file.path) +
      if (readOnly) listOf("read") else emptyList()
    val process = ProcessBuilder(command).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readLines()
    check(process.waitFor(30, TimeUnit.MINUTES)) { "замер $kind ${file.name} не кончился" }
    if (process.exitValue() != 0) {
      check(output.any { "OutOfMemoryError" in it }) { "замер $kind ${file.name}: ${output.takeLast(20)}" }
      return null
    }
    return Measure.parse(output.last { it.startsWith("MEASURE ") })
  }

  /**
   * Наименьшая куча (МБ, с точностью [stepMb]), с которой [file] читается: делением отрезка [32, [maxMb]].
   * Молодое поколение — 16 МБ ([measure]), иначе большие массивы ждут места в старом и куча завышена на треть.
   */
  fun minHeapMb(kind: String, file: File, maxMb: Int, stepMb: Int = 8): Int {
    var low = 32
    var high = maxMb
    check(measure(kind, file, high, readOnly = true) != null) { "$kind ${file.name} не читается и с кучей $maxMb МБ" }
    while (high - low > stepMb) {
      val mid = (low + high) / 2
      if (measure(kind, file, mid, readOnly = true) != null) high = mid else low = mid
    }
    return high
  }
}
