package dev.iskandern.kotin.landxml

import org.junit.Test
import dev.iskandern.kotin.io.ByteArrayInput
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Мусорные числа в файле не должны вешать читатель: бесконечные длины, пикеты и кривизна. */
class LandXmlHangGuardTest {

  private fun file(attrs: String, element: String) =
    """<?xml version="1.0"?>
<LandXML xmlns="http://www.landxml.org/schema/LandXML-1.2" version="1.2">
<Alignments><Alignment name="Т1" $attrs><CoordGeom>$element</CoordGeom></Alignment></Alignments>
</LandXML>""".encodeToByteArray()

  // читает в отдельном потоке: зависание — падение теста через 20 с, а не вечный прогон
  private fun readWithin(bytes: ByteArray) {
    var failure: Throwable? = null
    val thread = Thread {
      try {
        LandXmlReader(ByteArrayInput(bytes), listOf(AlignmentsSection(log = {}, stationing = true)), log = {}).read()
      } catch (e: Throwable) {
        failure = e
      }
    }
    thread.isDaemon = true
    thread.start()
    thread.join(TimeUnit.SECONDS.toMillis(20))
    assertTrue(!thread.isAlive, "чтение зависло")
    failure?.let { throw it }
  }

  @Test
  fun `огромная длина трассы не вешает пикетаж`() {
    readWithin(file("""staStart="0"""", "<Line><Start>0 0</Start><End>0 1e300</End></Line>"))
  }

  @Test
  fun `огромный staStart не вешает пикетаж`() {
    for (sta in listOf("1e17", "-1e17", "1e300")) {
      readWithin(file("""staStart="$sta"""", "<Line><Start>0 0</Start><End>0 100</End></Line>"))
    }
  }

  @Test
  fun `нормальные пикеты по-прежнему кратны шагу`() {
    val stationing = Stationing(staStart = 5.0, length = 100.0)
    val stations = ArrayList<Double>()
    stationing.forEachMultiple(20.0) { _, station -> stations.add(station) }
    assertEquals(listOf(20.0, 40.0, 60.0, 80.0, 100.0), stations)
  }

  @Test
  fun `огромный диапазон пикетов пропускается`() {
    var calls = 0
    Stationing(staStart = 1e17, length = 100.0).forEachMultiple(20.0) { _, _ -> calls++ }
    Stationing(staStart = 0.0, length = 1e300).forEachMultiple(20.0) { _, _ -> calls++ }
    assertEquals(0, calls)
  }

  @Test
  fun `переходная кривая с радиусом около нуля не даёт миллиарды шагов`() {
    readWithin(
      file(
        "",
        """<Spiral length="80" radiusStart="1e-320" radiusEnd="INF" rot="ccw" spiType="cosine">""" +
          "<Start>-500 0</Start><PI>-492.9 22.8</PI><End>-465.9 72.2</End></Spiral>",
      ),
    )
  }

  @Test
  fun `число шагов дуги ограничено`() {
    assertEquals(CoordGeom.MAX_ARC_STEPS, CoordGeom.arcSteps(Double.POSITIVE_INFINITY, 100.0, 1000.0))
    assertEquals(2, CoordGeom.arcSteps(Double.NaN, 100.0, 1000.0))
  }
}
