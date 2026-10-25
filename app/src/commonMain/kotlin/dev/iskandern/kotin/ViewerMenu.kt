package dev.iskandern.kotin

import kotlin.math.min
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.HorizontalAlignmentLine
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.erkko68.filament.compose.scene.LinearColor
import org.jetbrains.compose.resources.stringResource
import dev.iskandern.kotin.dxf.DxfColors
import dev.iskandern.kotin.landxml.StationLabels
import dev.iskandern.kotin.resources.Res
import dev.iskandern.kotin.resources.axes
import dev.iskandern.kotin.resources.axes_east_north
import dev.iskandern.kotin.resources.axes_north_east
import dev.iskandern.kotin.resources.close_menu
import dev.iskandern.kotin.resources.elements_title
import dev.iskandern.kotin.resources.flatten_drawing
import dev.iskandern.kotin.resources.hidden_body_faces
import dev.iskandern.kotin.resources.hide_occluded
import dev.iskandern.kotin.resources.home_view
import dev.iskandern.kotin.resources.language
import dev.iskandern.kotin.resources.layers_all
import dev.iskandern.kotin.resources.layers_none
import dev.iskandern.kotin.resources.layers_title
import dev.iskandern.kotin.resources.open_file
import dev.iskandern.kotin.resources.saved_view
import dev.iskandern.kotin.resources.scene_empty
import dev.iskandern.kotin.resources.scene_stats
import dev.iskandern.kotin.resources.scene_truncated
import dev.iskandern.kotin.resources.section_display
import dev.iskandern.kotin.resources.section_settings
import dev.iskandern.kotin.resources.sheet_model
import dev.iskandern.kotin.resources.sheets
import dev.iskandern.kotin.resources.show_all_stations
import dev.iskandern.kotin.resources.show_drawing
import dev.iskandern.kotin.resources.show_labels
import dev.iskandern.kotin.resources.show_line_weights
import dev.iskandern.kotin.resources.show_point_thickness
import dev.iskandern.kotin.resources.show_outlier_pointers
import dev.iskandern.kotin.resources.show_points
import dev.iskandern.kotin.resources.show_stationing
import dev.iskandern.kotin.resources.show_structures
import dev.iskandern.kotin.resources.show_surface
import dev.iskandern.kotin.resources.show_wire
import dev.iskandern.kotin.resources.thin_labels
import dev.iskandern.kotin.resources.labels_auto_off_menu
import dev.iskandern.kotin.resources.labels_auto_thin_menu
import dev.iskandern.kotin.resources.timing
import dev.iskandern.kotin.resources.timing_show
import dev.iskandern.kotin.resources.unread_bodies
import dev.iskandern.kotin.resources.vertical_scale
import dev.iskandern.kotin.scene.RenderScene
import dev.iskandern.kotin.scene.displayColor
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlin.time.TimeSource

private val VERTICAL_SCALES = listOf(1f, 2f, 5f, 10f)

/** Группа списка «Элементы» больше стольких строк сначала свёрнута. */
private const val GROUP_FOLD_SIZE = 10

/** Отступ зависимой строки («Прореживать подписи» под «Подписями»): под текст родителя. */
private val INDENT = 34.dp

/** Строки меню (флажки, заголовки разделов и групп): скругление подсветки и поля по краям. */
private val ROW_SHAPE = RoundedCornerShape(8.dp)
private val ROW_PADDING = 8.dp

/** Высота мягкого края прокрутки меню. */
private val FADE_EDGE = 12.dp

/** Квадратик цвета поверхности: на экране поверхность — в гамме дисплея ([displayColor]), квадратик — так же. */
fun swatchColor(color: LinearColor): Color = displayColor(color).let { Color(it.r, it.g, it.b) }

/** Цвет квадратика слоя: у слоя с поверхностью — из палитры сцены, у остальных — цвет слоя из файла, как он виден в сцене. */
fun layerSwatch(surfaceColors: Map<String, LinearColor>, layer: String, fileColor: Int): Color =
  surfaceColors[layer]?.let(::swatchColor) ?: Color(0xFF000000.toInt() or DxfColors.onBackground(fileColor))

/** Какие разделы меню раскрыты: живёт дольше меню, чтобы закрытое и открытое снова оно было прежним. */
class MenuSections {
  var display by mutableStateOf(true)
  var elements by mutableStateOf(true)
  // раскрыты: здесь язык, его ищут первым делом
  var settings by mutableStateOf(true)

  /** Прокрутка меню: смена языка пересоздаёт интерфейс, а меню остаётся на том же месте. */
  val scroll = ScrollState(0)

  /**
   * Где в видимой области меню (px от её верха) стояла строка языков, когда язык сменили; null — не меняли.
   * Высота разделов с языком меняется, поэтому прокрутка в пикселях не держит место: при первой разметке на новом
   * языке прокрутка поправляется так, чтобы строка языков осталась там, где её нажали ([anchorLanguageRow]).
   */
  internal var languageAnchor: Int? = null

  /** Язык, на котором применить [languageAnchor]: старое меню до смены языка его не трогает. */
  internal var anchorLanguage: AppLanguage? = null

  /** Когда язык сменили: якорь держится [LANGUAGE_ANCHOR_HOLD], пока разметка ещё может меняться (шрифты, поздние строки). */
  internal var anchorSince = TimeSource.Monotonic.markNow()

  // раскрытые группы «Элементов» сцены [groupsScene]: переживают смену языка, у новой сцены — заново
  private var groupsScene: Any? = null
  private var groups = mutableStateMapOf<String, Boolean>()

  /** Какие группы «Элементов» [scene] раскрыты или свёрнуты вручную. */
  fun groupsOf(scene: Any): SnapshotStateMap<String, Boolean> {
    if (scene !== groupsScene) {
      groupsScene = scene
      groups = mutableStateMapOf()
    }
    return groups
  }

  /** Растёт, когда просят показать слой выбранного объекта: список прокручивается к нему ещё раз. */
  var layerRequest by mutableIntStateOf(0)

  /** «Слой: …» в карточке выбранного: раскрыть «Элементы» и прокрутить к слою. */
  fun showLayer() {
    elements = true
    layerRequest++
  }
}

/**
 * Меню: сводка сцены и действия, сворачиваемые разделы «Показывать», «Элементы», «Настройки», внизу версия и FPS.
 * Прокручивается целиком, если не помещается по высоте. [rowHeight] — высота строк-флажков.
 */
@Composable
fun ViewerMenu(
  ui: ViewerUiState,
  viewModel: ViewerViewModel,
  sections: MenuSections,
  language: AppLanguage,
  onLanguage: (AppLanguage) -> Unit,
  onOpenFile: () -> Unit,
  rowHeight: Dp,
  modifier: Modifier = Modifier,
  /** Боковое меню телефона: заголовок «KoTIN» с крестиком, первой строкой — имя файла (карточки файла там нет). */
  onClose: (() -> Unit)? = null,
) {
  val loaded = ui.shownScene
  val scroll = sections.scroll
  // где строка языков в содержимом меню (px от его верха) — из последней разметки
  val languageRow = remember { IntArray(1) { -1 } }
  // поля карточки — снаружи прокрутки: прокрученный текст уходит под мягкий край, а не упирается в рамку
  Column(
    modifier
      .padding(vertical = 6.dp)
      .fadingEdges(scroll)
      .anchorLanguageRow(sections, language, languageRow)
      .verticalScroll(scroll)
      .measureLanguageRow(languageRow)
      .padding(start = 16.dp, end = 16.dp, top = if (onClose != null) 6.dp else 10.dp, bottom = 8.dp),
  ) {
    if (onClose != null) {
      Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("KoTIN", color = KotinColors.Ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(KotinColors.Subtle)) {
          IconBox(KotinIcons.Close, stringResource(Res.string.close_menu), 44.dp, onClose, RoundedCornerShape(12.dp), iconSize = 16.dp)
        }
      }
    }
    Column(Modifier.fillMaxWidth().padding(bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      // система координат — сразу под именем файла; на десктопе и планшете имени в меню нет, строка — первой
      // (без имени и без систем блока нет: пустой добавил бы зазор)
      if (loaded != null && (onClose != null || loaded.geometry.coordinateSystems.isNotEmpty())) Column {
        if (onClose != null) FileTitle(loaded)
        CoordinateSystems(loaded.geometry.coordinateSystems, Modifier.padding(top = if (onClose != null) 1.dp else 0.dp))
      }
      if (loaded != null) SceneSummary(loaded, ui.showMillis)
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ActionButton(stringResource(Res.string.open_file), primary = true, Modifier.weight(1f), onOpenFile)
        if (loaded != null) {
          ActionButton(stringResource(Res.string.home_view), primary = false, Modifier.weight(1f)) { viewModel.sendCameraCommand(CameraCommand.Home) }
        }
      }
      // вид, с которым чертёж сохранён в AutoCAD (VPORT *Active); без него кнопки нет
      loaded?.scene?.savedView?.let { view ->
        ActionButton(stringResource(Res.string.saved_view), primary = false, Modifier.fillMaxWidth()) {
          viewModel.sendCameraCommand(CameraCommand.ShowTop(view.x, view.z, view.height))
        }
      }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(KotinColors.Scene))

    val available = loaded?.let { remember(it.scene) { AvailableControls(it.scene) } }
    if (loaded != null && available != null) {
      MenuSection(stringResource(Res.string.section_display), sections.display, rowHeight, { sections.display = !sections.display }) {
        DisplayChecks(loaded.scene, available, ui.settings, ui.autoLabels, rowHeight, viewModel::changeSettings, viewModel::setFlattenDrawing)
      }
      ElementsSection(loaded.scene, ui.settings, ui.selection?.layer, ui.selection?.objectId to sections.layerRequest, sections, rowHeight, viewModel::changeSettings)
    }

    MenuSection(stringResource(Res.string.section_settings), sections.settings, rowHeight, { sections.settings = !sections.settings }) {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (loaded != null && available != null) {
          // плоская сцена (план, лист — бумага всегда плоская) — высота ноль, чипы ничего не меняют; значение
          // в настройках остаётся — скрытые чипы безвредны
          if (available.verticalScale && loaded.sheet == MODEL_SHEET) {
            SmallLabel(stringResource(Res.string.vertical_scale))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
              VERTICAL_SCALES.forEach { scale ->
                ChoiceChip("${scale.toInt()}×", scale == ui.settings.verticalScale, Modifier.weight(1f)) {
                  viewModel.changeSettings(ui.settings.copy(verticalScale = scale))
                }
              }
            }
          }
          // какая ось файла — север: геодезическая выгрузка «север восток» (X=север, Y=восток) без перестановки
          // повёрнута на 90° и зеркальна
          SmallLabel(stringResource(Res.string.axes), Modifier.padding(top = 4.dp))
          Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ChoiceChip(stringResource(Res.string.axes_east_north), !loaded.swapXY, Modifier.fillMaxWidth()) { if (loaded.swapXY) viewModel.setSwapXY(false) }
            ChoiceChip(stringResource(Res.string.axes_north_east), loaded.swapXY, Modifier.fillMaxWidth()) { if (!loaded.swapXY) viewModel.setSwapXY(true) }
          }
          SheetSwitcher(loaded, viewModel::selectSheet)
        }
        SmallLabel(stringResource(Res.string.language), Modifier.padding(top = 4.dp))
        Row(Modifier.languageRowLine(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          // названия языков — на самих языках
          AppLanguage.entries.forEach {
            ChoiceChip(it.nativeName, it == language, Modifier.weight(1f)) {
              if (it != language) {
                // где строка сейчас в видимой области — туда её вернёт первая разметка на новом языке
                if (languageRow[0] >= 0) {
                  sections.languageAnchor = languageRow[0] - scroll.value
                  sections.anchorLanguage = it
                  sections.anchorSince = TimeSource.Monotonic.markNow()
                }
                onLanguage(it)
              }
            }
          }
        }
      }
    }

    FpsText(Modifier.padding(top = 8.dp))
  }
}

/** Раздел меню: заголовок со стрелкой сворачивает и раскрывает [content]. */
@Composable
private fun MenuSection(title: String, open: Boolean, rowHeight: Dp, onToggle: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
  // область нажатия — как у строк раздела: скруглённая, с теми же полями
  Row(
    Modifier.fillMaxWidth().height(rowHeight + 4.dp).clip(ROW_SHAPE).clickable(role = Role.Button, onClick = onToggle)
      .padding(horizontal = ROW_PADDING),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(title, color = KotinColors.Ink, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
    Icon(KotinIcons.ChevronDown, null, Modifier.size(18.dp).rotate(if (open) 0f else -90f), tint = KotinColors.Muted)
  }
  if (open) Column(content = content)
}

/** Сводка сцены и время чтения: выделяются и копируются. */
@Composable
private fun SceneSummary(loaded: LoadState.Loaded, showMillis: Long?) = SelectionContainer { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
  val scene = loaded.scene
  Text(
    stringResource(
      Res.string.scene_stats,
      scene.surfaces.size, scene.surfaces.sumOf { it.vertexCount }, scene.surfaces.sumOf { it.triangleCount },
      scene.lines.sumOf { it.lineCount }, scene.fills.sumOf { it.triangleCount }, scene.labels.size, scene.points.size,
    ),
    color = KotinColors.Muted, fontSize = 13.sp, lineHeight = 19.sp,
  )
  val timing = stringResource(Res.string.timing, loaded.parseMillis, loaded.buildMillis)
  val showTiming = if (showMillis != null) stringResource(Res.string.timing_show, showMillis) else ""
  Text(timing + showTiming, color = KotinColors.Muted, fontSize = 12.sp, lineHeight = 16.sp)
  val warnings = buildList {
    if (scene.truncated) add(stringResource(Res.string.scene_truncated))
    if (scene.hiddenBodyFaces > 0) add(stringResource(Res.string.hidden_body_faces, scene.hiddenBodyFaces))
    if (scene.unreadBodies > 0) add(stringResource(Res.string.unread_bodies, scene.unreadBodies))
    if (scene.surfaces.isEmpty() && scene.lines.isEmpty() && scene.fills.isEmpty() && scene.labels.isEmpty() && scene.points.isEmpty()) {
      add(stringResource(Res.string.scene_empty))
    }
  }
  warnings.forEach { Text(it, color = KotinColors.Warning, fontSize = 13.sp, lineHeight = 18.sp) }
} }

/** Галочки показа — только для того, что есть в сцене; зависимые — с отступом и только пока «родитель» включён. */
@Composable
private fun DisplayChecks(
  scene: RenderScene, available: AvailableControls, settings: ViewSettings, autoLabels: AutoLabels?, rowHeight: Dp,
  onChange: (ViewSettings) -> Unit, onFlattenDrawing: (Boolean) -> Unit,
) {
  @Composable
  fun check(label: String, checked: Boolean, indent: Boolean = false, change: (Boolean) -> Unit) =
    CheckRow(label, checked, rowHeight, indent = indent, onChange = change)

  if (available.surfaces) {
    check(stringResource(Res.string.show_surface), settings.showSurface) { onChange(settings.copy(showSurface = it)) }
    check(stringResource(Res.string.show_wire), settings.showWire) { onChange(settings.copy(showWire = it)) }
  }
  if (available.drawing) {
    check(stringResource(Res.string.show_drawing), settings.showLines) { onChange(settings.copy(showLines = it)) }
    if (available.lineWeights && settings.showLines) {
      check(stringResource(Res.string.show_line_weights), settings.showLineWeights, indent = true) { onChange(settings.copy(showLineWeights = it)) }
    }
  }
  if (available.labels) {
    check(stringResource(Res.string.show_labels), settings.showLabels) { onChange(settings.copy(showLabels = it)) }
    // польза переключателя сомнительна: отсев (placeLabels в scene/TextMesh.kt) помогает только при множестве
    // перекрывающихся подписей, а как отдельная настройка он пользователю непонятен — поле стоит переосмыслить:
    // включать автоматически по плотности подписей или убрать
    if (settings.showLabels) check(stringResource(Res.string.thin_labels), settings.thinLabels, indent = true) { onChange(settings.copy(thinLabels = it)) }
    // ступень выбрана автоматически по нагрузке файла — пока её не переключили вручную
    if (autoLabels != null) {
      val reason = when (autoLabels.tier) {
        LabelTier.OFF -> stringResource(Res.string.labels_auto_off_menu, groupDigits(autoLabels.labels.toLong()))
        else -> stringResource(Res.string.labels_auto_thin_menu)
      }
      Text(
        reason, color = KotinColors.Muted, fontSize = 12.sp, lineHeight = 16.sp,
        modifier = Modifier.padding(start = INDENT, end = ROW_PADDING, bottom = 6.dp),
      )
    }
  }
  if (available.stationing) {
    val shown = StationLabels.LAYER !in settings.hiddenLayers
    check(stringResource(Res.string.show_stationing), shown) { onChange(settings.withStationing(it, settings.allStations)) }
    if (available.midStations && shown) {
      check(stringResource(Res.string.show_all_stations), settings.allStations, indent = true) { onChange(settings.withStationing(true, it)) }
    }
  }
  if (available.structures) check(stringResource(Res.string.show_structures), settings.showStructures) { onChange(settings.copy(showStructures = it)) }
  if (available.points) {
    check(stringResource(Res.string.show_points), settings.showPoints) { onChange(settings.copy(showPoints = it)) }
    // «Полевик» пишет у каждой точки толщину 2 — по умолчанию эти палки не показываем
    if (available.pointThickness && settings.showPoints) {
      check(stringResource(Res.string.show_point_thickness), settings.showPointThickness, indent = true) { onChange(settings.copy(showPointThickness = it)) }
    }
  }
  if (available.outliers) {
    check(stringResource(Res.string.show_outlier_pointers), settings.showOutlierPointers) { onChange(settings.copy(showOutlierPointers = it)) }
  }
  // прячет подписи и точки глубиной поверхности: нужна видимая поверхность, настоящие отметки и что прятать
  val canOcclude = available.occlusion && settings.showSurface && !scene.drawingFlattened &&
    ((available.labels && settings.showLabels) || (available.points && settings.showPoints))
  if (canOcclude) check(stringResource(Res.string.hide_occluded), settings.hideOccluded) { onChange(settings.copy(hideOccluded = it)) }
  // бессмысленные отметки в плане (±1000 м у внешних ссылок) вытягивают чертёж в столб
  if (available.flattenDrawing) check(stringResource(Res.string.flatten_drawing), scene.drawingFlattened, change = onFlattenDrawing)
}

/** «Элементы (N)» (LandXML, по группам) или «Слои (N)» (DXF): «Все» / «Ни одного» и строки слоёв с цветом. */
@Composable
private fun ElementsSection(
  scene: RenderScene,
  settings: ViewSettings,
  /** Слой выбранного объекта: строка подсвечивается и прокручивается в поле зрения. */
  selectedLayer: String?,
  /** Смена значения заново прокручивает список к [selectedLayer]. */
  focusKey: Any?,
  sections: MenuSections,
  rowHeight: Dp,
  onChange: (ViewSettings) -> Unit,
) {
  // пикетаж — отдельные галочки в «Показывать», в списке его нет, и «Все» / «Ни одного» его не трогают
  val layers = scene.layerInfos.filter { it.name != StationLabels.LAYER && it.name != StationLabels.MID_LAYER }
  if (layers.size < 2) return
  val stationingHidden = settings.hiddenLayers.filter { it == StationLabels.LAYER || it == StationLabels.MID_LAYER }.toSet()
  val surfaceColors = remember(scene) { surfaceColors(scene) }
  // у LandXML слоёв нет — список собирает просмотрщик: «Элементы» по группам; у DXF групп нет — «Слои» одним списком
  val groups = remember(scene) { layers.groupBy { it.group } }
  val title = if (groups.keys.any { it.isNotEmpty() }) Res.string.elements_title else Res.string.layers_title
  MenuSection(stringResource(title, layers.size), sections.elements, rowHeight, { sections.elements = !sections.elements }) {
    // отступ сверху — чтобы подсветка заголовка не упиралась в кнопки; слева — вровень с флажками строк
    Row(Modifier.padding(start = ROW_PADDING, top = 6.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      SmallButton(stringResource(Res.string.layers_all)) { onChange(settings.copy(hiddenLayers = stationingHidden)) }
      SmallButton(stringResource(Res.string.layers_none)) { onChange(settings.copy(hiddenLayers = stationingHidden + layers.map { it.name })) }
    }
    // раскрыта ли группа, если её переключали; иначе большая группа (десятки PlanFeature) свёрнута
    val expanded = sections.groupsOf(scene)
    groups.forEach { (group, infos) -> key(group) {
      // у DXF групп нет — список всегда раскрыт
      val open = group.isEmpty() || (expanded[group] ?: (infos.size <= GROUP_FOLD_SIZE))
      if (group.isNotEmpty()) {
        // выбор объекта разворачивает его группу — иначе строке слоя некуда прокручиваться
        LaunchedEffect(selectedLayer, focusKey) {
          if (!open && infos.any { it.name == selectedLayer }) expanded[group] = true
        }
        Row(
          Modifier.fillMaxWidth().height(36.dp).clip(ROW_SHAPE).clickable(role = Role.Button) { expanded[group] = !open }
            .padding(horizontal = ROW_PADDING),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Icon(KotinIcons.ChevronRight, null, Modifier.size(14.dp).rotate(if (open) 90f else 0f), tint = KotinColors.Muted)
          Text("${localized(group)} (${infos.size})", color = KotinColors.Muted, fontSize = 13.sp)
        }
      }
      // без return@key: возврат из inline-лямбды key Android D8 не переваривает
      if (open) infos.forEach { info ->
        LayerRow(info.name, layerSwatch(surfaceColors, info.name, info.color), settings, selectedLayer, focusKey, rowHeight, onChange)
      }
    } }
  }
}

@Composable
private fun LayerRow(
  layer: String, swatch: Color, settings: ViewSettings, selectedLayer: String?, focusKey: Any?, rowHeight: Dp,
  onChange: (ViewSettings) -> Unit,
) {
  val selected = layer == selectedLayer
  val requester = remember { BringIntoViewRequester() }
  // выбор объекта (или нажатие на «Слой: …» в его карточке) прокручивает меню к слою
  LaunchedEffect(selected, focusKey) { if (selected) requester.bringIntoView() }
  CheckRow(
    localized(layer), layer !in settings.hiddenLayers, rowHeight, swatch = swatch, highlighted = selected,
    modifier = Modifier.bringIntoViewRequester(requester),
  ) { show ->
    val hidden = if (show) settings.hiddenLayers - layer else settings.hiddenLayers + layer
    onChange(settings.copy(hiddenLayers = hidden))
  }
}

/** «Модель | Лист1 | Лист2…»: только если в файле есть листы с содержимым. */
@Composable
private fun SheetSwitcher(loaded: LoadState.Loaded, onSelect: (Int) -> Unit) {
  val sheets = loaded.geometry.sheets
  if (sheets.isEmpty()) return
  SmallLabel(stringResource(Res.string.sheets), Modifier.padding(top = 4.dp))
  Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    ChoiceChip(stringResource(Res.string.sheet_model), loaded.sheet == MODEL_SHEET, Modifier) {
      if (loaded.sheet != MODEL_SHEET) onSelect(MODEL_SHEET)
    }
    sheets.forEachIndexed { i, sheet ->
      ChoiceChip(localized(sheet.name), loaded.sheet == i, Modifier) { if (loaded.sheet != i) onSelect(i) }
    }
  }
}

/** Строка-флажок ~40 dp: квадрат, цвет слоя ([swatch]) и подпись; [highlighted] — слой выбранного объекта. */
@Composable
fun CheckRow(
  label: String, checked: Boolean, height: Dp, indent: Boolean = false, swatch: Color? = null, highlighted: Boolean = false,
  modifier: Modifier = Modifier, onChange: (Boolean) -> Unit,
) {
  Row(
    modifier
      .fillMaxWidth()
      .heightIn(min = height)
      .clip(ROW_SHAPE)
      .background(if (highlighted) KotinColors.Selection else Color.Transparent)
      .toggleable(checked, role = Role.Checkbox, onValueChange = onChange)
      .padding(start = if (indent) INDENT else ROW_PADDING, end = ROW_PADDING, top = 6.dp, bottom = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    CheckBox(checked)
    swatch?.let { Box(Modifier.size(12.dp).background(it, RoundedCornerShape(3.dp))) }
    Text(label, color = KotinColors.Ink, style = MaterialTheme.typography.bodyMedium)
  }
}

@Composable
private fun CheckBox(checked: Boolean) {
  val shape = RoundedCornerShape(5.dp)
  Box(
    Modifier
      .size(20.dp)
      .then(if (checked) Modifier.background(KotinColors.Accent, shape) else Modifier.border(2.dp, KotinColors.CheckOutline, shape)),
    contentAlignment = Alignment.Center,
  ) {
    if (checked) Icon(KotinIcons.Check, null, Modifier.size(14.dp), tint = Color.White)
  }
}

/** Выбор одного из нескольких: выбранный — синяя рамка и подложка. */
@Composable
private fun ChoiceChip(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
  val shape = RoundedCornerShape(10.dp)
  Box(
    modifier
      .height(40.dp)
      .clip(shape)
      .background(if (selected) KotinColors.AccentSoft else KotinColors.Card)
      .border(if (selected) 2.dp else 1.dp, if (selected) KotinColors.Accent else KotinColors.Outline, shape)
      .clickable(role = Role.RadioButton, onClick = onClick)
      .padding(horizontal = 10.dp),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      label, color = if (selected) KotinColors.AccentInk else KotinColors.Ink, style = MaterialTheme.typography.bodyMedium,
      fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1,
    )
  }
}

/** Кнопка меню 44 dp: основная — синяя, вторая — белая с рамкой. */
@Composable
private fun ActionButton(label: String, primary: Boolean, modifier: Modifier, onClick: () -> Unit) {
  val shape = RoundedCornerShape(12.dp)
  Box(
    modifier
      .height(44.dp)
      .clip(shape)
      .then(if (primary) Modifier.background(KotinColors.Accent) else Modifier.background(KotinColors.Card).border(1.dp, KotinColors.Outline, shape))
      .clickable(role = Role.Button, onClick = onClick)
      .padding(horizontal = 12.dp),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      label, color = if (primary) Color.White else KotinColors.Ink, style = MaterialTheme.typography.bodyMedium,
      fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1,
    )
  }
}

@Composable
private fun SmallButton(label: String, onClick: () -> Unit) {
  val shape = RoundedCornerShape(8.dp)
  Box(
    Modifier.height(32.dp).clip(shape).border(1.dp, KotinColors.Outline, shape).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 12.dp),
    contentAlignment = Alignment.Center,
  ) { Text(label, color = KotinColors.Ink, fontSize = 13.sp) }
}

@Composable
private fun SmallLabel(text: String, modifier: Modifier = Modifier) {
  Text(text, modifier, color = KotinColors.Muted, fontSize = 13.sp)
}

@Composable
private fun FpsText(modifier: Modifier = Modifier) {
  var fps by remember { mutableIntStateOf(0) }
  LaunchedEffect(Unit) {
    var frames = 0
    var windowStart = TimeSource.Monotonic.markNow()
    while (true) {
      withFrameNanos { }
      frames++
      val elapsed = windowStart.elapsedNow()
      if (elapsed < 1.seconds) continue
      fps = (frames / elapsed.toDouble(DurationUnit.SECONDS)).toInt()
      frames = 0
      windowStart = TimeSource.Monotonic.markNow()
    }
  }
  // версию можно скопировать
  SelectionContainer(modifier) {
    Text("v$APP_VERSION · FPS $fps", color = KotinColors.Muted, fontSize = 12.sp, fontFamily = MonoFamily)
  }
}

/**
 * Мягкие края прокручиваемой области: у верхнего и нижнего края, если за ним есть ещё, содержимое уходит под
 * градиент цвета карточки. Не маской в отдельном буфере (CompositingStrategy.Offscreen): прямоугольник такого буфера
 * на web не обрезался по скруглению карточки и торчал в её нижнем углу, а на каждом кадре стоил лишнего прохода.
 */
private fun Modifier.fadingEdges(scroll: ScrollState): Modifier = drawWithContent {
  drawContent()
  val edge = FADE_EDGE.toPx()
  if (scroll.value > 0) {
    drawRect(
      Brush.verticalGradient(listOf(KotinColors.Card, Color.Transparent), startY = 0f, endY = edge),
      size = Size(size.width, edge),
    )
  }
  if (scroll.value < scroll.maxValue) {
    drawRect(
      Brush.verticalGradient(listOf(Color.Transparent, KotinColors.Card), startY = size.height - edge, endY = size.height),
      topLeft = Offset(0f, size.height - edge), size = Size(size.width, edge),
    )
  }
}

/** Сколько после смены языка держится якорь строки языков. */
private val LANGUAGE_ANCHOR_HOLD = 1.seconds

/** Верх строки языков: линия выравнивания доходит через все колонки меню до содержимого прокрутки. */
private val LanguageRowTop = HorizontalAlignmentLine(::min)

private fun Modifier.languageRowLine(): Modifier = layout { measurable, constraints ->
  val placeable = measurable.measure(constraints)
  layout(placeable.width, placeable.height, mapOf(LanguageRowTop to 0)) { placeable.place(0, 0) }
}

/** Запоминает в [row] положение строки языков в содержимом меню (−1 — её нет), при каждом измерении. */
private fun Modifier.measureLanguageRow(row: IntArray): Modifier = layout { measurable, constraints ->
  val placeable = measurable.measure(constraints)
  row[0] = placeable[LanguageRowTop].takeIf { it != AlignmentLine.Unspecified } ?: -1
  layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}

/**
 * Якорь строки языков при смене языка — снаружи [verticalScroll]: она в измерении уже обновила наибольшую прокрутку,
 * а значение читает только при размещении. Поправка между ними попадает в тот же кадр, что и новая разметка, и меню
 * ни на кадр не сдвигается (раньше докрутка шла кадром позже — меню дёргалось).
 */
private fun Modifier.anchorLanguageRow(sections: MenuSections, language: AppLanguage, row: IntArray): Modifier = layout { measurable, constraints ->
  val placeable = measurable.measure(constraints)
  val anchor = sections.languageAnchor
  if (anchor != null && sections.anchorSince.elapsedNow() > LANGUAGE_ANCHOR_HOLD) sections.languageAnchor = null
  // каждая разметка на новом языке в первую секунду (тексты и шрифты могут догружаться) — строка на прежнем месте
  if (anchor != null && sections.anchorLanguage == language && row[0] >= 0) {
    val delta = row[0] - anchor - sections.scroll.value
    if (delta != 0) sections.scroll.dispatchRawDelta(delta.toFloat())
  }
  layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}
