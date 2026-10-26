package dev.iskandern.kotin.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.withFrameNanos
import dev.iskandern.kotin.APP_VERSION
import dev.iskandern.kotin.AppLanguage
import dev.iskandern.kotin.AutoLabels
import dev.iskandern.kotin.AvailableControls
import dev.iskandern.kotin.CameraCommand
import dev.iskandern.kotin.LabelTier
import dev.iskandern.kotin.LoadState
import dev.iskandern.kotin.MODEL_SHEET
import dev.iskandern.kotin.ViewSettings
import dev.iskandern.kotin.ViewerUiState
import dev.iskandern.kotin.ViewerViewModel
import dev.iskandern.kotin.dxf.DxfColors
import dev.iskandern.kotin.groupDigits
import dev.iskandern.kotin.landxml.StationLabels
import dev.iskandern.kotin.localized
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
import dev.iskandern.kotin.resources.labels_auto_off_menu
import dev.iskandern.kotin.resources.labels_auto_thin_menu
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
import dev.iskandern.kotin.resources.show_outlier_pointers
import dev.iskandern.kotin.resources.show_point_thickness
import dev.iskandern.kotin.resources.show_points
import dev.iskandern.kotin.resources.show_stationing
import dev.iskandern.kotin.resources.show_structures
import dev.iskandern.kotin.resources.show_surface
import dev.iskandern.kotin.resources.show_wire
import dev.iskandern.kotin.resources.thin_labels
import dev.iskandern.kotin.resources.timing
import dev.iskandern.kotin.resources.timing_show
import dev.iskandern.kotin.resources.unread_bodies
import dev.iskandern.kotin.resources.vertical_scale
import dev.iskandern.kotin.scene.RenderScene
import dev.iskandern.kotin.scene.displayColor
import dev.iskandern.kotin.surfaceColors
import dev.iskandern.kotin.web.dom.AttrsScope
import dev.iskandern.kotin.web.dom.Div
import dev.iskandern.kotin.web.dom.Span
import dev.iskandern.kotin.web.dom.Text
import dev.iskandern.kotin.web.dom.px
import dev.iskandern.kotin.withStationing
import io.github.erkko68.filament.compose.scene.LinearColor
import org.jetbrains.compose.resources.stringResource
import org.w3c.dom.HTMLElement
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlin.time.TimeSource

private val VERTICAL_SCALES = listOf(1f, 2f, 5f, 10f)

/** Группа списка «Элементы» больше стольких строк сначала свёрнута. */
private const val GROUP_FOLD_SIZE = 10

/** Квадратик цвета поверхности: на экране поверхность — в гамме дисплея ([displayColor]), квадратик — так же. */
fun swatchColor(color: LinearColor): String = displayColor(color).let { c ->
  "rgb(${(c.r * 255).roundToInt()}, ${(c.g * 255).roundToInt()}, ${(c.b * 255).roundToInt()})"
}

/** Цвет квадратика слоя: у слоя с поверхностью — из палитры сцены, у остальных — цвет слоя из файла, как он виден в сцене. */
fun layerSwatch(surfaceColors: Map<String, LinearColor>, layer: String, fileColor: Int): String =
  surfaceColors[layer]?.let(::swatchColor) ?: cssColor(DxfColors.onBackground(fileColor))

/** Какие разделы меню раскрыты: живёт дольше меню, чтобы закрытое и открытое снова оно было прежним. */
class MenuSections {
  var display by mutableStateOf(true)
  var elements by mutableStateOf(true)
  // раскрыты: здесь язык, его ищут первым делом
  var settings by mutableStateOf(true)

  /** Прокрутка меню, px: закрытое и открытое снова меню — на том же месте. */
  var scroll = 0.0

  // раскрытые группы «Элементов» сцены [groupsScene]: у новой сцены — заново
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
  rowHeight: Int,
  /** Боковое меню телефона: заголовок «KoTIN» с крестиком, первой строкой — имя файла (карточки файла нет). */
  onClose: (() -> Unit)? = null,
) {
  val loaded = ui.shownScene
  val scroller = remember { arrayOfNulls<HTMLElement>(1) }
  val languageRow = remember { arrayOfNulls<HTMLElement>(1) }
  var fadeTop by remember { mutableStateOf(false) }
  var fadeBottom by remember { mutableStateOf(false) }
  fun updateFades() {
    val s = scroller[0] ?: return
    fadeTop = s.scrollTop > 0
    fadeBottom = s.scrollTop + s.clientHeight < s.scrollHeight - 0.5
  }
  // смена языка: строка языков остаётся там, где её нажали (высота разделов над ней меняется)
  val anchor = remember { LanguageAnchor() }
  Div({ classes("menu") }) {
    Div({
      classes("menu-scroll")
      if (onClose != null) classes("in-drawer")
      // края прокрутки гаснут маской самой прокрутки, а не белыми накладками: те в Safari вылезали за скругление меню
      classIf(fadeTop, "fade-top")
      classIf(fadeBottom, "fade-bottom")
      on("scroll") {
        sections.scroll = scroller[0]?.scrollTop ?: 0.0
        updateFades()
      }
    }, ref = { element ->
      val s = element as HTMLElement
      scroller[0] = s
      s.scrollTop = sections.scroll
      val observer = observeSize(s) { updateFades() }
      ({ disconnectObserver(observer) })
    }) {
      if (onClose != null) {
        Div({ classes("menu-head"); ui("menu-head") }) {
          Div({ classes("title") }) { Text("KoTIN") }
          Div({ classes("close"); ui("menu-close"); style { property("width", "44px"); property("height", "44px") } }) {
            IconBox(Icon.Close, stringResource(Res.string.close_menu), 44, onClose, "12px", iconSize = 16)
          }
        }
      }
      Div({ classes("menu-top"); ui("menu-top") }) {
        // система координат — сразу под именем файла; на десктопе и планшете имени в меню нет, строка — первой
        if (loaded != null && (onClose != null || loaded.geometry.coordinateSystems.isNotEmpty())) Div {
          if (onClose != null) FileTitle(loaded)
          CoordinateSystems(loaded.geometry.coordinateSystems)
        }
        if (loaded != null) SceneSummary(loaded, ui.showMillis)
        Div({ classes("actions") }) {
          ActionButton(stringResource(Res.string.open_file), primary = true, tag = "menu-open-file", onClick = onOpenFile)
          if (loaded != null) {
            ActionButton(stringResource(Res.string.home_view), primary = false, tag = "menu-home") { viewModel.sendCameraCommand(CameraCommand.Home) }
          }
        }
        // вид, с которым чертёж сохранён в AutoCAD (VPORT *Active); без него кнопки нет
        loaded?.scene?.savedView?.let { view ->
          Div({ classes("actions") }) {
            ActionButton(stringResource(Res.string.saved_view), primary = false) {
              viewModel.sendCameraCommand(CameraCommand.ShowTop(view.x, view.z, view.height))
            }
          }
        }
      }
      Div({ classes("menu-divider"); ui("menu-divider") })

      val available = loaded?.let { remember(it.scene) { AvailableControls(it.scene) } }
      if (loaded != null && available != null) {
        MenuSection("section-display", stringResource(Res.string.section_display), sections.display, rowHeight, { sections.display = !sections.display }) {
          DisplayChecks(loaded.scene, available, ui.settings, ui.autoLabels, rowHeight, viewModel::changeSettings, viewModel::setFlattenDrawing)
        }
        ElementsSection(loaded.scene, ui.settings, ui.selection?.layer, ui.selection?.objectId to sections.layerRequest, sections, rowHeight, viewModel::changeSettings)
      }

      MenuSection("section-settings", stringResource(Res.string.section_settings), sections.settings, rowHeight, { sections.settings = !sections.settings }) {
        Div({ style { property("display", "flex"); property("flex-direction", "column"); property("gap", "8px") } }) {
          if (loaded != null && available != null) {
            // плоская сцена (план, лист) — высота ноль, чипы ничего не меняют
            if (available.verticalScale && loaded.sheet == MODEL_SHEET) {
              SmallLabel(stringResource(Res.string.vertical_scale))
              Div({ classes("chips") }) {
                VERTICAL_SCALES.forEach { scale ->
                  ChoiceChip("${scale.toInt()}×", scale == ui.settings.verticalScale, weighted = true) {
                    viewModel.changeSettings(ui.settings.copy(verticalScale = scale))
                  }
                }
              }
            }
            // какая ось файла — север: геодезическая выгрузка «север восток» (X=север, Y=восток) без перестановки
            // повёрнута на 90° и зеркальна
            SmallLabel(stringResource(Res.string.axes), top = 4)
            Div({ classes("chips", "column") }) {
              ChoiceChip(stringResource(Res.string.axes_east_north), !loaded.swapXY) { if (loaded.swapXY) viewModel.setSwapXY(false) }
              ChoiceChip(stringResource(Res.string.axes_north_east), loaded.swapXY) { if (!loaded.swapXY) viewModel.setSwapXY(true) }
            }
            SheetSwitcher(loaded, viewModel::selectSheet)
          }
          SmallLabel(stringResource(Res.string.language), top = 4)
          Div({ classes("chips") }, ref = { languageRow[0] = it as HTMLElement; ({ languageRow[0] = null }) }) {
            // названия языков — на самих языках
            AppLanguage.entries.forEach {
              ChoiceChip(it.nativeName, it == language, weighted = true) {
                if (it != language) {
                  val row = languageRow[0]
                  val s = scroller[0]
                  if (row != null && s != null) {
                    anchor.offset = row.getBoundingClientRect().top - s.getBoundingClientRect().top
                    anchor.language = it
                  }
                  onLanguage(it)
                }
              }
            }
          }
        }
      }

      FpsText()
    }
  }
  // разметка изменилась (разделы, тексты): края прокрутки — заново
  SideEffect { updateFades() }
  LaunchedEffect(Unit) {
    withFrameNanos { }
    updateFades()
  }
  // первый кадр на новом языке: строка языков — на прежнем месте видимой области
  LaunchedEffect(language) {
    if (anchor.language != language) return@LaunchedEffect
    val offset = anchor.offset
    anchor.language = null
    withFrameNanos { }
    val row = languageRow[0] ?: return@LaunchedEffect
    val s = scroller[0] ?: return@LaunchedEffect
    val now = row.getBoundingClientRect().top - s.getBoundingClientRect().top
    s.scrollTop += now - offset
  }
}

private class LanguageAnchor {
  var offset = 0.0
  var language: AppLanguage? = null
}

@JsFun("(element, callback) => { const o = new ResizeObserver(() => callback()); o.observe(element); for (const c of element.children) o.observe(c); return o; }")
private external fun observeSize(element: org.w3c.dom.Element, callback: () -> Unit): JsAny

@JsFun("(observer) => observer.disconnect()")
private external fun disconnectObserver(observer: JsAny)

/** Раздел меню: заголовок со стрелкой сворачивает и раскрывает [content]. */
@Composable
private fun MenuSection(tag: String, title: String, open: Boolean, rowHeight: Int, onToggle: () -> Unit, content: @Composable () -> Unit) {
  // область нажатия — как у строк раздела: скруглённая, с теми же полями
  Div({
    classes("menu-section-head", "ripple")
    ui(tag)
    attr("role", "button")
    attr("aria-expanded", open.toString())
    style { property("height", (rowHeight + 4).px) }
    onClick { onToggle() }
  }) {
    Span({ classes("label", "body-medium") }) { Text(title) }
    IconView(Icon.ChevronDown, 18, "var(--muted)", if (open) 0f else -90f)
  }
  if (open) Div { content() }
}

/** Сводка сцены и время чтения: выделяются и копируются. */
@Composable
private fun SceneSummary(loaded: LoadState.Loaded, showMillis: Long?) {
  val scene = loaded.scene
  Div({ classes("selectable"); style { property("display", "flex"); property("flex-direction", "column"); property("gap", "10px") } }) {
    Div({ style { property("color", "var(--muted)"); property("font-size", "13px"); property("line-height", "20px"); property("white-space", "pre-line") } }) {
      Text(
        stringResource(
          Res.string.scene_stats,
          scene.surfaces.size, scene.surfaces.sumOf { it.vertexCount }, scene.surfaces.sumOf { it.triangleCount },
          scene.lines.sumOf { it.lineCount }, scene.fills.sumOf { it.triangleCount }, scene.labels.size, scene.points.size,
        ),
      )
    }
    val timing = stringResource(Res.string.timing, loaded.parseMillis, loaded.buildMillis)
    val showTiming = if (showMillis != null) stringResource(Res.string.timing_show, showMillis) else ""
    Div({ style { property("color", "var(--muted)"); property("font-size", "12px"); property("line-height", "16px"); property("white-space", "pre-line") } }) {
      Text(timing + showTiming)
    }
    val warnings = buildList {
      if (scene.truncated) add(stringResource(Res.string.scene_truncated))
      if (scene.hiddenBodyFaces > 0) add(stringResource(Res.string.hidden_body_faces, scene.hiddenBodyFaces))
      if (scene.unreadBodies > 0) add(stringResource(Res.string.unread_bodies, scene.unreadBodies))
      if (scene.surfaces.isEmpty() && scene.lines.isEmpty() && scene.fills.isEmpty() && scene.labels.isEmpty() && scene.points.isEmpty()) {
        add(stringResource(Res.string.scene_empty))
      }
    }
    warnings.forEach {
      Div({ style { property("color", "var(--warning)"); property("font-size", "13px"); property("line-height", "18px") } }) { Text(it) }
    }
  }
}

/** Галочки показа — только для того, что есть в сцене; зависимые — с отступом и только пока «родитель» включён. */
@Composable
private fun DisplayChecks(
  scene: RenderScene, available: AvailableControls, settings: ViewSettings, autoLabels: AutoLabels?, rowHeight: Int,
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
    if (settings.showLabels) check(stringResource(Res.string.thin_labels), settings.thinLabels, indent = true) { onChange(settings.copy(thinLabels = it)) }
    // ступень выбрана автоматически по нагрузке файла — пока её не переключили вручную
    if (autoLabels != null) {
      val reason = when (autoLabels.tier) {
        LabelTier.OFF -> stringResource(Res.string.labels_auto_off_menu, groupDigits(autoLabels.labels.toLong()))
        else -> stringResource(Res.string.labels_auto_thin_menu)
      }
      Div({
        style {
          property("color", "var(--muted)"); property("font-size", "12px"); property("line-height", "16px")
          property("padding", "0 8px 6px 34px")
        }
      }) { Text(reason) }
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
  rowHeight: Int,
  onChange: (ViewSettings) -> Unit,
) {
  // пикетаж — отдельные галочки в «Показывать», в списке его нет, и «Все» / «Ни одного» его не трогают
  val layers = scene.layerInfos.filter { it.name != StationLabels.LAYER && it.name != StationLabels.MID_LAYER }
  if (layers.size < 2) return
  val stationingHidden = settings.hiddenLayers.filter { it == StationLabels.LAYER || it == StationLabels.MID_LAYER }.toSet()
  val surfaceColors = remember(scene) { surfaceColors(scene) }
  val groups = remember(scene) { layers.groupBy { it.group } }
  val title = if (groups.keys.any { it.isNotEmpty() }) Res.string.elements_title else Res.string.layers_title
  MenuSection("section-elements", stringResource(title, layers.size), sections.elements, rowHeight, { sections.elements = !sections.elements }) {
    Div({ style { property("display", "flex"); property("gap", "8px"); property("padding", "6px 0 6px 8px") } }) {
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
        Div({
          classes("group-row", "ripple")
          attr("role", "button")
          onClick { expanded[group] = !open }
        }) {
          IconView(Icon.ChevronRight, 14, "var(--muted)", if (open) 90f else 0f)
          Span { Text("${localized(group)} (${infos.size})") }
        }
      }
      if (open) infos.forEach { info ->
        LayerRow(info.name, layerSwatch(surfaceColors, info.name, info.color), settings, selectedLayer, focusKey, rowHeight, onChange)
      }
    } }
  }
}

@Composable
private fun LayerRow(
  layer: String, swatch: String, settings: ViewSettings, selectedLayer: String?, focusKey: Any?, rowHeight: Int,
  onChange: (ViewSettings) -> Unit,
) {
  val selected = layer == selectedLayer
  val row = remember { arrayOfNulls<HTMLElement>(1) }
  // выбор объекта (или нажатие на «Слой: …» в его карточке) прокручивает меню к слою
  LaunchedEffect(selected, focusKey) {
    if (selected) {
      withFrameNanos { }
      row[0]?.let(::scrollIntoViewNearest)
    }
  }
  CheckRow(localized(layer), layer !in settings.hiddenLayers, rowHeight, swatch = swatch, highlighted = selected, ref = row) { show ->
    val hidden = if (show) settings.hiddenLayers - layer else settings.hiddenLayers + layer
    onChange(settings.copy(hiddenLayers = hidden))
  }
}

@JsFun("(element) => element.scrollIntoView({ block: 'nearest', behavior: 'smooth' })")
private external fun scrollIntoViewNearest(element: HTMLElement)

/** «Модель | Лист1 | Лист2…»: только если в файле есть листы с содержимым. */
@Composable
private fun SheetSwitcher(loaded: LoadState.Loaded, onSelect: (Int) -> Unit) {
  val sheets = loaded.geometry.sheets
  if (sheets.isEmpty()) return
  SmallLabel(stringResource(Res.string.sheets), top = 4)
  Div({ classes("chips", "scroll") }) {
    ChoiceChip(stringResource(Res.string.sheet_model), loaded.sheet == MODEL_SHEET) {
      if (loaded.sheet != MODEL_SHEET) onSelect(MODEL_SHEET)
    }
    sheets.forEachIndexed { i, sheet ->
      ChoiceChip(localized(sheet.name), loaded.sheet == i) { if (loaded.sheet != i) onSelect(i) }
    }
  }
}

/** Строка-флажок ~40 dp: квадрат, цвет слоя ([swatch]) и подпись; [highlighted] — слой выбранного объекта. */
@Composable
fun CheckRow(
  label: String, checked: Boolean, height: Int, indent: Boolean = false, swatch: String? = null, highlighted: Boolean = false,
  ref: Array<HTMLElement?>? = null, onChange: (Boolean) -> Unit,
) {
  Div({
    classes("check-row", "ripple")
    classIf(indent, "indent")
    classIf(highlighted, "highlighted")
    attr("role", "checkbox")
    attr("aria-checked", checked.toString())
    style { property("min-height", height.px) }
    onClick { onChange(!checked) }
  }, ref = if (ref == null) null else { element -> ref[0] = element as HTMLElement; ({ ref[0] = null }) }) {
    Div({ classes("checkbox"); classIf(checked, "on") }) {
      if (checked) IconView(Icon.Check, 14, "var(--white)")
    }
    swatch?.let { Div({ classes("swatch"); style { property("background", it) } }) }
    Span({ classes("label", "body-medium") }) { Text(label) }
  }
}

/** Выбор одного из нескольких: выбранный — синяя рамка и подложка; [weighted] — делит ширину ряда поровну. */
@Composable
private fun ChoiceChip(label: String, selected: Boolean, weighted: Boolean = false, onClick: () -> Unit) {
  Div({
    classes("chip", "body-medium", "ripple")
    classIf(selected, "on")
    classIf(weighted, "weighted")
    attr("role", "radio")
    attr("aria-checked", selected.toString())
    onClick { onClick() }
  }) { Span({ classes("ellipsis") }) { Text(label) } }
}

/** Кнопка меню 44 dp: основная — синяя, вторая — белая с рамкой. */
@Composable
private fun ActionButton(label: String, primary: Boolean, tag: String? = null, onClick: () -> Unit) {
  Div({
    classes("action", "body-medium", "ripple", if (primary) "primary" else "secondary")
    if (tag != null) ui(tag)
    attr("role", "button")
    onClick { onClick() }
  }) { Span({ classes("ellipsis") }) { Text(label) } }
}

@Composable
private fun SmallButton(label: String, onClick: () -> Unit) {
  Div({ classes("small-button", "ripple"); attr("role", "button"); onClick { onClick() } }) { Text(label) }
}

@Composable
private fun SmallLabel(text: String, top: Int = 0) {
  Div({ classes("small-label"); if (top > 0) style { property("padding-top", top.px) } }) { Text(text) }
}

@Composable
private fun FpsText() {
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
  Div({
    classes("selectable", "mono")
    style { property("padding-top", "8px"); property("color", "var(--muted)"); property("font-size", "12px") }
  }) { Text("v$APP_VERSION · FPS $fps") }
}

/** Атрибуты панели меню, чтобы не пересоздавать лямбду на каждой перекомпоновке экрана. */
internal fun AttrsScope.menuPanel(top: Int, width: Int, shown: Boolean) {
  classes("menu-panel", "appear", "soft-12")
  ui("menu-panel")
  classIf(shown, "in")
  style {
    property("top", top.px)
    property("width", width.px)
    property("max-height", "calc(100% - ${top + 16}px)")
  }
}
