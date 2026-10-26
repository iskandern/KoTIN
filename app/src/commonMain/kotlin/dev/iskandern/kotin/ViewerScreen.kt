package dev.iskandern.kotin

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.erkko68.filament.compose.rememberFilamentEngine
import io.github.erkko68.filament.compose.rememberFilamentViewState
import io.github.erkko68.filament.compose.scene.rememberCameraState
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.debounce
import org.jetbrains.compose.resources.stringResource
import dev.iskandern.kotin.model.SceneObject
import dev.iskandern.kotin.resources.Res
import dev.iskandern.kotin.resources.selection_hint
import dev.iskandern.kotin.resources.labels_auto_off
import dev.iskandern.kotin.resources.labels_auto_thin
import dev.iskandern.kotin.resources.labels_show
import dev.iskandern.kotin.resources.labels_show_all
import dev.iskandern.kotin.scene.RenderScene
import dev.iskandern.kotin.scene.DrawingOutline
import dev.iskandern.kotin.resources.more_object
import dev.iskandern.kotin.resources.more_objects
import dev.iskandern.kotin.resources.to_more_objects
import dev.iskandern.kotin.resources.selection_hint_touch

/** Пауза после последнего движения камеры, через которую её поза уходит во ViewModel. */
private const val POSE_SETTLE_MS = 300L

private val PANEL_SHAPE = RoundedCornerShape(16.dp)

/**
 * Размеры раскладки по ширине экрана: [panelWidth] — меню и строка «≡ + карточка файла» над ним, [barHeight] — кнопка ≡
 * и карточка файла, [cameraButton] — кнопки камеры, [rowHeight] — строки-флажки меню.
 */
private class ScreenSizes(val panelWidth: Dp, val barHeight: Dp, val cameraButton: Dp, val rowHeight: Dp)

private fun sizesOf(tokens: DesignTokens.Sizes) =
  ScreenSizes(tokens.panelWidth.dp, tokens.barHeight.dp, tokens.cameraButton.dp, tokens.rowHeight.dp)

private val DESKTOP = sizesOf(DesignTokens.DESKTOP)

/** Планшет (600–1199 dp): меню уже, кнопки крупнее — под палец. */
private val TABLET = sizesOf(DesignTokens.TABLET)

/** Телефон (уже 600 dp): меню — боковая панель, кнопки 52 dp колонкой у правого края, строки меню 48 dp. */
private val PHONE = sizesOf(DesignTokens.PHONE)

/** Телефонная раскладка (T16): ≡ и оси по углам, кнопки камеры колонкой, шторка выбора, боковое меню. */
private fun isPhone(width: Dp) = width < DesignTokens.PHONE_MAX_WIDTH.dp

private fun screenSizes(width: Dp): ScreenSizes = when {
  isPhone(width) -> PHONE
  width < DesignTokens.TABLET_MAX_WIDTH.dp -> TABLET
  else -> DESKTOP
}

/** Индикатор осей: размер и отступ — чтобы строка состояния файла на телефоне его не задевала. */
private val AXIS_SIZE = DesignTokens.AXIS_SIZE.dp

/** Отступ плавающих элементов от краёв экрана и между ними. */
private val EDGE = DesignTokens.EDGE.dp

/** Ширина карточки выбранного объекта. */
private val SELECTION_WIDTH = DesignTokens.SELECTION_WIDTH.dp

/** Высота свёрнутой карточки выбранного — доля высоты окна. */
private const val SELECTION_LIMIT = DesignTokens.SELECTION_LIMIT

/** Подсказка о выборе скрыта крестиком (ключ [PrefsStore]). */
private const val PREF_HINT_HIDDEN = "selection_hint_hidden"

/** Плашки на далёкие объекты выключены в меню (ключ [PrefsStore]): "0"; нет значения — включены. */
private const val PREF_OUTLIER_POINTERS = "outlier_pointers"

@Composable
fun ViewerScreen(initialSource: DrawingSource? = null, injected: ViewerViewModel? = null) {
  val prefs = rememberPrefsStore()
  // сохранённый выбор плашек отдаём модели при создании: до первого кадра, без вспышки и без записи поверх сохранённого
  val viewModel = injected ?: viewModel { ViewerViewModel(ViewSettings(showOutlierPointers = prefs.load(PREF_OUTLIER_POINTERS) != "0")) }
  val ui by viewModel.state.collectAsState()
  LaunchedEffect(initialSource) {
    if (initialSource != null) viewModel.openInitial(initialSource)
  }

  // вне key(сцены) и языка: движок и камера рендера живут, пока открыт экран, файлы меняют только сцену и вид
  val engine = rememberFilamentEngine()
  val cameraState = rememberCameraState()

  // язык интерфейса: по умолчанию по языку системы, выбор в меню хранится между запусками
  var language by remember { mutableStateOf(AppLanguage.of(prefs.load(PREF_LANGUAGE))) }
  // плашки на далёкие объекты: выбор «выключено» помнится между запусками (по умолчанию включены)
  val outlierPointersOn = ui.settings.showOutlierPointers
  LaunchedEffect(outlierPointersOn) { prefs.save(PREF_OUTLIER_POINTERS, if (outlierPointersOn) null else "0") }
  // подсказку о выборе, скрытую крестиком, больше не показываем
  var hintHidden by remember { mutableStateOf(prefs.load(PREF_HINT_HIDDEN) != null) }
  // смена языка ждёт загрузки его строк — вне ключа языка, чтобы не отменилась
  val languageScope = rememberCoroutineScope()
  // разделы меню переживают смену языка
  val sections = remember { MenuSections() }

  // сцена на экране: пересобранная (оси, «в плоскость», лист) или новый файл сменяют её, когда их подписи готовы —
  // до тех пор держится прежняя картинка, без пустого кадра
  val current = ui.shownScene
  var held by remember { mutableStateOf(current) }
  LaunchedEffect(current) { if (held == null || current == null) held = current }
  val shown = if (current == null) null else held ?: current

  // слабое устройство — реже пересчитываем то, что можно (указатель на ушедший чертёж); замер один раз за запуск,
  // в фоне (в браузере фонового потока нет, и замера там нет — см. DeviceSpeed)
  val slowDevice by produceState(false) { value = withContext(Dispatchers.Default) { DeviceSpeed.isSlow() } }

  KotinTheme {
    BoxWithConstraints(Modifier.fillMaxSize().background(KotinColors.Scene)) {
      val sizes = screenSizes(maxWidth)
      val phone = isPhone(maxWidth)
      val safe = Modifier.windowInsetsPadding(WindowInsets.safeDrawing)

      // сцена — вне языка: смена языка меняет только тексты интерфейса, движок, сцена и камера остаются
      val camera = shown?.let { loaded ->
        key(loaded.scene) {
          // плоский чертёж вписывается в кадр по отношению сторон экрана при открытии
          val aspect = with(LocalWindowInfo.current.containerSize) { if (height > 0) width.toFloat() / height else 1f }
          val camera = rememberSceneCamera(
            cameraState, loaded.scene, ui.settings.verticalScale, aspect, initialPose = remember { viewModel.cameraPoseOf(loaded) },
          )
          CameraSync(camera.controller, loaded, ui.camera, viewModel)
          camera
        }
      }
      // выбор в сцене (подсветка, номера кандидатов) — кадром позже карточки: пересборка подсветки не ложится на
      // первый кадр смены карточки, самый тяжёлый
      var sceneSelection by remember { mutableStateOf(ui.selection) }
      LaunchedEffect(ui.selection) {
        if (ui.selection != null && sceneSelection != null) withFrameNanos { }
        sceneSelection = ui.selection
      }
      // размер вида и плотность — для ступени подписей следующего файла
      val viewDensity = LocalDensity.current.density
      LaunchedEffect(constraints.maxWidth, constraints.maxHeight, viewDensity) {
        viewModel.setViewport(constraints.maxWidth, constraints.maxHeight, viewDensity)
      }
      // подписи показанной сцены нарисованы: с этого кадра мерим, тянет ли устройство их ступень
      var textShownFor by remember { mutableStateOf<RenderScene?>(null) }
      val filamentState = rememberFilamentViewState()
      val measureScene = shown?.scene?.takeIf { ui.frameCheck && it === textShownFor && shown === current }
      LaunchedEffect(measureScene) {
        if (measureScene == null) return@LaunchedEffect
        // рендер Filament идёт в кадрах Compose (withFrameNanos в цикле), но кадр, на который GPU не успевает, Filament
        // пропускает: время кадра — по кадрам, которые он нарисовал (история рендерера); истории нет — по кадрам Compose
        val renderer = filamentState.renderer
        val startId = renderer?.lastDrawnFrame()
        val frames = LongArray(FRAME_CHECK_FRAMES + 1)
        for (i in frames.indices) frames[i] = withFrameNanos { it }
        val drawn = startId?.let { renderer.drawnFrameBegins(it) }
        val windowMillis = (frames.last() - frames.first()) / 1_000_000.0
        val composeMillis = medianFrameMillis(frames)
        viewModel.onFramesMeasured(drawnFrameMillis(drawn, windowMillis, composeMillis))
      }
      if (shown != null && camera != null) {
        val density = LocalDensity.current.density
        // номера кандидатов выбора рисует сцена, нажатие на них — onTap
        val selected = sceneSelection?.takeIf { shown === current }
        val hits = remember(selected) { selected?.let(::candidateHits) }
        SceneView(
          engine, shown.scene, camera, ui.settings,
          selection = sceneSelection?.geometry?.takeIf { shown === current },
          highlightLayer = sceneSelection?.layer,
          candidateHits = hits,
          candidateIndex = selected?.index ?: 0,
          onTap = { position, size ->
            if (selected != null && hits != null) {
              candidateAt(camera.controller, hits, selected.index, position.x, position.y, size.width.toFloat(), size.height.toFloat(), density)
                ?.let(viewModel::selectCandidate)
            }
          },
          onShown = { viewModel.onSceneShown(shown.scene) },
          onTextShown = { textShownFor = shown.scene },
          onLongPress = { position, size ->
            viewModel.selectAt(
              camera.controller.pickRay(position.x, position.y, size.width.toFloat(), size.height.toFloat()), density,
              position.x, position.y, camera.controller.pose,
            )
          },
          next = current?.scene ?: shown.scene,
          onNextReady = { scene -> if (current?.scene === scene) held = current },
          viewState = filamentState,
        )
      }

      ProvideLanguage(language.tag) {
        // журнал — на выбранном языке. Строки грузятся не на UI-диспетчере: иначе stringResource в композиции
        // (runBlocking на главном потоке) ждёт ту же загрузку, которой для завершения нужен главный поток, — зависание
        LaunchedEffect(Unit) { withContext(Dispatchers.Default) { UiStrings.load() } }
        // файл выбран — меню закрывается: ход чтения и ошибка видны в карточке файла, а на телефоне меню её закрывает.
        // Отмена выбора сюда не приходит — меню остаётся
        val pickFile = rememberFilePicker { source ->
          viewModel.setMenuOpen(false)
          viewModel.open(source)
        }
        val openMenuOnLayer = {
          viewModel.setMenuOpen(true)
          sections.showLayer()
        }
        if (current == null) {
          EmptyState(pickFile, Modifier.align(Alignment.Center).padding(24.dp))
        }
        if (shown != null && camera != null) key(shown.scene) {
          AxisIndicator(camera.controller, shown.swapXY, { viewModel.sendCameraCommand(CameraCommand.Home) }, Modifier.align(Alignment.TopEnd).then(safe).padding(EDGE))
          val zoom = { factor: Float -> viewModel.sendCameraCommand(CameraCommand.Zoom(factor)) }
          // чертёж целиком ушёл из кадра: указатель у края сдвигает камеру к ближайшей его части, без поворота и смены зума.
          // Чертёж — основная масса без далёких объектов; пока она в кадре, на группы далёких объектов вне кадра — свои
          // указатели того же вида
          val outline = shown.scene.outline
          if (outline != null) {
            val viewWidth = constraints.maxWidth.toFloat()
            val viewHeight = constraints.maxHeight.toFloat()
            val verticalScale = ui.settings.verticalScale
            val outliers = shown.scene.home?.outliers.orEmpty()
            val showOutliers = ui.settings.showOutlierPointers
            // плашки, чьи центры у края ближе POINTER_MERGE, — одна: угол между направлениями на них
            val mergeAngle = with(LocalDensity.current) { POINTER_MERGE.toPx() } / (minOf(viewWidth, viewHeight) / 2f)
            // ушёл ли чертёж из кадра — каждый кадр движения камеры, а на слабом устройстве не чаще раза в POINTER_SLOW_MS
            // (поза приходит последняя); куда указывать — каждый кадр: это дёшево, и плашка едет за камерой без рывков
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
                PaddingValues(start = EDGE, end = EDGE + sizes.cameraButton + EDGE, top = EDGE + AXIS_SIZE + EDGE, bottom = EDGE * 2)
              } else {
                PaddingValues(start = EDGE, end = EDGE, top = EDGE + AXIS_SIZE + 8.dp, bottom = EDGE + sizes.cameraButton + 8.dp + EDGE)
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
                val modifier = Modifier.fillMaxSize().then(safe)
                val direction = { camera.controller.drawingDirection(target, verticalScale) }
                if (pointer.group < 0) {
                  DrawingPointer(direction, reserved, { toward(target) }, modifier)
                } else {
                  DrawingPointer(
                    direction, reserved, { toward(target) }, modifier,
                    text = stringResource(if (pointer.count == 1) Res.string.more_object else Res.string.more_objects),
                    clickLabel = stringResource(Res.string.to_more_objects),
                  )
                }
              }
            }
          }
          if (phone) {
            // посередине правого края; при открытой шторке выбора — выше, над ней
            val railShift by animateDpAsState(if (ui.selection != null) -maxHeight * 0.1f else 0.dp, tween(MOTION_MS))
            CameraRail(
              ui.camera.mode, viewModel::setCameraMode, zoom, sizes.cameraButton,
              Modifier.align(Alignment.CenterEnd).then(safe).padding(end = EDGE).offset(y = railShift),
            )
          } else {
            CameraButtons(ui.camera.mode, viewModel::setCameraMode, zoom, sizes.cameraButton, Modifier.align(Alignment.BottomEnd).then(safe).padding(EDGE))
          }
        }

        if (current != null) {
          // слева снизу — выбранный объект или подсказка; при открытом меню — справа от него
          val start by animateDpAsState(if (ui.menuOpen) EDGE + sizes.panelWidth + 12.dp else EDGE, tween(MOTION_MS))
          val bottomLeft = Modifier.align(Alignment.BottomStart).then(safe).padding(start = start, bottom = EDGE)
          // карточка уходит плавно: пока она гаснет, в ней прежний объект
          val selection = rememberLast(ui.selection)
          val colors = remember(current.scene) { surfaceColors(current.scene) }
          val swatch = { info: SceneObject -> layerSwatch(colors, info.layer, info.color) }
          // карточка открывается свёрнутой (не выше SELECTION_LIMIT экрана); новый выбор — новый набор кандидатов —
          // сворачивает её снова, листание соседей — нет
          var expanded by remember(selection?.candidates) { mutableStateOf(false) }
          val collapsedHeight = maxHeight * SELECTION_LIMIT
          if (phone) {
            // телефон: шторка снизу во всю ширину; развёрнутая — до строки ≡. Разворачивают, сворачивают и закрывают
            // её перетаскиванием за ручку и шапку — кнопки «развернуть» нет
            val top = with(LocalDensity.current) { WindowInsets.safeDrawing.getTop(this).toDp() }
            AnimatedVisibility(
              ui.selection != null, Modifier.align(Alignment.BottomCenter),
              // только сдвиг: прозрачность внешним слоем рисовала бы тень шторки прямоугольником
              enter = slideInVertically(tween(MOTION_MS)) { it },
              exit = slideOutVertically(tween(MOTION_MS)) { it },
            ) {
              if (selection != null) BottomSheet(
                ui.selection != null, viewModel::clearSelection, expanded, { expanded = it },
                collapsedHeight, maxHeight - top - EDGE - sizes.barHeight - EDGE,
              ) { drag, dragging ->
                SelectionCard(
                  selection, swatch, current.swapXY, viewModel::clearSelection, openMenuOnLayer, onPick = viewModel::selectCandidate,
                  header = drag, animateSize = !dragging, buttonSize = sizes.rowHeight,
                )
              }
            }
          } else {
            // развёрнутая — от нижнего отступа до строки ≡ и карточки файла с зазором EDGE; обе стоят внутри
            // системных отступов (safe), поэтому их сверху и снизу вычитаем: на планшете статус-бар и навигация
            val insets = WindowInsets.safeDrawing
            val inset = with(LocalDensity.current) { (insets.getTop(this) + insets.getBottom(this)).toDp() }
            val fullHeight = maxHeight - inset - EDGE - sizes.barHeight - EDGE - EDGE
            Appear(ui.selection != null, bottomLeft) { appear ->
              if (selection != null) SelectionCard(
                selection, swatch, current.swapXY, viewModel::clearSelection, openMenuOnLayer,
                onPick = viewModel::selectCandidate, expanded = expanded, onExpand = { expanded = it }, buttonSize = sizes.rowHeight,
                modifier = Modifier
                  .width(SELECTION_WIDTH)
                  .heightIn(max = if (expanded) maxOf(fullHeight, collapsedHeight) else minOf(fullHeight, collapsedHeight))
                  .appearingCard(PANEL_SHAPE, 8.dp, appear, TransformOrigin(0f, 1f)),
              )
            }
          }
          // плашка автоматической ступени подписей: ~LABELS_NOTICE_MS от первого кадра сцены, раньше — по кнопке или
          // при открытии меню; жест камеры её не прячет. Пока она видна, подсказки о выборе нет
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
          val hintModifier = if (phone) Modifier.align(Alignment.BottomCenter).then(safe).padding(start = EDGE, end = EDGE, bottom = 24.dp).fillMaxWidth() else bottomLeft
          val hintWidth = if (phone) Modifier.fillMaxWidth() else Modifier.widthIn(max = 380.dp)
          AnimatedVisibility(notice != null, hintModifier, enter = fadeIn(tween(MOTION_MS)), exit = fadeOut(tween(MOTION_MS))) {
            if (lastNotice != null) {
              val off = lastNotice.tier == LabelTier.OFF
              ActionHint(
                if (off) stringResource(Res.string.labels_auto_off, groupDigits(lastNotice.labels.toLong())) else stringResource(Res.string.labels_auto_thin),
                stringResource(if (off) Res.string.labels_show else Res.string.labels_show_all),
                onAction = {
                  noticeDone = true
                  viewModel.changeSettings(ui.settings.withLabelTier(LabelTier.ALL))
                },
                modifier = hintWidth,
              )
            }
          }
          AnimatedVisibility(
            ui.selection == null && !hintHidden && notice == null,
            // телефон: внизу во всю ширину
            hintModifier,
            enter = fadeIn(tween(MOTION_MS)), exit = fadeOut(tween(MOTION_MS)),
          ) {
            SelectionHint(
              stringResource(if (phone) Res.string.selection_hint_touch else Res.string.selection_hint),
              onHide = {
                hintHidden = true
                prefs.save(PREF_HINT_HIDDEN, "1")
              },
              modifier = hintWidth,
            )
          }
        }

        // слева сверху: ≡ и карточка файла; на телефоне имя файла — в боковом меню, а карточка — только пока файл
        // читается или не открылся, до индикатора осей
        val topRow = if (phone) Modifier.fillMaxWidth().padding(end = AXIS_SIZE + 8.dp) else Modifier.width(sizes.panelWidth)
        Row(Modifier.align(Alignment.TopStart).then(safe).padding(EDGE).then(topRow), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          MenuButton(ui.menuOpen && !phone, sizes.barHeight, viewModel::toggleMenu)
          if (!phone || ui.loadState is LoadState.Loading || ui.loadState is LoadState.Failed) {
            FileCard(ui.loadState, current, sizes.barHeight, pickFile, viewModel::dismissError, Modifier.weight(1f))
          }
        }

        val onLanguage = { it: AppLanguage ->
          // строки нового языка — в кэш до переключения: первый кадр на нём сразу с текстом
          languageScope.launch {
            runCatching { preloadStrings(it.tag) }
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
          Appear(ui.menuOpen, Modifier.align(Alignment.TopStart).then(safe).padding(start = EDGE, top = EDGE + sizes.barHeight + EDGE, bottom = EDGE)) { appear ->
            // вырастает от ≡
            ViewerMenu(
              ui, viewModel, sections, language, onLanguage, pickFile, sizes.rowHeight,
              modifier = Modifier.width(sizes.panelWidth).appearingCard(PANEL_SHAPE, 12.dp, appear, TransformOrigin(0f, 0f)),
            )
          }
        }
      }
    }
  }
}

/** Сколько висит плашка автоматической ступени подписей от первого кадра сцены, мс. */
private const val LABELS_NOTICE_MS = 8_000L

/** Как часто на слабом устройстве пересчитывается указатель на ушедший чертёж, мс. */
private const val POINTER_SLOW_MS = 100L
// плашки указателей у края ближе этого сливаются в одну
private val POINTER_MERGE = 48.dp

/** Длительность появления и исчезновения панелей, мс. */
internal const val MOTION_MS = DesignTokens.MOTION_MS

/** Последнее не-null значение [value]: панель, которая гаснет, показывает то, что было в ней до закрытия. */
@Composable
private fun <T : Any> rememberLast(value: T?): T? {
  val last = remember { LastValue<T>() }
  if (value != null) last.value = value
  return last.value
}

private class LastValue<T : Any> {
  var value: T? = null
}

/**
 * Связь камеры рендера с ViewModel: схема управления и команды идут из ViewModel в контроллер, остановившаяся поза — обратно.
 * Покадровые изменения жестов через ViewModel не ходят, а запись позы экран не перекомпоновывает.
 */
@OptIn(FlowPreview::class)
@Composable
private fun CameraSync(controller: SceneCameraController, loaded: LoadState.Loaded, camera: CameraUiState, viewModel: ViewerViewModel) {
  LaunchedEffect(controller, camera.mode) { controller.switchMode(camera.mode) }
  // движение камеры по команде живёт в контроллере и идёт дольше этого эффекта: следующая команда его не обрывает
  // на полпути, а подхватывает с ходу
  val animations = rememberCoroutineScope()
  LaunchedEffect(controller, camera.command) {
    val command = camera.command ?: return@LaunchedEffect
    if (command is CameraCommand.Zoom) controller.animateZoom(command.factor, animations) else controller.execute(command, animations)
    viewModel.onCameraCommandConsumed(command)
  }
  LaunchedEffect(controller) {
    snapshotFlow { controller.pose }.debounce(POSE_SETTLE_MS).collect { viewModel.onCameraSettled(loaded, it) }
  }
  // экран закрывается (поворот, смена листа): не потерять позу последних миллисекунд; ViewModel отнесёт её к своему виду файла
  DisposableEffect(controller) { onDispose { viewModel.onCameraSettled(loaded, controller.pose) } }
}
