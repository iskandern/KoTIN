package dev.iskandern.kotin.landxml

import dev.iskandern.kotin.dxf.FACE_MONO
import org.junit.Test
import dev.iskandern.kotin.io.ByteArrayInput
import dev.iskandern.kotin.io.ByteInput
import dev.iskandern.kotin.model.SceneGeometry
import dev.iskandern.kotin.ru
import dev.iskandern.kotin.scene.buildRenderScene
import java.io.File
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LandXmlPointsTest {

  private val fixture = File("src/jvmTest/resources/landxml/cgpoints.xml")

  private val logs = ArrayList<String>()

  private fun read(input: ByteInput): SceneGeometry =
    LandXmlReader(input, listOf(CgPointsSection(log = { logs.add(ru(it)) })), log = {}).read()

  private fun read(bytes: ByteArray): SceneGeometry = read(ByteArrayInput(bytes))

  @Test
  fun `фикстура - число точек, порядок осей, отметки`() {
    val geometry = read(fixture.readBytes())
    assertEquals(5, geometry.points.size)
    // «север восток отметка» → x = восток, y = север; без отметки z = 0
    val xyz = geometry.points.map { Triple(it.x, it.y, it.z) }
    assertEquals(
      listOf(
        Triple(2000.0, 1000.0, 50.0), Triple(2000.0, 1010.0, 51.5), Triple(2010.0, 1010.0, 52.0),
        Triple(2010.0, 1000.0, 0.0), Triple(2005.0, 1005.0, 60.0),
      ),
      xyz,
    )
    assertTrue(geometry.meshes.isEmpty() && geometry.lines.isEmpty() && geometry.fills.isEmpty())
    assertFalse(geometry.truncated)
    assertEquals(listOf("LandXML: точек CgPoint 5"), logs)
  }

  @Test
  fun `фикстура - слои, подписи и объекты`() {
    val geometry = read(fixture.readBytes())
    assertEquals(listOf("Точки", "Съёмка 2"), geometry.layers.map { ru(it.name) })
    assertEquals(listOf("Точки", "Точки", "Точки", "Точки", "Съёмка 2"), geometry.points.map { ru(it.layer) })
    assertTrue(geometry.points.all { it.color == LandXmlPalette.POINT })
    // подпись — номер над отметкой (у точки без отметки — только номер), моноширинным постоянного размера
    assertEquals(listOf("50,00", "1", "51,50", "2", "52,00", "Дерево", "4", "60,00", "5"), geometry.labels.map { it.text })
    assertEquals(listOf("Точки", "Точки", "Точки", "Точки", "Точки", "Точки", "Точки", "Съёмка 2", "Съёмка 2"), geometry.labels.map { ru(it.layer) })
    assertTrue(geometry.labels.all { it.height < 0 && it.font.face == FACE_MONO && it.color == LandXmlPalette.LABEL })
    // номер — строкой выше отметки, обе — правее значка
    val (elevation, number) = geometry.labels.take(2)
    assertTrue(number.liftDp > elevation.liftDp && elevation.liftDp > 0)
    assertTrue(number.shiftDp > 0 && number.shiftDp == elevation.shiftDp)
    // объект на точку; выбор точки и подписи ведёт к одному объекту
    assertEquals(5, geometry.objects.size)
    assertTrue(geometry.objects.all { it.kind == "CgPoint" })
    assertEquals(listOf("1", "2", "Дерево", "4", "5"), geometry.objects.map { it.name })
    assertEquals(listOf(0, 1, 2, 3, 4), geometry.points.map { it.objectId })
    assertEquals(listOf(0, 0, 1, 1, 2, 2, 3, 4, 4), geometry.labels.map { it.objectId })
    val first = geometry.objects[0].attributes.associate { ru(it.name) to ru(it.value) }
    assertEquals(
      mapOf("Имя" to "1", "Код" to "EP", "Описание" to "угол здания", "X" to "2000", "Y" to "1000", "Z" to "50"),
      first,
    )
    // у точки без отметки Z в атрибутах нет, у точки без кода — кода
    assertEquals(listOf("Имя", "X", "Y"), geometry.objects[3].attributes.map { ru(it.name) })
    buildRenderScene(geometry)
  }

  @Test
  fun `фикстура - то же при чтении кусками по границам буфера`() {
    val bytes = fixture.readBytes()
    val whole = read(bytes)
    for (chunk in listOf(1, 3, 7, 64)) {
      val chunked = read(ChunkedInput(bytes, chunk))
      assertEquals(whole.points.map { Triple(it.x, it.y, it.z) }, chunked.points.map { Triple(it.x, it.y, it.z) }, "кусок $chunk")
      assertEquals(whole.labels.map { it.text }, chunked.labels.map { it.text }, "кусок $chunk")
    }
  }

  @Test
  fun `сверка с независимым чтением StAX`() {
    val bytes = fixture.readBytes()
    val geometry = read(bytes)
    val reference = staxPoints(bytes)
    assertEquals(reference.size, geometry.points.size)
    for ((i, p) in geometry.points.withIndex()) {
      assertEquals(reference[i][1], p.x, 0.0)
      assertEquals(reference[i][0], p.y, 0.0)
      assertEquals(reference[i].getOrElse(2) { 0.0 }, p.z, 0.0)
    }
    assertEquals(reference.sumOf { it[0] + it[1] + it.getOrElse(2) { 0.0 } }, geometry.points.sumOf { it.x + it.y + it.z }, 1e-9)
  }

  @Test
  fun `совпадающие точки - блоки подписей друг над другом`() {
    // 102 и 1000 в одной точке (дубли, как в файлах Carlson), 103 — рядом, но не там же
    val geometry = read(
      """<LandXML version="1.2"><CgPoints>
        |<CgPoint name="102">100 200 10</CgPoint><CgPoint name="103">100.02 200 10</CgPoint>
        |<CgPoint name="1000">100.001 200.001 10</CgPoint><CgPoint name="2000">100 200 10</CgPoint>
        |</CgPoints></LandXML>""".trimMargin().encodeToByteArray()
    )
    val line = LabelScale.POINT_DP * 1.15
    // первый блок: отметка на подъёме 3 dp, номер строкой выше; каждый следующий блок в той же точке — на 3 строки выше
    assertEquals(
      listOf("10,00" to 3.0, "102" to 3.0 + line, "10,00" to 3.0, "103" to 3.0 + line,
        "10,00" to 3.0 + 3 * line, "1000" to 3.0 + 4 * line, "10,00" to 3.0 + 6 * line, "2000" to 3.0 + 7 * line),
      geometry.labels.map { it.text to it.liftDp },
    )
  }

  @Test
  fun `точки без координат и без имени, пустые разделы`() {
    val xml = """<LandXML version="1.2"><CgPoints/><CgPoints name="Г">
      <CgPoint name="пусто"/><CgPoint name="одно">5</CgPoint><CgPoint>7 8 9</CgPoint></CgPoints></LandXML>"""
    // «одно» — одно число: это ошибка формата, как у любой точки с одним числом
    val ok = """<LandXML version="1.2"><CgPoints/><CgPoints name="Г">
      <CgPoint name="пусто"/><CgPoint>7 8 9</CgPoint></CgPoints></LandXML>"""
    assertFalse(runCatching { read(xml.encodeToByteArray()) }.isSuccess)
    val geometry = read(ok.encodeToByteArray())
    assertEquals(1, geometry.points.size)
    assertEquals(Triple(8.0, 7.0, 9.0), geometry.points[0].let { Triple(it.x, it.y, it.z) })
    assertEquals(listOf("Г"), geometry.layers.map { ru(it.name) })
    // у точки без имени — только отметка
    assertEquals(listOf("9,00"), geometry.labels.map { it.text })
    assertTrue(logs.any { it.contains("нет координат") })
  }

  @Test
  fun `раздел точек после раздела со ссылками - поиск по имени в finish`() {
    val bytes = fixture.readBytes()
    val scene = LandXmlSceneBuilder()
    var found = false
    val out = DoubleArray(3)
    // шаг стоит раньше чтения точек, но выполняется после
    scene.defer(LandXmlSceneBuilder.STAGE_REFERENCES) {
      found = scene.pointNames.find("Дерево", out) && out[0] == 2010.0 && out[1] == 1010.0 && out[2] == 52.0
    }
    val section = CgPointsSection(log = {})
    val xml = XmlTokenizer(ByteArrayInput(bytes), section.names)
    while (xml.next() != XmlTokenizer.EOF) {
      if (xml.event == XmlTokenizer.START && xml.depth == 2 && xml.localName() == "CgPoints") section.read(xml, scene)
    }
    scene.finish()
    assertTrue(found)
    assertEquals(5, scene.pointNames.size)
  }

  @Test
  fun `таблица имён - поиск, повтор имени, добавление после поиска, не ASCII`() {
    val names = LandXmlPointNames()
    val out = DoubleArray(4)
    assertFalse(names.find("A", out))
    names.add("A", 1.0, 2.0, 3.0)
    names.add("Б", 4.0, 5.0, 6.0)
    assertTrue(names.find("A", out, 1))
    assertEquals(listOf(1.0, 2.0, 3.0), out.drop(1))
    assertTrue(names.find("Б", out))
    assertEquals(4.0, out[0])
    assertFalse(names.find("AB", out))
    assertFalse(names.find("", out))
    // повтор имени — последняя; добавление после первого поиска перестраивает индекс
    names.add("A", 7.0, 8.0, 9.0)
    assertTrue(names.find("A", out))
    assertEquals(listOf(7.0, 8.0, 9.0), out.take(3))
    // имя из середины большого буфера
    val buf = "xxPNT-12yy".encodeToByteArray()
    names.add(buf, 2, 8, 1.0, 1.0, 1.0)
    assertTrue(names.find("PNT-12", out))
    assertFalse(names.find("PNT-1", out))
    // пустое имя — тоже имя
    names.add("", 0.5, 0.5, 0.5)
    assertTrue(names.find("", out))
    assertEquals(0.5, out[0])
    // много точек — индекс растёт, пул тоже
    val many = LandXmlPointNames()
    for (i in 0 until 5000) many.add("pt$i", i.toDouble(), 0.0, 0.0)
    for (i in 0 until 5000 step 7) {
      assertTrue(many.find("pt$i", out))
      assertEquals(i.toDouble(), out[0])
    }
    assertFalse(many.find("pt5000", out))
  }

  @Test
  fun `имя не ASCII и с сущностью находится по строке`() {
    val xml = """<LandXML version="1.2"><CgPoints><CgPoint name="Т&amp;Ш ёж">1 2 3</CgPoint></CgPoints></LandXML>"""
    val section = CgPointsSection(log = {})
    val scene = LandXmlSceneBuilder()
    val bytes = xml.encodeToByteArray()
    val tokenizer = XmlTokenizer(ByteArrayInput(bytes), section.names)
    while (tokenizer.next() != XmlTokenizer.EOF) {
      if (tokenizer.event == XmlTokenizer.START && tokenizer.depth == 2) section.read(tokenizer, scene)
    }
    val out = DoubleArray(3)
    assertTrue(scene.pointNames.find("Т&Ш ёж", out))
    assertEquals(listOf(2.0, 1.0, 3.0), out.toList())
    assertEquals(listOf("3,00", "Т&Ш ёж"), scene.finish().labels.map { it.text })
  }

  @Test
  fun `подписи и объекты не больше предела, значки - у всех`() {
    val total = CgPointsSection.MAX_DETAILED + 50
    val geometry = read(SyntheticPoints(total, withCodes = true))
    assertEquals(total, geometry.points.size)
    // номер и отметка
    assertEquals(2 * CgPointsSection.MAX_DETAILED, geometry.labels.size)
    assertEquals(CgPointsSection.MAX_DETAILED, geometry.objects.size)
    assertNotEquals(-1, geometry.points[CgPointsSection.MAX_DETAILED - 1].objectId)
    assertEquals(-1, geometry.points.last().objectId)
    assertTrue(logs.any { it.contains("только у первых ${CgPointsSection.MAX_DETAILED}") })
  }

  @Test
  fun `скорость и память - миллион точек`() {
    val total = if (System.getenv("LANDXML_BIG") == "1") 1_000_000 else 300_000
    val source = SyntheticPoints(total, withCodes = false)
    val size = SyntheticPoints(total, withCodes = false).also { drain(it) }.produced
    System.gc()
    val before = usedHeap()
    val start = System.nanoTime()
    val geometry = read(source)
    val seconds = (System.nanoTime() - start) / 1e9
    System.gc()
    val perPoint = (usedHeap() - before).toDouble() / total
    println(
      "CgPoint: %,d точек, %.1f МБ, %.2f с — %,.0f точек/с, %.1f МБ/с; память после чтения %.0f байт на точку (с %,d подписями и объектами)"
        .format(total, size / 1e6, seconds, total / seconds, size / 1e6 / seconds, perPoint, geometry.labels.size),
    )
    assertEquals(total, geometry.points.size)
    // сверху вниз: подписи и объекты первых 200 000 точек дороже (строки, атрибуты), остальные — значок и пул имён;
    // 620: подпись точки в две строки (номер / отметка) и длина в плане у объекта подняли ~600 байт на несколько
    assertTrue(perPoint < 620, "память на точку $perPoint")
  }

  private fun usedHeap(): Long = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }

  private fun drain(input: ByteInput) {
    val buffer = ByteArray(1 shl 16)
    while (input.read(buffer, 0, buffer.size) >= 0) Unit
  }

  /** CgPoints на лету, без файла: N точек «север восток отметка» с 12 знаками. */
  private class SyntheticPoints(private val total: Int, private val withCodes: Boolean) : ByteInput {
    private var pending = ByteArray(0)
    private var at = 0
    private var next = -1
    var produced = 0L
      private set

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
      if (at >= pending.size) {
        if (!refill()) return -1
      }
      val n = minOf(length, pending.size - at)
      pending.copyInto(buffer, offset, at, at + n)
      at += n
      produced += n
      return n
    }

    private fun refill(): Boolean {
      val sb = StringBuilder()
      when {
        next == -1 -> {
          sb.append("<LandXML xmlns=\"http://www.landxml.org/schema/LandXML-1.2\" version=\"1.2\">\n<CgPoints name=\"Съёмка\">\n")
          next = 0
        }
        next < total -> {
          val end = minOf(total, next + 1000)
          for (i in next until end) {
            sb.append("<CgPoint name=\"").append(i + 1).append('"')
            if (withCodes) sb.append(" code=\"K").append(i % 10).append('"')
            sb.append('>').append(2498000 + i / 1000).append('.').append(i % 1000).append("123456 ")
              .append(398000 + i % 1000).append(".25 ").append(50 + i % 7).append(".5</CgPoint>\n")
          }
          next = end
        }
        next == total -> {
          sb.append("</CgPoints>\n</LandXML>\n")
          next++
        }
        else -> return false
      }
      pending = sb.toString().encodeToByteArray()
      at = 0
      return true
    }
  }

  // точки файла по порядку: [север, восток, отметка?]
  private fun staxPoints(bytes: ByteArray): List<List<Double>> {
    val reader = XMLInputFactory.newInstance().createXMLStreamReader(bytes.inputStream())
    val result = ArrayList<List<Double>>()
    while (reader.hasNext()) {
      if (reader.next() == XMLStreamConstants.START_ELEMENT && reader.localName == "CgPoint") {
        result.add(reader.elementText.trim().split(Regex("\\s+")).map { it.toDouble() })
      }
    }
    return result
  }
}
