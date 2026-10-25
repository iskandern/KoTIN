package dev.iskandern.kotin.landxml

import org.junit.Test
import dev.iskandern.kotin.io.ByteArrayInput
import dev.iskandern.kotin.dxf.DxfTokenizer
import dev.iskandern.kotin.io.asByteInput
import dev.iskandern.kotin.ru
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class XmlTokenizerTest {

  private fun tokenizer(bytes: ByteArray, chunk: Int = Int.MAX_VALUE, bufferSize: Int = 256 * 1024) =
    XmlTokenizer(ChunkedInput(bytes, chunk), TIN_NAMES, bufferSize)

  @Test
  fun `документ со всеми конструкциями`() {
    assertEquals(EXPECTED, transcript(tokenizer(DOCUMENT)))
  }

  @Test
  fun `чтение по 1, 3 и 7 байт и маленький буфер дают то же`() {
    for (chunk in listOf(1, 2, 3, 7)) {
      for (bufferSize in listOf(4, 16, 256 * 1024)) {
        assertEquals(EXPECTED, transcript(tokenizer(DOCUMENT, chunk, bufferSize)), "по $chunk байт, буфер $bufferSize")
      }
    }
  }

  @Test
  fun `числа из байтов, 18-19 знаков без медленного пути`() {
    val xml = tokenizer(DOCUMENT, chunk = 3)
    val values = ArrayList<Double>()
    val slow = LandXmlNumbers.slowPathCount
    while (xml.next() != XmlTokenizer.EOF) {
      if (xml.event == XmlTokenizer.START && xml.nameId == P && xml.attrInt("id".encodeToByteArray(), 0) == 2) {
        while (xml.nextNumber()) values.add(xml.double())
      }
    }
    assertEquals(0, LandXmlNumbers.slowPathCount - slow)
    assertEquals(3, values.size)
    assertNear("398273.349980979285".toDouble(), values[0])
    assertNear("2498791.366516809911".toDouble(), values[1])
    assertNear("52.783999999985".toDouble(), values[2])
  }

  @Test
  fun `windows-1251 в объявлении и в атрибуте`() {
    val text = "<?xml version=\"1.0\" encoding=\"windows-1251\"?>\n" +
      "<LandXML><Surface name=\"Поверхность &amp; рельеф\" desc=\"ё\"/><Note>Трасса</Note></LandXML>"
    val bytes = text.toByteArray(charset("windows-1251"))
    val xml = XmlTokenizer(ByteArrayInput(bytes), TIN_NAMES)
    assertEquals(XmlTokenizer.START, xml.next())
    assertEquals(XmlTokenizer.START, xml.next())
    assertEquals("Surface", xml.localName())
    assertEquals("Поверхность & рельеф", xml.attrString("name"))
    assertEquals("ё", xml.attrString("desc"))
    assertEquals(XmlTokenizer.END, xml.next())
    assertEquals(XmlTokenizer.START, xml.next())
    assertEquals(XmlTokenizer.TEXT, xml.next())
    xml.readText()
    assertEquals("Трасса", xml.textString())
  }

  @Test
  fun `префикс и пространство имён по умолчанию`() {
    val prefixed = """<lx:LandXML xmlns:lx="http://www.landxml.org/schema/LandXML-1.1" version="1.1">
      <lx:Surfaces><lx:Surface name="a"><lx:Definition><lx:Pnts><lx:P id="3">1 2 3</lx:P></lx:Pnts></lx:Definition></lx:Surface></lx:Surfaces>
      </lx:LandXML>"""
    val plain = """<LandXML xmlns="http://www.landxml.org/schema/LandXML-1.0" version="1.1">
      <Surfaces><Surface name="a"><Definition><Pnts><P id="3">1 2 3</P></Pnts></Definition></Surface></Surfaces>
      </LandXML>"""
    val expected = "<LandXML version=[1.1]><Surfaces><Surface name=[a]><Definition><Pnts><P id=[3]>{1;2;3;}</P>" +
      "</Pnts></Definition></Surface></Surfaces></LandXML>"
    assertEquals(expected, transcript(tokenizer(prefixed.encodeToByteArray())))
    assertEquals(expected, transcript(tokenizer(plain.encodeToByteArray())))

    val ids = ArrayList<Int>()
    val xml = tokenizer(prefixed.encodeToByteArray())
    while (xml.next() != XmlTokenizer.EOF) if (xml.event == XmlTokenizer.START) ids.add(xml.nameId)
    assertEquals(listOf(0, 1, 2, 3, 4, 5), ids)

    val wrongPrefix = "<lx:LandXML xmlns:lx=\"x\"></LandXML>"
    assertFailsWith<XmlFormatException> { transcript(tokenizer(wrongPrefix.encodeToByteArray())) }
  }

  @Test
  fun `30 МБ точек читаются потоком без роста буфера и без строк`() {
    val text = StringBuilder(32 * 1024 * 1024)
    text.append("<LandXML><Surfaces><Surface><Definition><Pnts>\n")
    var n = 0
    while (text.length < 30_000_000) {
      text.append("<P id=\"").append(n + 2).append("\">").append(2498791.366516809911 + n).append(' ')
        .append(398273.349980979285 - n).append(' ').append(52.783999999985).append("</P>\n")
      n++
    }
    text.append("</Pnts></Definition></Surface></Surfaces></LandXML>\n")
    val bytes = text.toString().encodeToByteArray()

    lateinit var stats: TinStats
    lateinit var xml: XmlTokenizer
    val slow = LandXmlNumbers.slowPathCount
    val result = Bench.run("XmlTokenizer 30 МБ в памяти", bytes.size.toLong(), warmup = 1, runs = 3) {
      xml = XmlTokenizer(ByteArrayInput(bytes), TIN_NAMES)
      stats = readTin(xml)
    }
    assertEquals(n, stats.points)
    assertEquals(0, xml.stringsCreated, "строки в цикле по P")
    assertEquals(256 * 1024, xml.buffer.size, "буфер не должен расти")
    assertEquals(bytes.size.toLong(), xml.bytesRead)
    println("медленный путь чисел: ${LandXmlNumbers.slowPathCount - slow}, ${"%.1f".format(result.mbPerSecond)} МБ/с")
  }

  @Test(timeout = 60_000)
  fun `оборванный файл — исключение, а не зависание`() {
    val rootEnd = String(DOCUMENT).indexOf("</LandXML>") + "</LandXML>".length
    val rootEndBytes = String(DOCUMENT).substring(0, rootEnd).encodeToByteArray().size
    for (length in 0 until rootEndBytes) {
      val cut = DOCUMENT.copyOf(length)
      for (chunk in listOf(3, Int.MAX_VALUE)) {
        val error = assertFailsWith<XmlFormatException>("обрезан до $length байт") { transcript(tokenizer(cut, chunk, 16)) }
        assertTrue(error.offset in 0..length.toLong(), "смещение ${error.offset} при длине $length")
      }
    }
  }

  @Test
  fun `ошибки — с русским сообщением и смещением`() {
    val mismatch = assertFailsWith<XmlFormatException> { transcript(tokenizer("<a><b></a>".encodeToByteArray())) }
    assertTrue("не совпадает" in ru(mismatch.message!!), ru(mismatch.message!!))
    assertEquals(6, mismatch.offset)

    val deep = "<a>".repeat(300) + "</a>".repeat(300)
    val tooDeep = assertFailsWith<XmlFormatException> { transcript(tokenizer(deep.encodeToByteArray())) }
    assertTrue("вложенность" in ru(tooDeep.message!!), ru(tooDeep.message!!))
    assertEquals(256L * 3, tooDeep.offset)

    val ok = "<a>".repeat(256) + "</a>".repeat(256)
    transcript(tokenizer(ok.encodeToByteArray()))

    val torn = assertFailsWith<XmlFormatException> { transcript(tokenizer("<a><b x=\"1\"".encodeToByteArray())) }
    assertTrue("оборван" in ru(torn.message!!), ru(torn.message!!))

    val garbage = tokenizer("<Pnts><P id=\"2\">1 abc 3</P></Pnts>".encodeToByteArray())
    assertEquals(XmlTokenizer.START, garbage.next())
    assertEquals(XmlTokenizer.START, garbage.next())
    assertTrue(garbage.nextNumber())
    assertEquals(1.0, garbage.double())
    assertTrue(garbage.nextNumber())
    val notNumber = assertFailsWith<XmlFormatException> { garbage.double() }
    assertEquals(18, notNumber.offset)
  }

  @Test
  fun `skipElement пропускает вложенное, nextChild идёт по детям`() {
    val text = """<LandXML><Units><Metric><X a="1"><Y/>текст<![CDATA[<P>]]></X></Metric></Units>
      <Surfaces><Surface><Definition><Pnts><Q/><P id="4">1 2 3</P><P id="6"/></Pnts></Definition></Surface></Surfaces></LandXML>"""
    val xml = tokenizer(text.encodeToByteArray(), chunk = 5, bufferSize = 8)
    assertEquals(XmlTokenizer.START, xml.next())
    val names = ArrayList<String>()
    while (xml.nextChild(1)) {
      names.add(xml.localName())
      xml.skipElement()
      assertEquals(XmlTokenizer.END, xml.event)
    }
    assertEquals(listOf("Units", "Surfaces"), names)
    assertEquals(XmlTokenizer.EOF, xml.next())
  }

  @Test
  fun `токенизатор и StAX видят одни и те же элементы и атрибуты`() {
    // неизвестную сущность и битую ссылку токенизатор оставляет как есть, а StAX считает ошибкой
    val strict = String(DOCUMENT).replace(" &unknown; &#xZZ;", "").encodeToByteArray()
    assertEquals(staxElements(strict), tokenizerElements(tokenizer(strict)))
    val files = File("src/jvmTest/resources/landxml").walkTopDown().filter { it.extension == "xml" }.sortedBy { it.path }.toList()
    assertTrue(files.size >= 24)
    for (file in files) {
      val bytes = file.readBytes()
      val stax = staxElements(bytes)
      val ours = tokenizerElements(tokenizer(bytes, chunk = 4093))
      assertEquals(stax.size, ours.size, file.name)
      assertEquals(stax.groupingBy { it.substringBefore(' ') }.eachCount(), ours.groupingBy { it.substringBefore(' ') }.eachCount(), file.name)
      assertEquals(stax, ours, file.name)
      println("%-40s %6d элементов — как у StAX".format(file.name, ours.size))
    }
  }

  @Test
  fun `TIN синтетического файла читается без строк и медленного пути, не медленнее DXF`() {
    val tin = SyntheticLandXml(points = 150_000)
    val xmlFile = File.createTempFile("synthetic", ".xml").apply { deleteOnExit() }
    val dxfFile = File.createTempFile("synthetic", ".dxf").apply { deleteOnExit() }
    tin.writeTo(xmlFile)
    DxfBaseline(tin).writeTo(dxfFile)

    lateinit var stats: TinStats
    lateinit var xml: XmlTokenizer
    val slow = LandXmlNumbers.slowPathCount
    val xmlResult = Bench.run("XmlTokenizer: TIN", xmlFile.length()) {
      xml = XmlTokenizer(xmlFile.inputStream().asByteInput(), TIN_NAMES)
      xml.use { stats = readTin(it) }
    }
    assertEquals(0, LandXmlNumbers.slowPathCount - slow, "числа на медленном пути")
    assertEquals(0, xml.stringsCreated, "строки в цикле по P/F")
    assertEquals(tin.points, stats.points)
    assertEquals(tin.faces, stats.faces)
    assertEquals(tin.invisibleFaces, stats.invisible)

    var dxfValues = 0
    val dxfResult = Bench.run("DxfTokenizer: тот же TIN (3DFACE)", dxfFile.length()) {
      dxfValues = 0
      dxfFile.inputStream().asByteInput().use { input ->
        val dxf = DxfTokenizer(input)
        var sum = 0.0
        while (dxf.next()) {
          if (dxf.code in 10..33) {
            sum += dxf.double()
            dxfValues++
          }
        }
        assertTrue(sum.isFinite())
      }
    }
    assertEquals(tin.faces * 12, dxfValues)
    assertTrue(
      xmlResult.mbPerSecond * 2 >= dxfResult.mbPerSecond,
      "XmlTokenizer ${"%.1f".format(xmlResult.mbPerSecond)} МБ/с, DxfTokenizer ${"%.1f".format(dxfResult.mbPerSecond)} МБ/с",
    )
  }

  private fun assertNear(expected: Double, actual: Double) {
    assertTrue(kotlin.math.abs(expected.toBits() - actual.toBits()) <= 1, "ожидалось $expected, получено $actual")
  }

  private companion object {
    val DOCUMENT = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE LandXML [
  <!ELEMENT LandXML ANY>
  <!-- в подмножестве: ] > " -->
]>
<!-- комментарий до корня -->
<?app данные?>
<LandXML xmlns="http://www.landxml.org/schema/LandXML-1.2" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" version="1.2">
  <Units><Metric linearUnit="meter" /></Units>
  <Surfaces>
    <Surface name="Рельеф &amp; &lt;1&gt; &quot;к&quot; &apos;а&apos; &#1046;&#x416;" desc='в "кавычках" > тег'>
      <Definition surfType="TIN">
        <Pnts>
          <P id="2">398273.349980979285 2498791.366516809911 52.783999999985</P>
          <P
             id = "5" >
            -1.5	2.25
            3e2
          </P>
          <P id="7"/>
        </Pnts>
        <Faces>
          <F n="0 2 0">2 5 7</F>
          <F i="1">5 7 2</F>
        </Faces>
      </Definition>
    </Surface>
  </Surfaces>
  <Note>a &amp; b<![CDATA[<c> & ]]>d<!-- x -->e<?pi?>&#x1F600; &unknown; &#xZZ;</Note>
  <Empty></Empty>
  <!-- комментарий -->
</LandXML>
<!-- хвост -->
""".encodeToByteArray()

    const val EXPECTED = "<LandXML version=[1.2]>" +
      "<Units><Metric linearUnit=[meter]/></Metric></Units>" +
      "<Surfaces><Surface name=[Рельеф & <1> \"к\" 'а' ЖЖ] desc=[в \"кавычках\" > тег]><Definition surfType=[TIN]><Pnts>" +
      "<P id=[2]>{398273.349980979285;2498791.366516809911;52.783999999985;}</P>" +
      "<P id=[5]>{-1.5;2.25;3e2;}</P>" +
      "<P id=[7]/>{}</P>" +
      "</Pnts><Faces><F n=[0 2 0]>'2 5 7'</F><F i=[1]>'5 7 2'</F></Faces></Definition></Surface></Surfaces>" +
      "<Note>'a & b<c> & de😀 &unknown; &#xZZ;'</Note><Empty></Empty></LandXML>"
  }
}
