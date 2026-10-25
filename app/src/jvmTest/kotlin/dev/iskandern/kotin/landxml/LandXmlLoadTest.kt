package dev.iskandern.kotin.landxml

import org.junit.Assume.assumeTrue
import org.junit.Test
import jdk.jfr.consumer.RecordingFile
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Нагрузка (KoTIN-73): крупный синтетический TIN против того же рельефа как 3DFACE в DXF. В CI не гоняется:
 * `LANDXML_BIG=1 ./gradlew :app:jvmTest --tests '*LandXmlLoadTest*' -i`; `LANDXML_JFR=1` — ещё и профиль JFR
 * чтения LandXML в `app/build/landxml-*.jfr`. Каждый замер — в своей JVM ([LandXmlLoad]).
 * Бюджет: LandXML не больше чем вдвое медленнее DXF и не больше чем вдвое по пику кучи; пик кучи — не больше
 * 2× итоговых массивов сетки (24 Б на вершину, 12 Б на треугольник) плюс [HEAP_SLACK_MB].
 */
class LandXmlLoadTest {

  @Test
  fun `крупный TIN — таблица замеров и бюджет против DXF`() {
    assumeTrue(System.getenv("LANDXML_BIG") == "1")
    val rows = ArrayList<String>()
    rows.add("%-24s %8s %12s %10s %10s %10s %16s".format("файл", "МБ", "треугольников", "чтение, мс", "сцена, мс", "пик кучи", "2× массивов"))
    for (points in listOf(1_000_000, 2_500_000)) {
      val tin = SyntheticLandXml(points)
      val xml = File.createTempFile("tin-$points-", ".xml").apply { deleteOnExit() }
      val dxf = File.createTempFile("tin-$points-", ".dxf").apply { deleteOnExit() }
      try {
        tin.writeTo(xml)
        DxfBaseline(tin).writeTo(dxf)
        val jfr = if (System.getenv("LANDXML_JFR") == "1") {
          listOf("-XX:StartFlightRecording=filename=build/landxml-$points.jfr,settings=profile")
        } else {
          emptyList()
        }
        val xmlMeasure = LandXmlLoad.measure("xml", xml, MAX_HEAP_MB, extraArgs = jfr)!!
        if (jfr.isNotEmpty()) rows.add(hotMethods(File("build/landxml-$points.jfr")))
        val dxfMeasure = LandXmlLoad.measure("dxf", dxf, MAX_HEAP_MB)!!
        val xmlHeap = LandXmlLoad.minHeapMb("xml", xml, MAX_HEAP_MB)
        val dxfHeap = LandXmlLoad.minHeapMb("dxf", dxf, MAX_HEAP_MB)

        val triangles = tin.faces - tin.invisibleFaces
        assertEquals(triangles, xmlMeasure.triangles)
        assertEquals(tin.faces, dxfMeasure.triangles)
        assertFalse(xmlMeasure.truncated)
        // прогресс доходит до конца файла
        assertEquals(xml.length(), xmlMeasure.progressBytes)
        val arraysMb = (24.0 * tin.points + 12.0 * triangles) / 1e6
        rows.add(row("LandXML ${tin.points / 1000}k точек", xml, xmlMeasure, xmlHeap, arraysMb * 2))
        rows.add(row("DXF 3DFACE", dxf, dxfMeasure, dxfHeap, Double.NaN))
        rows.add("  LandXML / DXF: чтение %.2f×, пик кучи %.2f×".format(xmlMeasure.readMs / dxfMeasure.readMs, xmlHeap.toDouble() / dxfHeap))

        assertTrue(xmlMeasure.readMs <= dxfMeasure.readMs * 2, rows.joinToString("\n"))
        assertTrue(xmlHeap <= dxfHeap * 2, rows.joinToString("\n"))
        assertTrue(xmlHeap <= arraysMb * 2 + HEAP_SLACK_MB, rows.joinToString("\n"))
      } finally {
        xml.delete()
        dxf.delete()
      }
    }
    println(rows.joinToString("\n"))
  }

  @Test
  fun `PntList на 100 МБ — читается, прогресс до конца`() {
    assumeTrue(System.getenv("LANDXML_BIG") == "1")
    val points = 3_600_000
    val file = File.createTempFile("pntlist-", ".xml").apply { deleteOnExit() }
    try {
      file.outputStream().buffered(1 shl 16).use { out ->
        val input = LongPntList(points)
        val buffer = ByteArray(1 shl 16)
        while (true) {
          val n = input.read(buffer, 0, buffer.size)
          if (n < 0) break
          out.write(buffer, 0, n)
        }
      }
      assertTrue(file.length() > 100_000_000, "${file.length()}")
      val m = LandXmlLoad.measure("xml", file, MAX_HEAP_MB)!!
      val heap = LandXmlLoad.minHeapMb("xml", file, MAX_HEAP_MB)
      println("PntList %.1f МБ, %d точек: чтение %.0f мс, сцена %.0f мс, пик кучи %d МБ".format(file.length() / 1e6, points, m.readMs, m.sceneMs, heap))
      assertEquals(file.length(), m.progressBytes)
      assertFalse(m.truncated)
    } finally {
      file.delete()
    }
  }

  private fun row(name: String, file: File, m: LandXmlLoad.Measure, heapMb: Int, budgetMb: Double) =
    "%-24s %8.1f %12d %10.0f %10.0f %7d МБ %13s".format(
      name, file.length() / 1e6, m.triangles, m.readMs, m.sceneMs, heapMb, if (budgetMb.isNaN()) "" else "%.0f МБ".format(budgetMb),
    )

  // верхние кадры выборок JFR: где проходит время чтения
  private fun hotMethods(file: File): String {
    val counts = HashMap<String, Int>()
    var total = 0
    RecordingFile.readAllEvents(file.toPath()).filter { it.eventType.name == "jdk.ExecutionSample" }.forEach { event ->
      val frame = event.stackTrace?.frames?.firstOrNull() ?: return@forEach
      val name = "${frame.method.type.name.substringAfterLast('.')}.${frame.method.name}"
      counts[name] = (counts[name] ?: 0) + 1
      total++
    }
    return "  профиль ($total выборок): " + counts.entries.sortedByDescending { it.value }.take(8)
      .joinToString(", ") { "${it.key} ${it.value * 100 / maxOf(total, 1)} %" }
  }

  private companion object {
    const val MAX_HEAP_MB = 6144
    // буфер чтения, токенизатор, таблица id, классы JVM
    const val HEAP_SLACK_MB = 32
  }
}
