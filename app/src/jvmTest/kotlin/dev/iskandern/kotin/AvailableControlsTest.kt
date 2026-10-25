package dev.iskandern.kotin

import dev.iskandern.kotin.io.ByteArrayInput
import dev.iskandern.kotin.landxml.AlignmentsSection
import dev.iskandern.kotin.landxml.LandXmlPalette
import dev.iskandern.kotin.landxml.LandXmlReader
import dev.iskandern.kotin.scene.MIN_LINE_WIDTH_PX
import dev.iskandern.kotin.scene.buildRenderScene
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AvailableControlsTest {

  private fun controls(vararg entities: Array<String>) = AvailableControls(scene(*entities))

  private fun scene(vararg entities: Array<String>) =
    buildRenderScene(readGeometry("0", "SECTION", "2", "ENTITIES", *entities.flatten().toTypedArray(), "0", "ENDSEC", "0", "EOF"))

  private val line = arrayOf("0", "LINE", "8", "0", "10", "0", "20", "0", "11", "10", "21", "5")
  private val heavyLine = arrayOf("0", "LINE", "8", "0", "370", "100", "10", "0", "20", "0", "11", "10", "21", "5")
  private val text = arrayOf("0", "TEXT", "8", "0", "10", "0", "20", "0", "30", "0", "40", "1", "1", "abc")
  private val point = arrayOf("0", "POINT", "8", "0", "10", "3", "20", "3", "30", "0")
  private val face = arrayOf(
    "0", "3DFACE", "8", "TIN",
    "10", "0", "20", "0", "30", "0", "11", "10", "21", "0", "31", "0", "12", "10", "22", "10", "32", "0", "13", "0", "23", "10", "33", "0",
  )

  private val hill = arrayOf(
    "0", "3DFACE", "8", "TIN",
    "10", "0", "20", "0", "30", "0", "11", "10", "21", "0", "31", "5", "12", "10", "22", "10", "32", "5", "13", "0", "23", "10", "33", "0",
  )

  @Test
  fun `только линии - нет подписей, точек и поверхностей`() {
    val c = controls(line)
    assertTrue(c.drawing)
    assertFalse(c.labels)
    assertFalse(c.points)
    assertFalse(c.surfaces)
    assertFalse(c.occlusion)
    assertFalse(c.lineWeights, "у линии без толщины переключатель толщины ничего не меняет")
  }

  @Test
  fun `есть подписи - есть и их переключатели, а скрытие за поверхностью - только вместе с поверхностью`() {
    assertTrue(controls(line, text).labels)
    assertFalse(controls(line, text).occlusion)
    assertFalse(controls(face, text).occlusion, "плоская поверхность ничего не закрывает")
    val withSurface = controls(hill, text)
    assertTrue(withSurface.surfaces && withSurface.labels && withSurface.occlusion)
    assertFalse(withSurface.drawing, "кроме поверхности и подписи чертежа нет")
  }

  @Test
  fun `точки и толщина линий`() {
    assertTrue(controls(point).points)
    assertEquals(false, controls(line).points)
    assertTrue(controls(heavyLine).lineWeights, "вес из DXF — толщину можно выключить")
  }

  @Test
  fun `у LandXML толщина наша, а не из файла - переключателя нет`() {
    val bytes = File("src/jvmTest/resources/landxml/attributes/alignment-speed-stations.xml").readBytes()
    val geometry = LandXmlReader(ByteArrayInput(bytes), listOf(AlignmentsSection(log = {}, stationing = true)), log = {}).read()
    val scene = buildRenderScene(geometry)
    assertTrue(scene.lines.any { it.color == LandXmlPalette.ALIGNMENT && it.widthPx > MIN_LINE_WIDTH_PX }, "ось трассы толстая")
    assertFalse(AvailableControls(scene).lineWeights)
  }

  @Test
  fun `масштаб по высоте - только у сцены с высотой`() {
    assertEquals(0f, scene(line).halfY, "план из линий: высота ровно ноль")
    assertFalse(controls(line).verticalScale)
    assertEquals(0f, scene(face, text).halfY, "плоская 3DFACE: высота ровно ноль")
    assertFalse(controls(face, text).verticalScale)
    assertTrue(controls(hill).verticalScale, "перепад 5 м на 10 м в плане")
    // хоть какая-то высота — чипы уже что-то меняют: перепад 0,05 м на 10 м в плане
    val lowHill = arrayOf(
      "0", "3DFACE", "8", "TIN",
      "10", "0", "20", "0", "30", "0", "11", "10", "21", "0", "31", "0.05", "12", "10", "22", "10", "32", "0.05", "13", "0", "23", "10", "33", "0",
    )
    assertTrue(controls(lowHill).verticalScale)
  }

  @Test
  fun `чертёж в плоскость нужен, только если меняет сцену`() {
    assertFalse(controls(text).flattenDrawing, "всё на z = 0, поверхности нет - класть некуда")
    assertFalse(controls(face).flattenDrawing, "одна поверхность - класть нечего")
    assertFalse(controls(face, text).flattenDrawing, "z = 0 и так поднимается на уровень поверхности")
    val high = arrayOf("0", "TEXT", "8", "0", "10", "0", "20", "0", "30", "7", "40", "1", "1", "abc")
    assertTrue(controls(high).flattenDrawing, "подпись на своей отметке")
    assertTrue(controls(face, high).flattenDrawing)
    val thick = arrayOf("0", "LINE", "8", "0", "39", "3", "10", "0", "20", "0", "11", "10", "21", "5")
    assertTrue(controls(thick).flattenDrawing, "толщина пропадает")
  }

  @Test
  fun `флажок виден независимо от того, включён ли он`() {
    val geometry = readGeometry("0", "SECTION", "2", "ENTITIES", *arrayOf("0", "TEXT", "8", "0", "10", "0", "20", "0", "30", "7", "40", "1", "1", "abc"), "0", "ENDSEC", "0", "EOF")
    assertTrue(AvailableControls(buildRenderScene(geometry, flattenDrawing = true)).flattenDrawing)
    assertTrue(AvailableControls(buildRenderScene(geometry, flattenDrawing = false)).flattenDrawing)
  }
}
