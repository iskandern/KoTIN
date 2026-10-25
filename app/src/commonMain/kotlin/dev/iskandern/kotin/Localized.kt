package dev.iskandern.kotin

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import dev.iskandern.kotin.model.UiText
import dev.iskandern.kotin.resources.Res
import dev.iskandern.kotin.resources.attr_x_east
import dev.iskandern.kotin.resources.attr_x_north
import dev.iskandern.kotin.resources.attr_y_east
import dev.iskandern.kotin.resources.attr_y_north
import dev.iskandern.kotin.resources.allStringResources

/** Текст для показа: ключ [UiText] — перевод из ресурсов, остальное — как есть. */
@Composable
fun localized(text: String): String {
  if (text.isEmpty() || text[0] != UiText.MARK) return text
  // ключ — имя ресурса: буквы, цифры и «_»
  var end = 1
  while (end < text.length && (text[end].isLetterOrDigit() || text[end] == '_')) end++
  val resource = Res.allStringResources[text.substring(1, end)] ?: return text
  val rest = text.substring(end)
  if (rest.isEmpty() || rest[0] != UiText.SEPARATOR) return stringResource(resource) + rest
  val args = rest.substring(1).split(UiText.SEPARATOR)
  return stringResource(resource, *args.toTypedArray())
}

/**
 * Название свойства для карточки выбранного: ключ [UiText] — перевод, имя атрибута LandXML («pntRef», «staStart»…) —
 * перевод по [LANDXML_ATTRIBUTES], остальное (теги атрибутов DXF, свойства из файла) — как есть. Координаты «X», «Y» —
 * с пояснением, какая ось север: по переключателю осей ([swapXY] — X север, Y восток).
 */
@Composable
fun attributeName(name: String, swapXY: Boolean = false): String = when (name) {
  "X" -> stringResource(if (swapXY) Res.string.attr_x_north else Res.string.attr_x_east)
  "Y" -> stringResource(if (swapXY) Res.string.attr_y_east else Res.string.attr_y_north)
  else -> LANDXML_ATTRIBUTES[name]?.let { stringResource(it) } ?: localized(name)
}

/**
 * Имена атрибутов LandXML, которые разделы кладут в свойства как есть. Регистр важен: теги атрибутов DXF AutoCAD
 * пишет заглавными, и «NAME» блока не переводится.
 */
private val LANDXML_ATTRIBUTES: Map<String, StringResource> by lazy {
  val all = Res.allStringResources
  mapOf(
    "name" to "attr_name", "pntRef" to "attr_point_ref", "desc" to "attr_description", "state" to "attr_state",
    "type" to "attr_type", "condition" to "attr_condition", "category" to "attr_category", "beacon" to "attr_beacon",
    "beaconProtection" to "attr_beacon_protection", "reference" to "attr_reference", "originSurvey" to "attr_origin_survey",
    "Roadway" to "attr_roadway", "Alignment" to "attr_alignment",
    "staStart" to "attr_start_station", "staEnd" to "attr_end_station", "sideofRoad" to "attr_side_of_road",
    "range" to "attr_range", "MUTCDCode" to "attr_sign_code", "station" to "attr_station", "Station" to "attr_station",
    "offset" to "attr_offset", "speed" to "attr_speed", "speedLimit" to "attr_speed_limit", "ADT" to "attr_traffic",
    "SpeedStation" to "attr_speed_stations", "id" to "attr_id", "stationName" to "attr_station_name",
    "instrumentHeight" to "attr_instrument_height", "PVI" to "attr_pvi_count",
  ).mapValues { all.getValue(it.value) }
}
