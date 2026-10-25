package dev.iskandern.kotin.landxml

import dev.iskandern.kotin.model.Journal
import dev.iskandern.kotin.model.NO_OBJECT
import dev.iskandern.kotin.model.PointMark
import dev.iskandern.kotin.model.SceneAttribute
import dev.iskandern.kotin.model.SceneObject
import dev.iskandern.kotin.model.UiText

/**
 * Раздел `CgPoints`: `CgPoint[name, oID, code, desc]` «север восток [отметка]» → значок точки и таблица имён для ссылок
 * ([LandXmlSceneBuilder.pointNames]). Группы `CgPoints[name]` могут быть вложены: слой — имя ближайшей именованной группы,
 * иначе «Точки». Без отметки z = 0 — накопитель сажает такую точку на поверхность файла, вне её сцена
 * поднимет на уровень поверхности, как 2D-точку DXF. `Survey` читает [SurveySection].
 *
 * Подпись (номер над отметкой) и объект сцены заводятся не больше чем для [MAX_DETAILED] точек: это объекты, и на миллионе
 * точек они съедают память; дальше остаются значок и запись в таблице имён. Высоту подписей ставит [LandXmlSceneBuilder.finish].
 */
internal class CgPointsSection(
  private val log: (String) -> Unit = Journal::log,
) : LandXmlSection("CgPoints", NAMES) {
  private val point = DoubleArray(3)
  private var registered = false
  private var count = 0
  private var skipped = 0
  // имя точки: буфер токенизатора сдвигается при чтении текста, поэтому имя копируется сюда
  private var scratch = ByteArray(64)

  override fun read(xml: XmlTokenizer, scene: LandXmlSceneBuilder) {
    if (!registered) {
      registered = true
      scene.defer(LandXmlSceneBuilder.STAGE_REFERENCES) { report() }
    }
    readGroup(xml, scene, DEFAULT_LAYER)
  }

  private fun report() {
    log(if (count > MAX_DETAILED) UiText.key("log_cgpoints_limited", count, MAX_DETAILED) else UiText.key("log_cgpoints", count))
    if (skipped > 0) log(UiText.key("log_cgpoints_skipped", skipped))
  }

  // группа CgPoints: точки и вложенные группы
  private fun readGroup(xml: XmlTokenizer, scene: LandXmlSceneBuilder, parentLayer: String) {
    val layer = xml.attrString(NAME)?.takeIf { it.isNotBlank() } ?: parentLayer
    val depth = xml.depth
    while (xml.nextChild(depth)) {
      when (xml.nameId) {
        CG_POINT -> readPoint(xml, scene, layer)
        CG_POINTS -> readGroup(xml, scene, layer)
        else -> xml.skipElement()
      }
      scene.tick()
    }
  }

  private fun readPoint(xml: XmlTokenizer, scene: LandXmlSceneBuilder, layerName: String) {
    // атрибуты читаются до текста; имя — байтами, String только для тех, что попадут на экран
    val detailed = count < MAX_DETAILED
    var code = ""
    var desc = ""
    var nameString: String? = null
    var nameLength = 0
    if (xml.findAttr(NAME)) {
      if (isPlain(xml.valueBuffer, xml.valueStart, xml.valueEnd)) {
        nameLength = xml.valueEnd - xml.valueStart
        if (nameLength > scratch.size) scratch = ByteArray(nameLength * 2)
        xml.valueBuffer.copyInto(scratch, 0, xml.valueStart, xml.valueEnd)
      } else {
        // кодировка не UTF-8 или сущности: ссылки ищут по строке, поэтому имя — через декодер
        val decoded = xml.attrString(NAME).orEmpty()
        nameString = decoded
        val bytes = decoded.encodeToByteArray()
        nameLength = bytes.size
        if (nameLength > scratch.size) scratch = ByteArray(nameLength * 2)
        bytes.copyInto(scratch)
      }
    }
    if (detailed) {
      code = xml.attrString(CODE).orEmpty()
      desc = xml.attrString(DESC).orEmpty()
    }
    val read = LandXmlCoord.readPoint(xml, point)
    if (read == 0) {
      skipped++
      return
    }
    scene.pointNames.add(scratch, 0, nameLength, point[0], point[1], point[2])
    val x = point[0]
    val y = point[1]
    val z = point[2]
    val layer = scene.layer(layerName, LandXmlPalette.POINT, LandXmlGroup.POINTS)
    var objectId = NO_OBJECT
    if (detailed) {
      val name = nameString ?: scratch.decodeToString(0, nameLength)
      val attributes = ArrayList<SceneAttribute>(6)
      attributes.add(SceneAttribute("@attr_name", name))
      if (code.isNotEmpty()) attributes.add(SceneAttribute("@attr_code", code))
      if (desc.isNotEmpty()) attributes.add(SceneAttribute("@attr_description", desc))
      // координаты в осях файла: X — второе число («восток»), Y — первое («север»); какая ось север, подписывает карточка
      attributes.add(SceneAttribute("X", AlignmentsSection.format(x)))
      attributes.add(SceneAttribute("Y", AlignmentsSection.format(y)))
      if (read == 3) attributes.add(SceneAttribute("Z", AlignmentsSection.format(z)))
      objectId = scene.addObject(SceneObject(KIND, name, layer, LandXmlPalette.POINT, attributes))
      // номер над отметкой (код — в свойствах точки)
      scene.addPointLabel(layer, x, y, z, name, hasElevation = read == 3, objectId = objectId, flat = read < 3)
    }
    scene.addPoint(PointMark(layer, LandXmlPalette.POINT, x, y, z, objectId = objectId), flat = read < 3)
    count++
  }

  // обычный ASCII без сущностей: байты те же, что дала бы строка в UTF-8
  private fun isPlain(buf: ByteArray, start: Int, end: Int): Boolean {
    for (i in start until end) {
      val b = buf[i]
      if (b < 0 || b == AMP) return false
    }
    return true
  }

  companion object {
    const val DEFAULT_LAYER = "@layer_points"
    const val KIND = "CgPoint"

    /** Подписи и объекты — не больше чем для стольких точек, остальные только значком. */
    const val MAX_DETAILED = 200_000

    private val NAMES = XmlNames("CgPoints", "CgPoint", "Survey")
    private const val CG_POINTS = 0
    private const val CG_POINT = 1

    private val NAME = "name".encodeToByteArray()
    private val CODE = "code".encodeToByteArray()
    private val DESC = "desc".encodeToByteArray()
    private const val AMP = '&'.code.toByte()
  }
}
