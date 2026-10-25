package dev.iskandern.kotin.landxml

import dev.iskandern.kotin.model.SceneAttribute

/** `Feature[name, code, source]` с парами `Property[label, value]`: произвольные атрибуты, которые пишут экспортёры. */
internal class FeatureData(
  val name: String,
  val code: String,
  val source: String,
  /** Пары «метка — значение» по порядку файла; значения как в файле. */
  val properties: List<Pair<String, String>>,
) {
  fun property(label: String): String? = properties.firstOrNull { it.first == label }?.second
}

internal object Features {
  /** Не больше стольких `Property` у одного `Feature` и `Feature` у одного объекта: остальное пропускается. */
  const val MAX_PROPERTIES = 64
  const val MAX_FEATURES = 64

  /** Имя, которым Civil 3D помечает проектную скорость по пикетам трассы (рельсовый `Cant/SpeedStation` без `Cant`). */
  const val SPEED_STATION = "SpeedStation"

  private val NAME = "name".encodeToByteArray()
  private val CODE = "code".encodeToByteArray()
  private val SOURCE = "source".encodeToByteArray()
  private val LABEL = "label".encodeToByteArray()
  private val VALUE = "value".encodeToByteArray()

  /** Читает `Feature`, на начале которого стоит токенизатор, до его конца. */
  fun read(xml: XmlTokenizer): FeatureData {
    val name = xml.attrString(NAME).orEmpty().trim()
    val code = xml.attrString(CODE).orEmpty().trim()
    val source = xml.attrString(SOURCE).orEmpty().trim()
    val properties = ArrayList<Pair<String, String>>()
    val depth = xml.depth
    while (xml.nextChild(depth)) {
      // Property[label, value]; вложенное неизвестное пропускаем
      val label = xml.attrString(LABEL)
      val value = xml.attrString(VALUE)
      if (label != null && value != null && properties.size < MAX_PROPERTIES) properties.add(label.trim() to value.trim())
      xml.skipElement()
    }
    return FeatureData(name, code, source, properties)
  }

  /** Числа в значениях — в короткой записи ([AlignmentsSection.format]), остальное как есть. */
  fun formatValue(value: String): String = value.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { AlignmentsSection.format(it) } ?: value

  /**
   * Атрибуты панели из [features]: «Feature.метка» (или «имя.метка» — по имени `Feature`) → значение.
   * [skip] — имена `Feature`, которые объект показывает по-своему.
   */
  fun attributes(features: List<FeatureData>, out: MutableList<SceneAttribute>, skip: String? = null) {
    for (f in features) {
      if (f.name == skip) continue
      val prefix = f.name.ifEmpty { "Feature" }
      if (f.code.isNotEmpty()) out.add(SceneAttribute("$prefix.code", f.code))
      if (f.source.isNotEmpty()) out.add(SceneAttribute("$prefix.source", f.source))
      for ((label, value) in f.properties) out.add(SceneAttribute("$prefix.$label", formatValue(value)))
    }
  }
}
