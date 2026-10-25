package dev.iskandern.kotin.landxml

import org.junit.Test
import dev.iskandern.kotin.io.ByteArrayInput
import dev.iskandern.kotin.model.SceneGeometry
import dev.iskandern.kotin.model.SceneMesh
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Размеры сечения колодцев и труб по `Units/diameterUnit`; без него или с неизвестной единицей — эвристика. */
class LandXmlPipeUnitsTest {

  private fun read(units: String, structDiameter: Double, pipeDiameter: Double): SceneGeometry = LandXmlReader(
    ByteArrayInput(
      """
      <LandXML version="1.2">
        $units
        <PipeNetworks>
          <PipeNetwork name="С" pipeNetType="storm">
            <Structs>
              <Struct name="1" elevRim="103" elevSump="100"><Center>0 0</Center><CircStruct diameter="$structDiameter"/></Struct>
              <Struct name="2" elevRim="103" elevSump="100"><Center>100 0</Center></Struct>
            </Structs>
            <Pipes><Pipe name="т" refStart="1" refEnd="2"><CircPipe diameter="$pipeDiameter"/></Pipe></Pipes>
          </PipeNetwork>
        </PipeNetworks>
      </LandXML>
      """.trimIndent().encodeToByteArray(),
    ),
    LandXmlReader.SECTIONS.map { it() }, log = {},
  ).read()

  // поперечник сетки по y — для колодца это диаметр цилиндра, для трубы с осью по x — диаметр кольца
  private fun SceneMesh.width(): Double {
    var lo = Double.MAX_VALUE
    var hi = -Double.MAX_VALUE
    for (i in 0 until coords.size step 3) {
      lo = minOf(lo, coords[i])
      hi = maxOf(hi, coords[i])
    }
    return hi - lo
  }

  private fun assertSizes(geometry: SceneGeometry, struct: Double, pipe: Double) {
    val structMesh = geometry.meshes.single { it.layer == PipeNetworksSection.structLayer("storm") }
    val pipeMesh = geometry.meshes.single { it.layer == PipeNetworksSection.tubeLayer("storm") }
    assertEquals(struct, structMesh.width(), struct * 0.02)
    assertEquals(pipe, pipeMesh.width(), pipe * 0.05)
  }

  @Test
  fun `Imperial inch и foot - дюймы делятся на 12`() {
    val units = """<Units><Imperial linearUnit="foot" diameterUnit="inch"/></Units>"""
    assertSizes(read(units, 48.0, 30.0), struct = 4.0, pipe = 2.5)
  }

  @Test
  fun `Imperial inch и USSurveyFoot - дюймы переводятся в футы геодезические`() {
    val units = """<Units><Imperial linearUnit="USSurveyFoot" diameterUnit="inch"/></Units>"""
    assertSizes(read(units, 48.0, 30.0), struct = 4.0, pipe = 2.5)
  }

  @Test
  fun `Metric millimeter и meter - миллиметры делятся на 1000`() {
    val units = """<Units><Metric linearUnit="meter" diameterUnit="millimeter"/></Units>"""
    assertSizes(read(units, 1500.0, 300.0), struct = 1.5, pipe = 0.3)
  }

  @Test
  fun `Metric centimeter и meter - сантиметры`() {
    val units = """<Units><Metric linearUnit="meter" diameterUnit="centimeter"/></Units>"""
    assertSizes(read(units, 150.0, 30.0), struct = 1.5, pipe = 0.3)
  }

  @Test
  fun `Metric meter и meter - размер как есть, даже больше порога эвристики`() {
    val units = """<Units><Metric linearUnit="meter" diameterUnit="meter"/></Units>"""
    assertSizes(read(units, 25.0, 2.0), struct = 25.0, pipe = 2.0)
  }

  @Test
  fun `нет Units - эвристика, миллиметры больше 20`() {
    assertSizes(read("", 1500.0, 300.0), struct = 1.5, pipe = 0.3)
    assertSizes(read("", 2.0, 0.5), struct = 2.0, pipe = 0.5)
  }

  @Test
  fun `неизвестная единица или нет diameterUnit - эвристика`() {
    assertSizes(read("""<Units><Metric linearUnit="meter" diameterUnit="parsec"/></Units>""", 1500.0, 300.0), 1.5, 0.3)
    assertSizes(read("""<Units><Metric linearUnit="meter"/></Units>""", 1500.0, 300.0), 1.5, 0.3)
    assertSizes(read("""<Units><Metric linearUnit="furlong" diameterUnit="millimeter"/></Units>""", 1500.0, 300.0), 1.5, 0.3)
  }

  @Test
  fun `Units после PipeNetworks - тоже учитываются`() {
    val geometry = LandXmlReader(
      ByteArrayInput(
        """
        <LandXML version="1.2">
          <PipeNetworks><PipeNetwork name="С" pipeNetType="storm"><Structs>
            <Struct name="1" elevRim="103" elevSump="100"><Center>0 0</Center><CircStruct diameter="48"/></Struct>
          </Structs></PipeNetwork></PipeNetworks>
          <Units><Imperial linearUnit="foot" diameterUnit="inch"/></Units>
        </LandXML>
        """.trimIndent().encodeToByteArray(),
      ),
      LandXmlReader.SECTIONS.map { it() }, log = {},
    ).read()
    assertTrue(geometry.meshes.single().width() in 3.9..4.1)
  }
}
