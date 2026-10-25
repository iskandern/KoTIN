package dev.iskandern.kotin

import androidx.compose.ui.graphics.toArgb
import org.junit.Test
import dev.iskandern.kotin.dxf.DxfColors
import dev.iskandern.kotin.dxf.ScenePalette
import dev.iskandern.kotin.io.ByteArrayInput
import dev.iskandern.kotin.landxml.LandXmlPalette
import dev.iskandern.kotin.landxml.LandXmlReader
import dev.iskandern.kotin.landxml.PipeNetworksSection
import dev.iskandern.kotin.scene.buildRenderScene
import java.io.File
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Общая палитра сцены (KoTIN-91): цвет сети — только по типу, поверхности — по номеру среди поверхностей. */
class ScenePaletteTest {

  private val types = listOf("storm", "sanitary", "water", "other")

  private fun scene(path: String) = buildRenderScene(
    LandXmlReader(ByteArrayInput(File(path).readBytes()), LandXmlReader.SECTIONS.map { it() }, log = {}).read(),
  )

  // квадратик на экране — тот же цвет с точностью до округления канала
  private fun assertSameColor(expected: Int, argb: Int, what: String) {
    for (shift in intArrayOf(16, 8, 0)) {
      val a = (expected shr shift) and 0xFF
      val b = (argb shr shift) and 0xFF
      assertTrue(abs(a - b) <= 1, "$what: ${expected.toString(16)} ≠ ${(argb and 0xFFFFFF).toString(16)}")
    }
  }

  @Test
  fun `цвета линий и сетей - не меньше 3 к 1 к фону, onBackground их не меняет`() {
    val roles = listOf(
      LandXmlPalette.BREAKLINE, LandXmlPalette.CONTOUR, LandXmlPalette.ALIGNMENT, LandXmlPalette.ALIGNMENT_GROUND,
      LandXmlPalette.CROSS_SECTION, LandXmlPalette.CROSS_SECTION_DESIGN, LandXmlPalette.PLAN_FEATURE, LandXmlPalette.PARCEL,
      LandXmlPalette.SURVEY, LandXmlPalette.STATIONING, LandXmlPalette.MONUMENT, LandXmlPalette.ALIGN_PI, LandXmlPalette.ROAD_SIGN,
      LandXmlPalette.boundary("island"), LandXmlPalette.breakline("wall"), LandXmlPalette.breakline("proximity"),
      LandXmlPalette.breakline("nondestructive"),
    )
    val scales = (0..3).flatMap { listOf(LandXmlPalette.scale(it, 0.0), LandXmlPalette.scale(it, 1.0)) }
    val networks = types.flatMap { listOf(LandXmlPalette.struct(it), LandXmlPalette.pipe(it)) }
    for (rgb in ScenePalette.LINES.toList() + roles + scales + networks) {
      assertEquals(rgb, DxfColors.onBackground(rgb), "цвет ${rgb.toString(16)} затемнён onBackground")
    }
  }

  @Test
  fun `закреплённые тоны сетей и трассы не повторяются в линиях и поверхностях`() {
    val networks = types.flatMap { listOf(LandXmlPalette.struct(it), LandXmlPalette.pipe(it)) }
    val fixed = networks + LandXmlPalette.ALIGNMENT + LandXmlPalette.ALIGNMENT_GROUND
    assertEquals(fixed.size, fixed.toSet().size)
    val surfaces = (0 until 9).map { ScenePalette.surface(it) }
    assertEquals(9, surfaces.toSet().size)
    assertEquals(12, ScenePalette.LINES.toSet().size)
    for (rgb in fixed) assertTrue(rgb !in surfaces && rgb !in ScenePalette.LINES, "закреплённый ${rgb.toString(16)} повторяется")
    // после 9-й поверхности и 12-го цвета линий — по кругу
    assertEquals(ScenePalette.surface(0), ScenePalette.surface(9))
    assertEquals(ScenePalette.line(0), ScenePalette.line(12))
  }

  @Test
  fun `сцена, меню и карточка - один цвет на поверхность, трубу и линию ситуации`() {
    val scene = scene("src/jvmTest/resources/landxml/synthetic/palette-scene.xml")
    val colors = surfaceColors(scene)
    val layerColor = scene.layerInfos.associate { it.name to it.color }
    fun swatch(layer: String) = layerSwatch(colors, layer, layerColor.getValue(layer)).toArgb()

    // три поверхности — первые три цвета таблицы, сети в счёт номера не идут
    val surfaces = scene.surfaces.filter { !it.structure }.map { it.layer }.distinct()
    assertEquals(3, surfaces.size)
    surfaces.forEachIndexed { i, layer -> assertSameColor(ScenePalette.surface(i), swatch(layer), "поверхность $layer") }

    // трубы и колодцы — слой на тип сети, цвет только по типу
    for (type in types) {
      assertSameColor(LandXmlPalette.pipe(type), swatch(PipeNetworksSection.tubeLayer(type)), "трубы $type")
      assertSameColor(LandXmlPalette.struct(type), swatch(PipeNetworksSection.structLayer(type)), "колодцы $type")
    }

    // восемь видов ситуации — первые восемь цветов линий; в сцене линия того же цвета
    val kinds = (0 until 8).map { ScenePalette.line(it) }
    val features = scene.lines.filter { it.color in kinds }
    assertEquals(kinds.toSet(), features.map { it.color }.toSet())
    for (line in features) assertSameColor(line.color, swatch(line.layer), "слой ${line.layer}")
  }
}
