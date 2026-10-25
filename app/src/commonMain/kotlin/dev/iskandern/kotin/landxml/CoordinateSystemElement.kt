package dev.iskandern.kotin.landxml

/**
 * Элемент `CoordinateSystem`: все непустые атрибуты как в файле, без проверки (решение владельца 24.10.2026).
 * Дети (`Feature` у FAA) пропускаются. Строки обрезаются до [MAX_LENGTH], WKT (`ogcWktCode`) хранится целиком.
 */
internal object CoordinateSystemElement {
  const val ELEMENT = "CoordinateSystem"
  private const val MAX_LENGTH = 300

  /** Вызывается на START элемента, возвращается на его END. Пусто — атрибутов нет. */
  fun read(xml: XmlTokenizer): List<Pair<String, String>> {
    val attributes = xml.attributes().mapNotNull { (name, raw) ->
      val value = raw.trim()
      when {
        value.isEmpty() -> null
        name == "ogcWktCode" || value.length <= MAX_LENGTH -> name to value
        else -> name to value.take(MAX_LENGTH - 1) + "…"
      }
    }
    xml.skipElement()
    return attributes
  }
}
