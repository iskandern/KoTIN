package dev.iskandern.kotin.landxml

import org.junit.Test
import dev.iskandern.kotin.io.ByteArrayInput
import dev.iskandern.kotin.model.CoordinateSystemInfo
import dev.iskandern.kotin.model.SceneGeometry
import dev.iskandern.kotin.ru
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `CoordinateSystem` читается как записан, без проверок, и геометрию не меняет (#288). */
class LandXmlCoordinateSystemTest {

  private val fixtures = File("src/jvmTest/resources/landxml/coordinate-system")

  private fun read(bytes: ByteArray, log: MutableList<String> = ArrayList()): SceneGeometry =
    LandXmlReader(ByteArrayInput(bytes), LandXmlReader.SECTIONS.map { it() }, log = { log += ru(it) }).read()

  private fun read(name: String, log: MutableList<String> = ArrayList()) = read(File(fixtures, name).readBytes(), log)

  @Test
  fun `EPSG — все атрибуты как в файле, код и имя, единицы файла, строка журнала`() {
    val log = ArrayList<String>()
    val system = read("epsg-28406.xml", log).coordinateSystems.single()
    assertEquals(
      listOf(
        "name" to "Pulkovo 1942 / Gauss-Kruger zone 6", "epsgCode" to "28406", "horizontalDatum" to "Pulkovo 1942",
        "verticalDatum" to "Baltic 1977", "ellipsoidName" to "Krassowsky 1940",
      ),
      system.attributes,
    )
    assertEquals("EPSG 28406", system.code)
    assertEquals("Pulkovo 1942 / Gauss-Kruger zone 6", system.name)
    assertEquals("meter", system.fileUnits)
    assertTrue("LandXML: система координат «Pulkovo 1942 / Gauss-Kruger zone 6» (EPSG 28406)" in log, log.toString())
    assertEquals(1, log.count { "система координат" in it })
  }

  @Test
  fun `WKT — код «WKT», имя из WKT, WKT целиком`() {
    val log = ArrayList<String>()
    val system = read("carlson-wkt.xml", log).coordinateSystems.single()
    assertEquals("WKT", system.code)
    assertEquals("CANQ-M6", system.name)
    assertNull(system.nameSource)
    val wkt = system.attr(CoordinateSystemInfo.WKT)!!
    assertTrue(wkt.startsWith("PROJCS[\"CANQ-M6\",GEOGCS[\"LL83\"") && wkt.endsWith("UNIT[\"Meter\",1.00000000000000]]"), wkt)
    assertTrue(wkt.length > 300)
    assertEquals("USSurveyFoot", system.fileUnits)
    assertTrue("LandXML: система координат «CANQ-M6» (WKT)" in log, log.toString())
  }

  @Test
  fun `только имя и датумы — без кода, дети пропущены, противоречия не проверяются`() {
    val log = ArrayList<String>()
    val geometry = read("faa-name.xml", log)
    val system = geometry.coordinateSystems.single()
    assertNull(system.code)
    assertEquals("NAD83 Alaska Zone 6 State Plane, US Survey Foot", system.name)
    assertEquals("WGS84", system.attr("ellipsoidName"))
    assertEquals("NGVD9003", system.attr("verticalDatum"))
    assertEquals("LL", system.attr("geocentricCoordinateSystemName"))
    assertEquals(5, system.attributes.size)
    assertTrue("LandXML: система координат «NAD83 Alaska Zone 6 State Plane, US Survey Foot»" in log, log.toString())
    // поверхность после элемента с детьми прочитана
    assertEquals(2, geometry.meshes.sumOf { it.triangleCount })
  }

  @Test
  fun `несколько элементов — по порядку файла, в том числе из шапки съёмки`() {
    val log = ArrayList<String>()
    val systems = read("eplan-two.xml", log).coordinateSystems
    assertEquals(2, systems.size)
    assertEquals(listOf("horizontalDatum" to "Local", "verticalDatum" to "Arbitrary"), systems[0].attributes)
    assertEquals("Local", systems[0].name)
    assertEquals("horizontalDatum", systems[0].nameSource)
    assertEquals(listOf("datum" to "OfPlan", "desc" to "DG250 (subtract 21' for MGA)"), systems[1].attributes)
    assertEquals("DG250 (subtract 21' for MGA)", systems[1].name)
    assertNull(systems[1].code)
    assertEquals(2, log.count { "система координат" in it })
  }

  @Test
  fun `нет элемента — систем нет и в журнале о ней ничего`() {
    val log = ArrayList<String>()
    val geometry = read(File("src/jvmTest/resources/landxml/minimal-1.2.xml").readBytes(), log)
    assertTrue(geometry.coordinateSystems.isEmpty())
    assertTrue(log.none { "система координат" in it }, log.toString())
  }

  @Test
  fun `префикс EPSG в коде показывается как есть, пустые атрибуты пропускаются, длинные строки обрезаются`() {
    val long = "x".repeat(1000)
    val xml = """<LandXML version="1.2"><CoordinateSystem epsgCode="EPSG:3857" name="" desc="$long"/></LandXML>"""
    val system = read(xml.encodeToByteArray()).coordinateSystems.single()
    assertEquals("EPSG:3857", system.code)
    assertEquals(listOf("epsgCode", "desc"), system.attributes.map { it.first })
    assertEquals(300, system.attr("desc")!!.length)
    assertNull(system.fileUnits)
  }

  @Test
  fun `геометрия бит в бит та же, что без элемента`() {
    val strip = Regex("""<CoordinateSystem\b[^>]*/>|<CoordinateSystem\b[^>]*>.*?</CoordinateSystem>""", RegexOption.DOT_MATCHES_ALL)
    for (name in listOf("epsg-28406.xml", "carlson-wkt.xml", "faa-name.xml", "eplan-two.xml")) {
      val text = File(fixtures, name).readText()
      val with = read(text.encodeToByteArray())
      val without = read(strip.replace(text, "").encodeToByteArray())
      assertTrue(with.coordinateSystems.isNotEmpty() && without.coordinateSystems.isEmpty(), name)
      assertEquals(with.meshes.size, without.meshes.size, name)
      for (i in with.meshes.indices) {
        assertContentEquals(with.meshes[i].coords, without.meshes[i].coords, name)
        assertContentEquals(with.meshes[i].triangles, without.meshes[i].triangles, name)
      }
      assertEquals(with.lines.size, without.lines.size, name)
      for (i in with.lines.indices) assertContentEquals(with.lines[i].coords, without.lines[i].coords, name)
      assertEquals(with.labels.map { it.text }, without.labels.map { it.text }, name)
      assertEquals(with.points.size, without.points.size, name)
    }
  }
}
