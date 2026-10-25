package dev.iskandern.kotin.landxml

import org.junit.Test
import dev.iskandern.kotin.io.ByteArrayInput
import dev.iskandern.kotin.model.SceneGeometry
import dev.iskandern.kotin.model.SceneObject
import java.io.File
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import dev.iskandern.kotin.ru

/** Атрибуты `Feature/Property`, значения по пикетам (скорость, `Roadways`), знаки, углы поворота и пункты сети. */
class LandXmlAttributesTest {

  private val dir = File("src/jvmTest/resources/landxml/attributes")
  private val logs = ArrayList<String>()

  private fun read(bytes: ByteArray): SceneGeometry {
    val log = { message: String -> logs.add(ru(message)); Unit }
    val sections = listOf(AlignmentsSection(log), CgPointsSection(log), PlanFeaturesSection(log), SurfacesSection(log),
      RoadwaysSection(log), MonumentsSection(log))
    return LandXmlReader(ByteArrayInput(bytes), sections, log = log).read()
  }

  private fun read(name: String): SceneGeometry = read(File(dir, name).readBytes())

  private fun SceneGeometry.byKind(kind: String) = objects.filter { it.kind == kind }

  private fun SceneObject.attr(name: String) = attributes.firstOrNull { ru(it.name) == name }?.value?.let(::ru)

  @Test
  fun `SpeedStation - полосы цветом скорости и подписи в местах смены`() {
    val g = read("alignment-speed-stations.xml")
    val speeds = g.byKind(AlignmentsSection.SPEED_KIND)
    // 100, 80, 60, 100 — соседних одинаковых нет, четыре участка
    assertEquals(listOf("SpeedStation 100", "SpeedStation 80", "SpeedStation 60", "SpeedStation 100"), speeds.map { it.name })
    assertNotEquals(speeds[0].color, speeds[2].color)
    assertEquals(speeds[0].color, speeds[3].color)
    assertEquals("0", speeds[0].attr("staStart"))
    assertEquals("400", speeds[0].attr("staEnd"))
    assertEquals("60 … 100", speeds[0].attr("range"))
    // последний участок кончается на конце трассы (1271,24 м)
    assertEquals("1000", speeds[3].attr("staStart"))
    assertEquals("1271.239", speeds[3].attr("staEnd"))
    assertTrue(g.lines.any { it.layer == AlignmentsSection.SPEED_LAYER && it.coords.isNotEmpty() })

    // подпись «SpeedStation 80» — в середине участка ПК4+00…ПК10+00 (точки применения у участка нет), а не у его начала
    val label = g.labels.single { it.text == "SpeedStation 80" }
    assertTrue(label.x > 400.0 && label.x < 1000.0, "середина участка ${label.x}")
  }

  @Test
  fun `трасса - скорость и Feature в панели`() {
    val g = read("alignment-speed-stations.xml")
    val alignment = g.byKind(AlignmentsSection.KIND).single()
    assertEquals("station 0 speed 100; station 400 speed 80; station 871.239 speed 60; station 1000 speed 100", alignment.attr("SpeedStation"))
    assertEquals("IV", alignment.attr("Проектная категория.Категория"))
    assertEquals("3500.5", alignment.attr("Проектная категория.Расчётная интенсивность"))
    assertEquals("gen", alignment.attr("Проектная категория.source"))
    // SpeedStation — одной строкой, а не парами «SpeedStation.station» / «SpeedStation.speed»
    assertTrue(alignment.attributes.none { it.name.startsWith(Features.SPEED_STATION + ".") })
  }

  @Test
  fun `AlignPIs - значки углов поворота с пикетом и отметкой`() {
    val g = read("alignment-speed-stations.xml")
    val pis = g.byKind(AlignmentsSection.PI_KIND)
    assertEquals(listOf("PI1", "PI2", "PI3"), pis.map { it.name })
    assertEquals("700", pis[1].attr("Station"))
    // ВУ2: север 0, восток 700 — X восток, Y север
    assertEquals("700", pis[1].attr("X"))
    assertEquals("0", pis[1].attr("Y"))
    val marks = g.points.filter { it.layer == AlignmentsSection.PI_LAYER }
    assertEquals(3, marks.size)
    // ВУ2: север 0, восток 700; на ПК7+00 профиль идёт на 110 → 98 (после PVI 600)
    val second = marks.single { abs(it.x - 700.0) < 1e-9 && abs(it.y) < 1e-9 }
    assertTrue(second.z > 100.0, "отметка по профилю, а не 0: ${second.z}")
    assertTrue(g.labels.any { it.text == "PI2" })
  }

  @Test
  fun `Roadways - участки по пикетам и знаки`() {
    val g = read("roadway-passport.xml")
    // вид объекта — имя элемента файла: DesignSpeed 3, DesignSpeed85th 1, PostedSpeed 2, DailyTrafficVolume 1; слой — «Трассы: …»
    assertEquals(3, g.byKind("DesignSpeed").size)
    assertEquals(1, g.byKind("DesignSpeed85th").size)
    assertEquals(2, g.byKind("PostedSpeed").size)
    val adt = g.byKind("DailyTrafficVolume").single()
    assertEquals("ADT 5200", adt.name)
    assertEquals("Трассы: интенсивность движения", ru(adt.layer))
    assertEquals("Дорога А", adt.attr("Roadway"))
    assertEquals("Трасса 1", adt.attr("Alignment"))
    // у каждого показателя своя шкала: самый медленный и самый быстрый DesignSpeed различаются по цвету
    val design = g.byKind("DesignSpeed")
    assertNotEquals(design.first { it.attr("speed") == "60" }.color, design.first { it.attr("speed") == "100" }.color)
    assertNotEquals(design[0].color, g.byKind("DesignSpeed85th").single().color)

    val signs = g.byKind(RoadwaysSection.ROAD_SIGN)
    assertEquals(listOf("R2-1", "W1-2", "D3-1"), signs.map { it.name })
    val marks = g.points.filter { it.layer == RoadwaysSection.ROAD_SIGN_LAYER }
    assertEquals(3, marks.size)
    // R2-1: ПК2+00, справа 8 м от оси, идущей на восток → север −8
    val right = marks.single { abs(it.x - 200.0) < 1e-6 }
    assertEquals(-8.0, right.y, 1e-6)
    // W1-2: ПК6+00 на дуге, слева 6 м — внутрь поворота; D3-1: ПК11+00, offset −7 без стороны — слева на 7 м
    val third = marks.single { abs(it.x - 693.0) < 1e-6 }
    assertEquals(300.0 + (1100.0 - (400.0 + Math.PI * 150.0)), third.y, 1e-6)
  }

  @Test
  fun `Roadway без трассы с таким именем пропускается и считается в журнале`() {
    val xml = """<?xml version="1.0"?>
<LandXML xmlns="http://www.landxml.org/schema/LandXML-1.2" version="1.2">
<Roadways><Roadway name="Д" alignmentRefs="Нет такой"><Speeds><DesignSpeed staStart="0" staEnd="100" speed="60"/></Speeds></Roadway></Roadways>
</LandXML>""".encodeToByteArray()
    val g = read(xml)
    assertTrue(g.objects.isEmpty())
    assertTrue(logs.any { "нет Alignment" in it })
  }

  @Test
  fun `Monuments - значок на точке CgPoint, без точки пропуск`() {
    val g = read("points-monuments-features.xml")
    val monuments = g.byKind(MonumentsSection.KIND)
    assertEquals(listOf("ГГС-12", "Репер 7"), monuments.map { it.name })
    assertEquals("existing", monuments[0].attr("state"))
    assertEquals("brass cap", monuments[0].attr("type"))
    assertEquals("ПП1", monuments[0].attr("pntRef"))
    val mark = g.points.single { it.layer == MonumentsSection.LAYER && abs(it.x - 100.0) < 1e-9 }
    assertEquals(50.0, mark.y, 1e-9)
    assertEquals(101.2, mark.z, 1e-9)
    assertTrue(logs.any { "нет точки CgPoint" in it })
  }

  @Test
  fun `PlanFeature - Feature и Property в панели объекта`() {
    val g = read("points-monuments-features.xml")
    val fence = g.byKind(PlanFeaturesSection.KIND).single { it.name == "Ограждение" }
    assertEquals("сталь", fence.attr("Паспорт.Материал"))
    assertEquals("2.5", fence.attr("Паспорт.Высота, м"))
    assertEquals("2019", fence.attr("Паспорт.Год"))
    val pole = g.byKind(PlanFeaturesSection.KIND).single { it.name == "Опора" }
    assertNotNull(pole.attr("Опора ЛЭП.Напряжение, кВ"))
    assertEquals("10", pole.attr("Опора ЛЭП.Напряжение, кВ"))
  }

}
