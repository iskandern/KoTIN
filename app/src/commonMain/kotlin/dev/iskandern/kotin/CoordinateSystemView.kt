package dev.iskandern.kotin

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
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

/**
 * Система координат из файла — строка в меню под именем файла: «EPSG 28406 · имя ⌄» (без кода — «WKT · имя» или
 * только имя); нажатие раскрывает все атрибуты, как они записаны, по блоку на элемент. Ничего не проверяется.
 * Систем нет — ничего не показывается. Новый файл открывается свёрнутым.
 */
@Composable
fun CoordinateSystems(systems: List<CoordinateSystemInfo>, modifier: Modifier = Modifier) {
  if (systems.isEmpty()) return
  var expanded by remember(systems) { mutableStateOf(false) }
  val first = systems.first()
  val code = first.code
  val description = stringResource(Res.string.crs_expand)
  Column(modifier) {
    Row(
      // без подсветки нажатия: подложка у строки выглядела бы кнопкой (решение владельца)
      Modifier.fillMaxWidth().clickable(
        remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClickLabel = description,
      ) { expanded = !expanded },
      horizontalArrangement = Arrangement.spacedBy(4.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      if (code != null) {
        Text(code, color = KotinColors.Ink, fontFamily = MonoFamily, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false)
      }
      Text(
        if (code != null) "· ${first.name}" else first.name, Modifier.weight(1f),
        color = KotinColors.Muted, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
      )
      Icon(KotinIcons.ChevronDown, null, Modifier.size(14.dp).rotate(if (expanded) 180f else 0f), tint = KotinColors.Muted)
    }
    if (expanded) SelectionContainer {
      Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        systems.forEach { SystemBlock(it) }
        Text(stringResource(Res.string.crs_note), color = KotinColors.Muted, fontSize = 12.sp, lineHeight = 16.sp)
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

  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    var i = 0
    while (i < cells.size) {
      val cell = cells[i]
      val next = cells.getOrNull(i + 1)
      if (cell.full || next == null || next.full) {
        CellBox(cell, Modifier.fillMaxWidth())
        i++
      } else {
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
          CellBox(cell, Modifier.weight(1f).fillMaxHeight())
          CellBox(next, Modifier.weight(1f).fillMaxHeight())
        }
        i += 2
      }
    }
  }
}

@Composable
private fun CellBox(cell: Cell, modifier: Modifier) {
  Column(modifier.clip(RoundedCornerShape(8.dp)).background(KotinColors.Subtle).padding(horizontal = 8.dp, vertical = 6.dp)) {
    Text(cell.label, color = KotinColors.Muted, fontSize = 12.sp, lineHeight = 16.sp)
    Text(
      cell.value, color = KotinColors.Ink, fontFamily = if (cell.mono) MonoFamily else null,
      fontSize = if (cell.oneLine) 12.sp else 13.sp, lineHeight = if (cell.oneLine) 16.sp else 18.sp,
      maxLines = if (cell.oneLine) 1 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis,
    )
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
