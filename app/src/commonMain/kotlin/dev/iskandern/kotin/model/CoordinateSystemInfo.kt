package dev.iskandern.kotin.model

/**
 * Система координат, как она записана в файле (LandXML `CoordinateSystem`): атрибуты в порядке записи, значения
 * без перевода и правки. Не проверяется и геометрию не меняет — показывается как есть.
 */
class CoordinateSystemInfo(
  /** Непустые атрибуты элемента: имя → значение. */
  val attributes: List<Pair<String, String>>,
  /** `linearUnit` из `Units` файла, как записан; null — не указан. */
  val fileUnits: String?,
) {
  fun attr(name: String): String? = attributes.firstOrNull { it.first == name }?.second

  /** Код для строки в меню: «EPSG 28406» (с префиксом `EPSG` — как в файле), без кода — «WKT», если есть WKT. */
  val code: String? get() {
    val epsg = attr(EPSG)
    if (epsg != null) return if (epsg.startsWith("EPSG", ignoreCase = true)) epsg else "EPSG $epsg"
    return if (attr(WKT) != null) "WKT" else null
  }

  /** Атрибут, из которого взято [name]; null — имя взято из WKT. */
  val nameSource: String? get() = when {
    attr(NAME) != null -> NAME
    wktName != null -> null
    else -> NAME_FALLBACK.firstOrNull { attr(it) != null } ?: attributes.first().first
  }

  /** Имя системы: `name`, иначе имя из WKT, иначе `desc`, иначе `horizontalDatum`, иначе первое значение. */
  val name: String get() = attr(NAME) ?: wktName ?: NAME_FALLBACK.firstNotNullOfOrNull { attr(it) } ?: attributes.first().second

  // PROJCS["CANQ-M6",GEOGCS[… → «CANQ-M6»
  private val wktName: String? get() {
    val wkt = attr(WKT) ?: return null
    val start = wkt.indexOf("[\"")
    if (start < 0) return null
    val end = wkt.indexOf('"', start + 2)
    return if (end > start + 2) wkt.substring(start + 2, end) else null
  }

  companion object {
    const val NAME = "name"
    const val DESC = "desc"
    const val EPSG = "epsgCode"
    const val WKT = "ogcWktCode"
    const val HORIZONTAL_DATUM = "horizontalDatum"
    const val VERTICAL_DATUM = "verticalDatum"
    const val ELLIPSOID = "ellipsoidName"
    private val NAME_FALLBACK = listOf(DESC, HORIZONTAL_DATUM)
  }
}
