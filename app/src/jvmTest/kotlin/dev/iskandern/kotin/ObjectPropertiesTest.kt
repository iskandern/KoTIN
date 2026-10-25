package dev.iskandern.kotin

import dev.iskandern.kotin.io.ByteArrayInput
import dev.iskandern.kotin.io.DrawingFormat
import dev.iskandern.kotin.landxml.LandXmlReader
import dev.iskandern.kotin.model.SceneObject
import dev.iskandern.kotin.scene.RenderScene
import dev.iskandern.kotin.scene.buildRenderScene
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ObjectPropertiesTest {

  private fun dxf(vararg entities: Array<String>, tables: Array<String> = emptyArray()) = buildRenderScene(
    readGeometry(
      *tables,
      "0", "SECTION", "2", "ENTITIES", *entities.flatten().toTypedArray(), "0", "ENDSEC", "0", "EOF",
    )
  )

  private fun landXml(text: String): RenderScene {
    val bytes = text.toByteArray()
    val detected = DrawingFormat.detect(ByteArrayInput(bytes))
    return buildRenderScene(LandXmlReader(detected.input, LandXmlReader.SECTIONS.map { it() }, log = {}).read())
  }

  // наклонный прямоугольник 30 × 20 в плане: вдоль y поднимается с 100 до 115 — по склону 25
  private fun face(layer: String = "TIN") = arrayOf(
    "0", "3DFACE", "8", layer,
    "10", "0", "20", "0", "30", "100", "11", "30", "21", "0", "31", "100",
    "12", "30", "22", "20", "32", "115", "13", "0", "23", "20", "33", "115",
  )

  /** Свойства выбранного объекта вида [kind] по-русски: «название» → «значение». */
  private fun card(scene: RenderScene, kind: String): Map<String, String> {
    val id = scene.objects.indexOfFirst { it.kind == kind }
    return withProperties(scene, id, scene.objects[id]).attributes.associate { ru(it.name) to ru(it.value) }
  }

  @Test
  fun `площади и отметки поверхности DXF по её треугольникам`() {
    val scene = dxf(face())
    val props = assertNotNull(surfaceProperties(scene, scene.objects.indexOfFirst { it.kind == "3DFACE" }))
    assertEquals(4, props.points)
    assertEquals(2, props.faces)
    assertEquals(600.0, props.area2D, 1e-3)
    assertEquals(750.0, props.area3D, 1e-3)
    assertEquals(100.0, props.elevMin, 1e-3)
    assertEquals(115.0, props.elevMax, 1e-3)

    val card = card(scene, "3DFACE")
    assertEquals(
      listOf("Точек", "Граней", "Площадь 2D", "Площадь 3D", "Отметка мин.", "Отметка макс."),
      card.keys.toList(),
    )
    assertEquals("600.00 м²", card["Площадь 2D"])
    assertEquals("750.00 м²", card["Площадь 3D"])
    assertEquals("100.00", card["Отметка мин."])
    assertEquals("115.00", card["Отметка макс."])
  }

  @Test
  fun `у линии DXF длина в плане по исходной линии, а не по штрихам`() {
    val tables = arrayOf(
      "0", "SECTION", "2", "TABLES",
      "0", "TABLE", "2", "LTYPE",
      "0", "LTYPE", "2", "DASHED", "70", "0", "72", "65", "73", "2", "40", "3.0", "49", "2.0", "74", "0", "49", "-1.0", "74", "0",
      "0", "ENDTAB",
      "0", "ENDSEC",
    )
    // 30-40-50 в плане и подъём по высоте: длина в плане 50, хотя штрихи покрывают лишь 2/3 линии
    val scene = dxf(
      arrayOf("0", "LINE", "8", "0", "6", "DASHED", "10", "0", "20", "0", "30", "0", "11", "30", "21", "40", "31", "7"),
      tables = tables,
    )
    assertEquals("50.00", card(scene, "LINE")["Длина в плане, м"])
  }

  @Test
  fun `у вставки блока длины нет`() {
    val scene = dxf(
      arrayOf("0", "INSERT", "8", "0", "2", "B", "10", "0", "20", "0", "30", "0"),
      tables = arrayOf(
        "0", "SECTION", "2", "BLOCKS",
        "0", "BLOCK", "8", "0", "2", "B", "70", "0", "10", "0", "20", "0", "30", "0",
        "0", "LINE", "8", "0", "10", "0", "20", "0", "11", "1", "21", "0",
        "0", "ENDBLK", "8", "0",
        "0", "ENDSEC",
      ),
    )
    val insert = scene.objects.first { it.kind == "INSERT" }
    assertEquals(emptyList(), withProperties(scene, scene.objects.indexOf(insert), insert).attributes)
  }

  @Test
  fun `поверхность LandXML - площади посчитаны, расходящиеся значения файла остаются как «в файле»`() {
    // квадрат 10 × 10 на отметке 5; в файле площадь 2D верная, 3D — неверная, отметок нет
    val scene = landXml(
      """<LandXML><Surfaces><Surface name="s"><Definition surfType="TIN" area2DSurf="100" area3DSurf="120">
        |<Pnts><P id="1">0 0 5</P><P id="2">0 10 5</P><P id="3">10 10 5</P><P id="4">10 0 5</P></Pnts>
        |<Faces><F>1 2 3</F><F>1 3 4</F></Faces></Definition></Surface></Surfaces></LandXML>""".trimMargin()
    )
    val card = card(scene, "Surface")
    assertEquals("4", card["Точек"])
    assertEquals("2", card["Граней"])
    assertEquals("100.00 м²", card["Площадь 2D"])
    assertEquals("100.00 м²", card["Площадь 3D"])
    assertEquals("5.00", card["Отметка мин."])
    assertEquals("5.00", card["Отметка макс."])
    assertNull(card["Площадь 2D в файле"])
    assertEquals("120.00 м²", card["Площадь 3D в файле"])
    assertEquals("TIN", card["Тип"])
  }

  @Test
  fun `структурная линия LandXML - длина в плане по отрезкам`() {
    val scene = landXml(
      """<LandXML><Surfaces><Surface name="s"><SourceData><Breaklines>
        |<Breakline name="b"><PntList3D>0 0 1 0 30 2 40 30 3</PntList3D></Breakline>
        |</Breaklines></SourceData></Surface></Surfaces></LandXML>""".trimMargin()
    )
    // LandXML: northing easting — (0,0) → (30,0) → (30,40): 30 + 40
    assertEquals("70.00", card(scene, "Breakline")["Длина в плане, м"])
  }

  @Test
  fun `разряды через неразрывный пробел, знаки дробной части сохраняются`() {
    assertEquals("1 234 567.80", formatGrouped(1234567.8, 2))
    assertEquals("999.00", formatGrouped(999.0, 2))
    assertEquals("-1 000.50", formatGrouped(-1000.5, 2))
    assertEquals("0.00", formatGrouped(-0.001, 2))
  }

  @Test
  fun `объект без поверхности и линий не меняется`() {
    val obj = SceneObject("CgPoint", "p", "L")
    val scene = dxf()
    assertEquals(obj, withProperties(scene, 0, obj))
  }
}
