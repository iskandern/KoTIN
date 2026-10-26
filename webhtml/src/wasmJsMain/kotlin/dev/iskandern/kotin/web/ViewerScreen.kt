package dev.iskandern.kotin.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import dev.iskandern.kotin.AppLanguage
import dev.iskandern.kotin.CameraCommand
import dev.iskandern.kotin.CameraUiState
import dev.iskandern.kotin.DesignTokens
import dev.iskandern.kotin.DeviceSpeed
import dev.iskandern.kotin.DrawingSource
import dev.iskandern.kotin.FRAME_CHECK_FRAMES
import dev.iskandern.kotin.LabelTier
import dev.iskandern.kotin.LoadState
import dev.iskandern.kotin.PREF_LANGUAGE
import dev.iskandern.kotin.SceneCameraController
import dev.iskandern.kotin.UiStrings
import dev.iskandern.kotin.ViewSettings
import dev.iskandern.kotin.ViewerViewModel
import dev.iskandern.kotin.applyLocale
import dev.iskandern.kotin.candidateAt
import dev.iskandern.kotin.candidateHits
import dev.iskandern.kotin.drawnFrameBegins
import dev.iskandern.kotin.drawnFrameMillis
import dev.iskandern.kotin.groupDigits
import dev.iskandern.kotin.lastDrawnFrame
import dev.iskandern.kotin.medianFrameMillis
import dev.iskandern.kotin.model.SceneObject
import dev.iskandern.kotin.pointerTargets
import dev.iskandern.kotin.rememberFilePicker
import dev.iskandern.kotin.rememberPrefsStore
import dev.iskandern.kotin.rememberSceneCamera
import dev.iskandern.kotin.resources.Res
import dev.iskandern.kotin.resources.labels_auto_off
import dev.iskandern.kotin.resources.labels_auto_thin
import dev.iskandern.kotin.resources.labels_show
import dev.iskandern.kotin.resources.labels_show_all
import dev.iskandern.kotin.resources.more_object
import dev.iskandern.kotin.resources.more_objects
import dev.iskandern.kotin.resources.selection_hint
import dev.iskandern.kotin.resources.selection_hint_touch
import dev.iskandern.kotin.resources.to_more_objects
import dev.iskandern.kotin.scene.DrawingOutline
import dev.iskandern.kotin.scene.RenderScene
import dev.iskandern.kotin.surfaceColors
import dev.iskandern.kotin.web.dom.Div
import dev.iskandern.kotin.web.dom.px
import dev.iskandern.kotin.withLabelTier
import io.github.erkko68.filament.compose.FilamentViewState
import io.github.erkko68.filament.compose.rememberFilamentEngine
import io.github.erkko68.filament.compose.rememberFilamentViewState
import io.github.erkko68.filament.compose.scene.rememberCameraState
import kotlinx.browser.window
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.refreshResourceLanguage
import org.jetbrains.compose.resources.stringResource

/** Пауза после последнего движения камеры, через которую её поза уходит во ViewModel. */
private const val POSE_SETTLE_MS = 300L

/**
 * Размеры раскладки по ширине экрана (px CSS = dp): [panelWidth] — меню и строка «≡ + карточка файла» над ним,
 * [barHeight] — кнопка ≡ и карточка файла, [cameraButton] — кнопки камеры, [rowHeight] — строки-флажки меню.
 */
private class ScreenSizes(val panelWidth: Int, val barHeight: Int, val cameraButton: Int, val rowHeight: Int)

private fun sizesOf(tokens: DesignTokens.Sizes) = ScreenSizes(tokens.panelWidth, tokens.barHeight, tokens.cameraButton, tokens.rowHeight)

private val DESKTOP = sizesOf(DesignTokens.DESKTOP)

/** Планшет (600–1199 dp): меню уже, кнопки крупнее — под палец. */
private val TABLET = sizesOf(DesignTokens.TABLET)

/** Телефон (уже 600 dp): меню — боковая панель, кнопки 52 dp колонкой у правого края, строки меню 48 dp. */
private val PHONE = sizesOf(DesignTokens.PHONE)

private fun isPhone(width: Int) = width < DesignTokens.PHONE_MAX_WIDTH

private fun screenSizes(width: Int): ScreenSizes = when {
  isPhone(width) -> PHONE
  width < DesignTokens.TABLET_MAX_WIDTH -> TABLET
  else -> DESKTOP
}

/** Индикатор осей: размер. */
private const val AXIS_SIZE = DesignTokens.AXIS_SIZE

/** Отступ плавающих элементов от краёв экрана и между ними. */
private const val EDGE = DesignTokens.EDGE

/** Ширина карточки выбранного объекта. */
private const val SELECTION_WIDTH = DesignTokens.SELECTION_WIDTH

/** Высота свёрнутой карточки выбранного — доля высоты окна. */
private const val SELECTION_LIMIT = DesignTokens.SELECTION_LIMIT

/** Подсказка о выборе скрыта крестиком (ключ PrefsStore). */
private const val PREF_HINT_HIDDEN = "selection_hint_hidden"

/** Плашки на далёкие объекты выключены в меню (ключ PrefsStore): "0"; нет значения — включены. */
private const val PREF_OUTLIER_POINTERS = "outlier_pointers"

/** Сколько висит плашка автоматической ступени подписей от первого кадра сцены, мс. */
private const val LABELS_NOTICE_MS = 8_000L

/** Как часто на слабом устройстве пересчитывается указатель на ушедший чертёж, мс. */
private const val POINTER_SLOW_MS = 100L

// плашки указателей у края ближе этого сливаются в одну, px CSS
private const val POINTER_MERGE = 48

/**
 * Экран просмотра — тот же, что `ViewerScreen` app, на элементах страницы. [filamentReady] — движок Filament загружен:
 * до него сцены нет, а интерфейс (пустой экран, меню, открытие файла) уже работает.
 */
@Composable
fun ViewerScreen(initialSource: DrawingSource?, filamentReady: Boolean) {
  val prefs = rememberPrefsStore()
  // сохранённый выбор плашек отдаём модели при создании: до первого кадра, без вспышки и без записи поверх сохранённого
  val viewModel = remember { ViewerViewModel(ViewSettings(showOutlierPointers = prefs.load(PREF_OUTLIER_POINTERS) != "0")) }
  val ui by viewModel.state.collectAsState()
  LaunchedEffect(initialSource) {
    if (initialSource != null) viewModel.openInitial(initialSource)
  }

  // язык интерфейса: по умолчанию по языку системы, выбор в меню хранится между запусками
  var language by remember {
    val chosen = AppLanguage.of(prefs.load(PREF_LANGUAGE))
    applyLocale(chosen.tag)
    refreshResourceLanguage()
    mutableStateOf(chosen)
  }
  // журнал — на выбранном языке
  LaunchedEffect(language) { UiStrings.load() }
  val outlierPointersOn = ui.settings.showOutlierPointers
  LaunchedEffect(outlierPointersOn) { prefs.save(PREF_OUTLIER_POINTERS, if (outlierPointersOn) null else "0") }
  // подсказку о выборе, скрытую крестиком, больше не показываем
  var hintHidden by remember { mutableStateOf(prefs.load(PREF_HINT_HIDDEN) != null) }
  val sections = remember { MenuSections() }
  val languageScope = rememberCoroutineScope()

  // сцена на экране: пересобранная или новый файл сменяют её, когда их подписи готовы — до тех пор держится прежняя
  val current = ui.shownScene
  var held by remember { mutableStateOf(current) }
  LaunchedEffect(current) { if (held == null || current == null) held = current }
  val shown = if (current == null) null else held ?: current

  val slowDevice by produceState(false) { value = withContext(Dispatchers.Default) { DeviceSpeed.isSlow() } }

  val windowSize = rememberWindowSize()
  val width = windowSize.width
  val height = windowSize.height
  val sizes = screenSizes(width)
  val phone = isPhone(width)
  val density = window.devicePixelRatio.toFloat()
  val cameraState = rememberCameraState()
  val filamentState = rememberFilamentViewState()

  Div({ classes("screen") }) {
    // сцена — вне языка: смена языка меняет только тексты интерфейса
    val camera = if (shown == null) null else key(shown.scene) {
      // плоский чертёж вписывается в кадр по отношению сторон экрана при открытии
      val aspect = if (height > 0) width.toFloat() / height else 1f
      val camera = rememberSceneCamera(cameraState, shown.scene, ui.settings.verticalScale, aspect, initialPose = remember { viewModel.cameraPoseOf(shown) })
      CameraSync(camera.controller, shown, ui.camera, viewModel)
      camera
    }
    // выбор в сцене — кадром позже карточки
    var sceneSelection by remember { mutableStateOf(ui.selection) }
    LaunchedEffect(ui.selection) {
      if (ui.selection != null && sceneSelection != null) withFrameNanos { }
      sceneSelection = ui.selection
    }
    // размер вида и плотность — для ступени подписей следующего файла
    LaunchedEffect(width, height, density) {
      viewModel.setViewport((width * density).toInt(), (height * density).toInt(), density)
    }
    var textShownFor by remember { mutableStateOf<RenderScene?>(null) }
    val measureScene = shown?.scene?.takeIf { ui.frameCheck && it === textShownFor && shown === current }
    LaunchedEffect(measureScene) {
      if (measureScene == null) return@LaunchedEffect
      val renderer = filamentState.renderer
      val startId = renderer?.lastDrawnFrame()
      val frames = LongArray(FRAME_CHECK_FRAMES + 1)
      for (i in frames.indices) frames[i] = withFrameNanos { it }
      val drawn = startId?.let { renderer.drawnFrameBegins(it) }
      val windowMillis = (frames.last() - frames.first()) / 1_000_000.0
      val composeMillis = medianFrameMillis(frames)
      viewModel.onFramesMeasured(drawnFrameMillis(drawn, windowMillis, composeMillis))
    }
    if (filamentReady && shown != null && camera != null) {
      val engine = rememberFilamentEngine()
      val selected = sceneSelection?.takeIf { shown === current }
      val hits = remember(selected) { selected?.let(::candidateHits) }
      SceneView(
        engine, shown.scene, camera, ui.settings,
        selection = sceneSelection?.geometry?.takeIf { shown === current },
        candidateHits = hits,
        candidateIndex = selected?.index ?: 0,
        onTap = { position, w, h ->
          if (selected != null && hits != null) {
            candidateAt(camera.controller, hits, selected.index, position.x, position.y, w, h, density)?.let(viewModel::selectCandidate)
          }
        },
        onShown = { viewModel.onSceneShown(shown.scene) },
        onTextShown = { textShownFor = shown.scene },
        onLongPress = { position, w, h ->
          viewModel.selectAt(camera.controller.pickRay(position.x, position.y, w, h), density, position.x, position.y, camera.controller.pose)
        },
        next = current?.scene ?: shown.scene,
        onNextReady = { scene -> if (current?.scene === scene) held = current },
        viewState = filamentState,
      )
    }

    // файл выбран — меню закрывается: ход чтения и ошибка видны в карточке файла
    val pickFile = rememberFilePicker { source ->
      viewModel.setMenuOpen(false)
      viewModel.open(source)
    }
    val openMenuOnLayer = {
      viewModel.setMenuOpen(true)
      sections.showLayer()
    }
    if (current == null) EmptyState(pickFile)
    if (shown != null && camera != null) key(shown.scene) {
      AxisIndicator(camera.controller, shown.swapXY) { viewModel.sendCameraCommand(CameraCommand.Home) }
      val zoom = { factor: Float -> viewModel.sendCameraCommand(CameraCommand.Zoom(factor)) }
      // чертёж целиком ушёл из кадра: указатель у края сдвигает камеру к ближайшей его части
      val outline = shown.scene.outline
      if (outline != null) {
        val viewWidth = width * density
        val viewHeight = height * density
        val verticalScale = ui.settings.verticalScale
        val outliers = shown.scene.home?.outliers.orEmpty()
        val showOutliers = ui.settings.showOutlierPointers
        val mergeAngle = POINTER_MERGE * density / (minOf(viewWidth, viewHeight) / 2f)
        val pointers by produceState(emptyList(), camera.controller, outline, outliers, showOutliers, viewWidth, viewHeight, verticalScale, slowDevice, mergeAngle) {
          snapshotFlow { camera.controller.pose }.collect {
            value = pointerTargets(
              camera.controller.offscreenDrawing(outline, verticalScale, viewWidth, viewHeight) != null, showOutliers, outliers, mergeAngle,
            ) { camera.controller.offscreenDrawing(it.outline, verticalScale, viewWidth, viewHeight) }
            if (slowDevice) delay(POINTER_SLOW_MS)
          }
        }
        if (pointers.isNotEmpty()) {
          // поля под ≡ и осями сверху, под кнопками камеры снизу (на телефоне — справа)
          val reserved = if (phone) {
            Reserved(EDGE, EDGE + AXIS_SIZE + EDGE, EDGE + sizes.cameraButton + EDGE, EDGE * 2)
          } else {
            Reserved(EDGE, EDGE + AXIS_SIZE + 8, EDGE, EDGE + sizes.cameraButton + 8 + EDGE)
          }
          val toward = { target: DrawingOutline ->
            val pose = camera.controller.pose
            target.nearest(pose.pivotX, pose.pivotZ)?.let { nearest ->
              viewModel.sendCameraCommand(CameraCommand.FlyTo(camera.controller.towardDrawing(nearest, target, verticalScale)))
            }
            Unit
          }
          for (pointer in pointers) key(pointer.group) {
            val target = if (pointer.group < 0) outline else outliers[pointer.group].outline
            val direction = { camera.controller.drawingDirection(target, verticalScale) }
            val pose = { camera.controller.pose }
            if (pointer.group < 0) {
              DrawingPointer(direction, pose, reserved, { toward(target) })
            } else {
              DrawingPointer(
                direction, pose, reserved, { toward(target) },
                text = stringResource(if (pointer.count == 1) Res.string.more_object else Res.string.more_objects),
                clickLabel = stringResource(Res.string.to_more_objects),
              )
            }
          }
        }
      }
      if (phone) {
        // посередине правого края; при открытой шторке выбора — выше, над ней
        CameraRail(ui.camera.mode, viewModel::setCameraMode, zoom, sizes.cameraButton, if (ui.selection != null) -(height * 0.1f).toInt() else 0)
      } else {
        CameraButtons(ui.camera.mode, viewModel::setCameraMode, zoom, sizes.cameraButton)
      }
    }

    if (current != null) {
      // слева снизу — выбранный объект или подсказка; при открытом меню — справа от него
      val start = if (ui.menuOpen) EDGE + sizes.panelWidth + 12 else EDGE
      val selection = rememberLast(ui.selection)
      val colors = remember(current.scene) { surfaceColors(current.scene) }
      val swatch = { info: SceneObject -> layerSwatch(colors, info.layer, info.color) }
      // карточка открывается свёрнутой; новый выбор сворачивает её снова, листание соседей — нет
      var expanded by remember(selection?.candidates) { mutableStateOf(false) }
      val collapsedHeight = (height * SELECTION_LIMIT).toInt()
      if (phone) {
        // телефон: шторка снизу во всю ширину; развёрнутая — до строки ≡
        Appear(ui.selection != null, MOTION_MS) { visible ->
          if (selection != null) BottomSheet(
            visible, viewModel::clearSelection, expanded, { expanded = it },
            collapsedHeight, height - EDGE - sizes.barHeight - EDGE,
          ) { drag, dragging ->
            SelectionCard(
              selection, swatch, current.swapXY, viewModel::clearSelection, openMenuOnLayer, onPick = viewModel::selectCandidate,
              header = drag, animateSize = !dragging, buttonSize = sizes.rowHeight,
              attrs = { classes("plain") },
            )
          }
        }
      } else {
        val fullHeight = height - EDGE - sizes.barHeight - EDGE - EDGE
        Appear(ui.selection != null) { visible ->
          if (selection != null) SelectionCard(
            selection, swatch, current.swapXY, viewModel::clearSelection, openMenuOnLayer,
            onPick = viewModel::selectCandidate, expanded = expanded, onExpand = { expanded = it }, buttonSize = sizes.rowHeight,
            maxHeight = if (expanded) maxOf(fullHeight, collapsedHeight) else minOf(fullHeight, collapsedHeight),
            attrs = {
              classes("layer", "appear", "soft-8")
              classIf(visible, "in")
              style {
                property("left", start.px)
                property("bottom", EDGE.px)
                property("width", SELECTION_WIDTH.px)
              }
            },
          )
        }
      }
      // плашка автоматической ступени подписей: ~LABELS_NOTICE_MS от первого кадра сцены, раньше — по кнопке или при
      // открытии меню. Пока она видна, подсказки о выборе нет
      val auto = ui.autoLabels
      var noticeDone by remember(auto?.serial) { mutableStateOf(false) }
      val sceneDrawn = ui.showMillis != null
      LaunchedEffect(auto?.serial, sceneDrawn) {
        if (auto != null && sceneDrawn) {
          delay(LABELS_NOTICE_MS)
          noticeDone = true
        }
      }
      LaunchedEffect(ui.menuOpen) { if (ui.menuOpen) noticeDone = true }
      val notice = auto?.takeIf { sceneDrawn && !noticeDone && !ui.menuOpen && ui.selection == null }
      val lastNotice = rememberLast(notice)
      val hintPlace: dev.iskandern.kotin.web.dom.AttrsScope.(Boolean) -> Unit = { visible ->
        classes("layer", "fade")
        classIf(visible, "in")
        style {
          if (phone) {
            property("left", EDGE.px)
            property("right", EDGE.px)
            property("bottom", "24px")
          } else {
            property("left", start.px)
            property("bottom", EDGE.px)
            property("max-width", "380px")
            property("transition", "opacity 200ms var(--fos), left 200ms var(--fos)")
          }
        }
      }
      Appear(notice != null, MOTION_MS) { visible ->
        if (lastNotice != null) {
          val off = lastNotice.tier == LabelTier.OFF
          ActionHint(
            if (off) stringResource(Res.string.labels_auto_off, groupDigits(lastNotice.labels.toLong())) else stringResource(Res.string.labels_auto_thin),
            stringResource(if (off) Res.string.labels_show else Res.string.labels_show_all),
            onAction = {
              noticeDone = true
              viewModel.changeSettings(ui.settings.withLabelTier(LabelTier.ALL))
            },
          ) { hintPlace(visible) }
        }
      }
      Appear(ui.selection == null && !hintHidden && notice == null, MOTION_MS) { visible ->
        SelectionHint(
          stringResource(if (phone) Res.string.selection_hint_touch else Res.string.selection_hint),
          onHide = {
            hintHidden = true
            prefs.save(PREF_HINT_HIDDEN, "1")
          },
        ) { hintPlace(visible) }
      }
    }

    // слева сверху: ≡ и карточка файла; на телефоне имя файла — в боковом меню, а карточка — только пока файл
    // читается или не открылся, до индикатора осей
    Div({
      classes("top-row")
      style { if (phone) property("right", (EDGE + AXIS_SIZE + 8).px) else property("width", sizes.panelWidth.px) }
    }) {
      MenuButton(ui.menuOpen && !phone, sizes.barHeight, viewModel::toggleMenu)
      if (!phone || ui.loadState is LoadState.Loading || ui.loadState is LoadState.Failed) {
        FileCard(ui.loadState, current, sizes.barHeight, pickFile, viewModel::dismissError)
      }
    }

    val onLanguage = { it: AppLanguage ->
      languageScope.launch {
        applyLocale(it.tag)
        refreshResourceLanguage()
        language = it
        prefs.save(PREF_LANGUAGE, it.tag)
      }
      Unit
    }
    if (phone) {
      // телефон: меню выезжает слева поверх затемнённой сцены
      MenuDrawer(ui.menuOpen, sizes.panelWidth, { viewModel.setMenuOpen(false) }) {
        ViewerMenu(ui, viewModel, sections, language, onLanguage, pickFile, sizes.rowHeight, onClose = { viewModel.setMenuOpen(false) })
      }
    } else {
      // выпадающая панель под ≡; не выше экрана, длинное прокручивается; сцена вокруг живая
      Appear(ui.menuOpen) { visible ->
        Div({ menuPanel(EDGE + sizes.barHeight + EDGE, sizes.panelWidth, visible) }) {
          ViewerMenu(ui, viewModel, sections, language, onLanguage, pickFile, sizes.rowHeight)
        }
      }
    }
  }
}

/**
 * Связь камеры рендера с ViewModel: схема управления и команды идут из ViewModel в контроллер, остановившаяся поза — обратно.
 */
@OptIn(FlowPreview::class)
@Composable
private fun CameraSync(controller: SceneCameraController, loaded: LoadState.Loaded, camera: CameraUiState, viewModel: ViewerViewModel) {
  LaunchedEffect(controller, camera.mode) { controller.switchMode(camera.mode) }
  // движение камеры по команде живёт в контроллере и идёт дольше этого эффекта
  val animations = rememberCoroutineScope()
  LaunchedEffect(controller, camera.command) {
    val command = camera.command ?: return@LaunchedEffect
    if (command is CameraCommand.Zoom) controller.animateZoom(command.factor, animations) else controller.execute(command, animations)
    viewModel.onCameraCommandConsumed(command)
  }
  LaunchedEffect(controller) {
    snapshotFlow { controller.pose }.debounce(POSE_SETTLE_MS).collect { viewModel.onCameraSettled(loaded, it) }
  }
  DisposableEffect(controller) { onDispose { viewModel.onCameraSettled(loaded, controller.pose) } }
}
