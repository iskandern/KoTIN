package dev.iskandern.kotin.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.iskandern.kotin.model.CoordinateSystemInfo
import dev.iskandern.kotin.resources.Res
import dev.iskandern.kotin.resources.crs_code
import dev.iskandern.kotin.resources.crs_datum
import dev.iskandern.kotin.resources.crs_description
import dev.iskandern.kotin.resources.crs_ellipsoid
import dev.iskandern.kotin.resources.crs_expand
import dev.iskandern.kotin.resources.crs_file_units
import dev.iskandern.kotin.resources.crs_heights
import dev.iskandern.kotin.resources.crs_note
import dev.iskandern.kotin.resources.crs_system
import dev.iskandern.kotin.web.dom.Div
import dev.iskandern.kotin.web.dom.Span
import dev.iskandern.kotin.web.dom.Text
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Система координат из файла — строка в меню под именем файла: «EPSG 28406 · имя ⌄» (без кода — «WKT · имя» или
 * только имя); нажатие раскрывает все атрибуты, как они записаны, по блоку на элемент. Ничего не проверяется.
 * Систем нет — ничего не показывается. Новый файл открывается свёрнутым.
 */
@Composable
fun CoordinateSystems(systems: List<CoordinateSystemInfo>) {
  if (systems.isEmpty()) return
  var expanded by remember(systems) { mutableStateOf(false) }
  val first = systems.first()
  val code = first.code
  val description = stringResource(Res.string.crs_expand)
  Div {
    // без подсветки нажатия: подложка у строки выглядела бы кнопкой (решение владельца)
    Div({
      classes("crs-row")
      attr("role", "button")
      attr("aria-label", description)
      onClick { expanded = !expanded }
    }) {
      if (code != null) Span({ classes("crs-code") }) { Text(code) }
      Span({ classes("crs-name", "ellipsis") }) { Text(if (code != null) "· ${first.name}" else first.name) }
      IconView(Icon.ChevronDown, 14, "var(--muted)", if (expanded) 180f else 0f)
    }
    if (expanded) Div({
      classes("selectable")
      style { property("padding-top", "8px"); property("display", "flex"); property("flex-direction", "column"); property("gap", "8px") }
    }) {
      systems.forEach { SystemBlock(it) }
      Div({ style { property("color", "var(--muted)"); property("font-size", "12px"); property("line-height", "16px") } }) {
        Text(stringResource(Res.string.crs_note))
      }
    }
  }
}

/** Ячейка сетки: подпись, значение; [mono] — код, [full] — во всю ширину, [oneLine] — одна строка (начало WKT). */
private class Cell(val label: String, val value: String, val mono: Boolean = false, val full: Boolean = false, val oneLine: Boolean = false)

/** Блок одного элемента: «Система» во всю ширину, затем то, что есть в файле, по две ячейки в строке. */
@Composable
private fun SystemBlock(system: CoordinateSystemInfo) {
  val cells = ArrayList<Cell>()
  cells += Cell(stringResource(Res.string.crs_system), system.name, full = true)
  val epsg = system.attr(CoordinateSystemInfo.EPSG)
  val wkt = system.attr(CoordinateSystemInfo.WKT)
  if (epsg != null) cells += Cell(stringResource(Res.string.crs_code), system.code!!, mono = true)
  if (wkt != null) cells += Cell(if (epsg == null) stringResource(Res.string.crs_code) else "WKT", wkt, mono = true, full = true, oneLine = true)
  val skip = setOf(CoordinateSystemInfo.EPSG, CoordinateSystemInfo.WKT, system.nameSource)
  for ((name, value) in system.attributes.sortedBy { ORDER.indexOf(it.first).let { i -> if (i < 0) ORDER.size else i } }) {
    if (name in skip) continue
    val label = LABELS[name]?.let { stringResource(it) } ?: name
    cells += Cell(label, value)
  }
  system.fileUnits?.let { cells += Cell(stringResource(Res.string.crs_file_units), it) }

  Div({ classes("crs-cells") }) {
    var i = 0
    while (i < cells.size) {
      val cell = cells[i]
      val next = cells.getOrNull(i + 1)
      if (cell.full || next == null || next.full) {
        CellBox(cell)
        i++
      } else {
        Div({ classes("crs-pair") }) {
          CellBox(cell)
          CellBox(next)
        }
        i += 2
      }
    }
  }
}

@Composable
private fun CellBox(cell: Cell) {
  Div({ classes("crs-cell") }) {
    Div({ classes("label") }) { Text(cell.label) }
    Div({
      classes("value")
      if (cell.mono) classes("mono")
      if (cell.oneLine) classes("one-line", "ellipsis")
    }) { Text(cell.value) }
  }
}

/** Подписи известных атрибутов, в порядке показа; остальные — после них, именем атрибута, как в файле. */
private val LABELS: Map<String, StringResource> = mapOf(
  CoordinateSystemInfo.HORIZONTAL_DATUM to Res.string.crs_datum,
  // ePlan: <CoordinateSystem datum="OfPlan" …/>
  "datum" to Res.string.crs_datum,
  CoordinateSystemInfo.VERTICAL_DATUM to Res.string.crs_heights,
  CoordinateSystemInfo.ELLIPSOID to Res.string.crs_ellipsoid,
  CoordinateSystemInfo.DESC to Res.string.crs_description,
)
private val ORDER = LABELS.keys.toList()
