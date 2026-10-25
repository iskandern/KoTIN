package dev.iskandern.kotin.landxml

import dev.iskandern.kotin.model.Journal
import dev.iskandern.kotin.model.PointMark
import dev.iskandern.kotin.model.SceneAttribute
import dev.iskandern.kotin.model.SceneObject
import dev.iskandern.kotin.model.UiText

/**
 * Раздел `Monuments`: пункты геодезической сети. `Monument[name, pntRef, desc, state, type, condition, category]` — знак,
 * поставленный на точку `CgPoint` (`pntRef`): значок и имя слева от него на слое «Пункты сети», в панели — атрибуты знака.
 * Координат у `Monument` нет, поэтому ссылка разрешается в `finish()` ([LandXmlSceneBuilder.STAGE_REFERENCES]);
 * знак без найденной точки пропускается и считается в журнале.
 */
internal class MonumentsSection(
  private val log: (String) -> Unit = Journal::log,
) : LandXmlSection("Monuments", NAMES) {
  private val pending = ArrayList<Pending>()
  private var deferred = false

  override fun read(xml: XmlTokenizer, scene: LandXmlSceneBuilder) {
    if (!deferred) {
      deferred = true
      scene.defer(LandXmlSceneBuilder.STAGE_REFERENCES) { build(scene) }
    }
    val depth = xml.depth
    while (xml.nextChild(depth)) {
      if (xml.nameId == MONUMENT) {
        val attributes = ArrayList<SceneAttribute>()
        for ((label, key) in FIELDS) xml.attrString(key)?.trim()?.takeIf { it.isNotEmpty() }?.let { attributes.add(SceneAttribute(label, it)) }
        pending.add(Pending(xml.attrString(NAME).orEmpty(), xml.attrString(PNT_REF)?.trim().orEmpty(), attributes))
      }
      xml.skipElement()
      scene.tick()
    }
  }

  private fun build(scene: LandXmlSceneBuilder) {
    if (pending.isEmpty()) return
    val layer = scene.layer(LAYER, LandXmlPalette.MONUMENT, LandXmlGroup.POINTS)
    val point = DoubleArray(3)
    var shown = 0
    var missing = 0
    for (m in pending) {
      if (m.pntRef.isEmpty() || !scene.pointNames.find(m.pntRef, point)) {
        missing++
        continue
      }
      val x = point[0]
      val y = point[1]
      val z = point[2]
      // CgPoint без отметки отдаёт z = 0: такой знак плоский, его сажает посадка
      val flat = z == 0.0
      val attributes = ArrayList<SceneAttribute>()
      attributes.add(SceneAttribute("name", m.name))
      attributes.add(SceneAttribute("pntRef", m.pntRef))
      attributes.addAll(m.attributes)
      attributes.add(SceneAttribute("X", AlignmentsSection.format(x)))
      attributes.add(SceneAttribute("Y", AlignmentsSection.format(y)))
      if (!flat) attributes.add(SceneAttribute("Z", AlignmentsSection.format(z)))
      val id = scene.addObject(SceneObject(KIND, m.name, layer, LandXmlPalette.MONUMENT, attributes))
      scene.addPoint(PointMark(layer, LandXmlPalette.MONUMENT, x, y, z, objectId = id), flat)
      // номер и отметку точки уже подписал CgPoints — имя знака слева от значка
      scene.addMarkLabel(layer, x, y, z, m.name, objectId = id, flat = flat)
      shown++
    }
    log(UiText.key("log_monuments", pending.size, shown))
    if (missing > 0) log(UiText.key("log_monuments_missing", missing))
  }

  private class Pending(val name: String, val pntRef: String, val attributes: List<SceneAttribute>)

  companion object {
    const val LAYER = "@layer_monuments"
    const val KIND = "Monument"

    private val NAMES = XmlNames("Monuments", "Monument")
    private const val MONUMENT = 1

    private val NAME = "name".encodeToByteArray()
    private val PNT_REF = "pntRef".encodeToByteArray()
    private val FIELDS = listOf(
      "desc", "state", "type", "condition", "category", "beacon", "beaconProtection", "reference", "originSurvey",
    ).map { it to it.encodeToByteArray() }
  }
}
