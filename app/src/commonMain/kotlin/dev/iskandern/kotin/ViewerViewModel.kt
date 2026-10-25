package dev.iskandern.kotin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.iskandern.kotin.landxml.StationLabels
import dev.iskandern.kotin.model.Journal
import dev.iskandern.kotin.model.SceneObject
import dev.iskandern.kotin.model.UiText
import dev.iskandern.kotin.scene.RenderScene
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.time.TimeSource

/** Выбранный объект сцены: номер в [RenderScene.objects], его описание и геометрия для подсветки. */
/** Кандидат выбора — объект под нажатием ([hit] — место в сцене для номера на экране), вид и имя для списка. */
class PickCandidate(val hit: PickHit, val kind: String, val name: String)

/**
 * Выбранный объект; [candidates] — все объекты под тем же нажатием, лучший первым (см. `pickCandidates`), [index] —
 * который из них выбран. Повторное нажатие в то же место и стрелки в карточке переходят к следующему.
 */
class ObjectSelection(
  val objectId: Int, val objectInfo: SceneObject, val geometry: SelectionGeometry,
  val candidates: List<PickCandidate> = emptyList(), val index: Int = 0,
) {
  /** Слой объекта: подсвечивается в сцене и в списке слоёв; null — слоя нет. */
  val layer: String? get() = objectInfo.layer.takeIf { it.isNotEmpty() }

  /** Объект ещё показан при [settings]; иначе выбор снимается. */
  fun isVisibleIn(settings: ViewSettings) = geometry.isVisibleIn(settings, objectInfo.layer)
}

/** Всё, что показывает экран просмотра. */
data class ViewerUiState(
  val loadState: LoadState = LoadState.Idle,
  /** Последняя открытая сцена остаётся на экране, пока грузится следующая. */
  val shownScene: LoadState.Loaded? = null,
  val settings: ViewSettings = ViewSettings(),
  /** Меню открыто; по умолчанию закрыто — сцена во весь экран. */
  val menuOpen: Boolean = false,
  val camera: CameraUiState = CameraUiState(),
  /** Выбранный долгим нажатием объект показанной сцены; null — ничего не выбрано. */
  val selection: ObjectSelection? = null,
  /** Сколько прошло от появления [shownScene] до её первого кадра, мс; null — кадра ещё не было. */
  val showMillis: Long? = null,
  /** Подписи выставлены автоматически по нагрузке файла (не «все»); null — как обычно или выбраны вручную. */
  val autoLabels: AutoLabels? = null,
  /** Ждём замер кадров показанного файла: медленные кадры понижают ступень подписей. */
  val frameCheck: Boolean = false,
)

/**
 * Автоматическая ступень подписей файла: [tier] — ступень, [labels] — сколько подписей; [serial] — номер решения
 * (новый файл или понижение по замеру): по нему показывается плашка.
 */
class AutoLabels(val tier: LabelTier, val labels: Int, val serial: Int)

/** Вид сцены: размер в px и плотность (px на dp) — от них нагрузка подписей. */
class Viewport(val width: Int, val height: Int, val density: Float)

/** [scene] нарисована за [millis]; запоздавший кадр прежней сцены и повторный кадр этой не в счёт. */
internal fun ViewerUiState.shownAfter(scene: RenderScene, millis: Long): ViewerUiState =
  if (shownScene?.scene === scene && showMillis == null) copy(showMillis = millis) else this

/** Ступень подписей, которую показывают настройки. */
val ViewSettings.shownLabelTier: LabelTier
  get() = if (!showLabels) LabelTier.OFF else if (thinLabels) LabelTier.THIN else LabelTier.ALL

/**
 * Ступень подписей [scene] по нагрузке и сама нагрузка: слои [hidden] скрыты, вид [view] (нет — все); устройство,
 * медленное по прежнему замеру ([slow]), — на ступень легче.
 */
internal fun autoLabelTier(scene: RenderScene, hidden: Set<String>, view: Viewport?, slow: Boolean): Pair<LabelTier, LabelLoad> {
  val load = scene.labelLoad(hidden)
  val byLoad = if (view == null) LabelTier.ALL else labelTier(load, view.density, view.width, view.height)
  return (if (slow && load.labels > 0) byLoad.lighter() else byLoad) to load
}

/**
 * Пересобранная сцена [rebuilt] (оси X/Y, «в плоскость», лист) на экране с настройками [settings]. Подписи не
 * переключали вручную ([manual]) — ступень заново по нагрузке новой сцены, как при открытии файла, и новый замер
 * кадров; новый номер плашки ([nextSerial]) — только если ступень сменилась. Вручную — подписи как выбраны.
 */
internal fun ViewerUiState.withRebuiltScene(
  rebuilt: LoadState.Loaded, settings: ViewSettings, view: Viewport?, slow: Boolean, manual: Boolean, nextSerial: () -> Int,
): ViewerUiState {
  if (manual) return copy(shownScene = rebuilt, settings = settings)
  val (tier, load) = autoLabelTier(rebuilt.scene, settings.hiddenLayers, view, slow)
  val auto = when {
    tier == LabelTier.ALL -> null
    tier == autoLabels?.tier -> AutoLabels(tier, load.labels, autoLabels.serial)
    else -> AutoLabels(tier, load.labels, nextSerial())
  }
  return copy(
    shownScene = rebuilt,
    settings = settings.withLabelTier(tier),
    autoLabels = auto,
    frameCheck = tier != LabelTier.OFF && load.labels > 0 && !slow,
  )
}

/**
 * Состояние и действия экрана просмотра: открытие файла, пересборка сцены, настройки показа и камера. Живёт дольше
 * экрана — разобранная сцена и вид камеры переживают пересоздание. Рендер (движок, `CameraState`, жесты) остаётся в UI.
 */
class ViewerViewModel(initialSettings: ViewSettings = ViewSettings()) : ViewModel() {
  private val _state = MutableStateFlow(ViewerUiState(settings = initialSettings))
  val state: StateFlow<ViewerUiState> = _state.asStateFlow()

  private val poses = CameraPoseMemory()
  private var loadJob: Job? = null
  private var rebuildJob: Job? = null
  private var selectJob: Job? = null
  // последнее нажатие выбора: место на экране и поза камеры — повторное нажатие там же листает кандидатов
  private var lastTap: Tap? = null
  private var initialKey: Pair<String, Long?>? = null
  private var viewport: Viewport? = null
  // подписи файла переключены вручную: до следующего файла автоматика не вмешивается
  private var labelsManual = false
  private var autoSerial = 0

  // когда сцена, которую показывает экран, появилась в состоянии: от этого считается время показа
  private var shownMark = TimeSource.Monotonic.markNow()

  /**
   * Открывает файл, с которым запущено приложение (аргумент, intent, адрес страницы). Экран при пересоздании отдаёт тот
   * же файл снова — его повторно не читаем.
   */
  fun openInitial(source: DrawingSource) {
    val key = source.name to source.sizeBytes
    if (key == initialKey) return
    initialKey = key
    open(source)
  }

  fun open(source: DrawingSource) {
    loadJob?.cancel()
    rebuildJob?.cancel()
    _state.update { it.copy(loadState = LoadState.Loading(source.name, progress = 0f, bytesRead = 0)) }
    loadJob = viewModelScope.launch {
      val result = loadScene(source) { loading -> _state.update { it.copy(loadState = loading) } }
      if (result is LoadState.Loaded) shownMark = TimeSource.Monotonic.markNow()
      _state.update { state ->
        if (result !is LoadState.Loaded) return@update state.copy(loadState = result)
        poses.reset(result.geometry)
        val view = viewport
        // промежуточные пикеты закрывают исходный вид — сначала только концы трасс
        val allStations = view == null || !result.scene.stationsCrowded(view)
        val hidden = if (allStations) result.hiddenByDefault() else result.hiddenByDefault() + StationLabels.MID_LAYER
        // ступень подписей — по нагрузке файла; медленное по прежнему замеру устройство — сразу на ступень легче
        val (tier, load) = autoLabelTier(result.scene, hidden, view, LabelFrames.slow)
        labelsManual = false
        state.copy(
          loadState = result,
          shownScene = result,
          // слои, выключенные или замороженные в DXF, сначала скрыты; веса линий — по $LWDISPLAY файла
          settings = state.settings.copy(
            hiddenLayers = hidden, allStations = allStations, showLineWeights = result.scene.lineWeightDisplay,
          ).withLabelTier(tier),
          camera = state.camera.forNewScene(),
          selection = null,
          showMillis = null,
          autoLabels = if (tier == LabelTier.ALL) null else AutoLabels(tier, load.labels, ++autoSerial),
          frameCheck = tier != LabelTier.OFF && load.labels > 0 && !LabelFrames.slow,
        )
      }
    }
  }

  /** Экран нарисовал первый кадр [scene]: время от её появления до этого кадра уходит на панель. */
  fun onSceneShown(scene: RenderScene) {
    val millis = shownMark.elapsedNow().inWholeMilliseconds
    val before = _state.value
    _state.update { it.shownAfter(scene, millis) }
    // экран пересоздан (смена языка, поворот) — сцена та же, время показа уже записано
    if (_state.value !== before) Journal.log(UiText.key("log_shown", _state.value.shownScene?.name.orEmpty(), millis))
  }

  /** Лист ([MODEL_SHEET] — модель); слои у листа свои: выключенные в DXF сначала скрыты, как при открытии файла. */
  fun selectSheet(sheet: Int) = rebuild(resetLayers = true) { rebuildScene(it, flattenDrawing = null, sheet = sheet) }

  /** Чертёж в плоскость и обратно: план тот же — камера остаётся над тем же местом, по высоте — у центра сцены. */
  fun setFlattenDrawing(flatten: Boolean) = rebuild(carryPose = { it.copy(pivotY = 0f) }) { rebuildScene(it, flattenDrawing = flatten) }

  /**
   * X и Y местами: сцена x = X − cx, z = cy − Y, поэтому точка (x, z) переходит в (−z, −x) — камера остаётся над тем же
   * местом с тем же приближением и наклоном.
   */
  fun setSwapXY(swap: Boolean) = rebuild(carryPose = { it.copy(pivotX = -it.pivotZ, pivotZ = -it.pivotX) }) {
    rebuildScene(it, flattenDrawing = it.scene.drawingFlattened, swapXY = swap)
  }

  /**
   * Новые настройки показа; выбранный объект, который стал скрыт (слой, вид), перестаёт быть выбранным. Подписи
   * переключены вручную — автоматика до следующего файла их не трогает.
   */
  fun changeSettings(settings: ViewSettings) = _state.update { state ->
    val manual = settings.showLabels != state.settings.showLabels || settings.thinLabels != state.settings.thinLabels
    if (manual) labelsManual = true
    state.copy(
      settings = settings,
      selection = state.selection?.takeIf { it.isVisibleIn(settings) },
      autoLabels = if (manual) null else state.autoLabels,
      frameCheck = state.frameCheck && !manual,
    )
  }

  /** Размер вида сцены и плотность: по ним выбирается ступень подписей следующего файла. */
  fun setViewport(width: Int, height: Int, density: Float) {
    viewport = Viewport(width, height, density)
  }

  /**
   * Замер кадров показанного файла: медиана интервалов [medianMillis]. Медленно — подписи на ступень легче, а
   * устройство до конца запуска считается медленным. Подписи уже переключены вручную — не вмешиваемся.
   */
  fun onFramesMeasured(medianMillis: Double) {
    if (!_state.value.frameCheck) return
    val slow = framesTooSlow(medianMillis)
    Journal.log(UiText.key(if (slow) "log_label_frames_slow" else "log_label_frames", medianMillis.roundToInt()))
    _state.update { state ->
      if (!state.frameCheck || labelsManual) return@update state.copy(frameCheck = false)
      if (!slow) return@update state.copy(frameCheck = false)
      LabelFrames.slow = true
      val tier = state.settings.shownLabelTier.lighter()
      val labels = state.shownScene?.scene?.labelLoad(state.settings.hiddenLayers)?.labels ?: 0
      state.copy(settings = state.settings.withLabelTier(tier), autoLabels = AutoLabels(tier, labels, ++autoSerial), frameCheck = false)
    }
  }

  /**
   * Выбирает объект под [ray] (долгое нажатие в точке экрана ([px], [py]) при позе камеры [pose]): поиск и сбор геометрии
   * подсветки идут в фоне. Под лучом ничего нет — выбор снимается. Повторное нажатие в то же место (в радиусе
   * попадания, камера не двигалась) переходит к следующему кандидату по кругу, как циклический выбор в AutoCAD.
   * [density] — пикселей в dp, от него окрестность попадания.
   */
  fun selectAt(ray: PickRay, density: Float, px: Float = 0f, py: Float = 0f, pose: CameraPose? = null) {
    val shown = _state.value.shownScene ?: return
    val settings = _state.value.settings
    val last = lastTap
    val current = _state.value.selection
    if (last != null && current != null && current.candidates.size > 1 && last.pose == pose &&
      hypot(px - last.x, py - last.y) <= PICK_RADIUS_DP * density
    ) {
      selectCandidate((current.index + 1) % current.candidates.size)
      return
    }
    lastTap = Tap(px, py, pose)
    selectJob?.cancel()
    selectJob = viewModelScope.launch {
      val selection = withContext(Dispatchers.Default) {
        val hits = pickCandidates(shown.scene, settings, ray, density)
        val candidates = hits.mapNotNull { hit -> shown.scene.objects.getOrNull(hit.objectId)?.let { PickCandidate(hit, it.kind, it.name) } }
        selectionOf(shown.scene, candidates, 0)
      }
      // пока искали, сцена могла смениться — выбор относится к прежней
      // и настройки могли смениться: скрытый за это время объект не выбирается
      _state.update { if (it.shownScene === shown) it.copy(selection = selection?.takeIf { s -> s.isVisibleIn(it.settings) }) else it }
    }
  }

  /** Переход к кандидату [index] текущего выбора (стрелки и список в карточке, номер в сцене). */
  fun selectCandidate(index: Int) {
    val shown = _state.value.shownScene ?: return
    val current = _state.value.selection ?: return
    if (index !in current.candidates.indices || index == current.index) return
    selectJob?.cancel()
    selectJob = viewModelScope.launch {
      val selection = withContext(Dispatchers.Default) { selectionOf(shown.scene, current.candidates, index) }
      _state.update { if (it.shownScene === shown && it.selection === current) it.copy(selection = selection) else it }
    }
  }

  // выбор кандидата [index]: свойства и геометрия подсветки; null — кандидатов нет
  private fun selectionOf(scene: RenderScene, candidates: List<PickCandidate>, index: Int): ObjectSelection? {
    val candidate = candidates.getOrNull(index) ?: return null
    val id = candidate.hit.objectId
    val info = scene.objects.getOrNull(id) ?: return null
    return ObjectSelection(id, withProperties(scene, id, info), collectObject(scene, id), candidates, index)
  }

  fun clearSelection() {
    selectJob?.cancel()
    lastTap = null
    _state.update { it.copy(selection = null) }
  }

  private class Tap(val x: Float, val y: Float, val pose: CameraPose?)

  fun toggleMenu() = _state.update { it.copy(menuOpen = !it.menuOpen) }

  fun setMenuOpen(open: Boolean) = _state.update { it.copy(menuOpen = open) }

  /** Убрать ошибку открытия: карточка файла снова показывает открытый файл. */
  fun dismissError() = _state.update {
    if (it.loadState is LoadState.Failed) it.copy(loadState = it.shownScene ?: LoadState.Idle) else it
  }

  fun setCameraMode(mode: CameraMode) = updateCamera { it.withMode(mode) }

  /** Просит камеру выполнить [command]: кнопка интерфейса или внешнее событие. */
  fun sendCameraCommand(command: CameraCommand) = updateCamera { it.send(command) }

  fun onCameraCommandConsumed(command: CameraCommand) = updateCamera { it.consumed(command) }

  /** Поза, с которой камера открывает [loaded]: где её оставили в этом виде файла; null — домашний вид. Не наблюдаемая. */
  fun cameraPoseOf(loaded: LoadState.Loaded): CameraPose? = poses.recall(loaded.viewKey())

  /** Камера остановилась на [pose] в [loaded]. Вид мог уже смениться (экран закрывается) — поза всё равно его, не нового. */
  fun onCameraSettled(loaded: LoadState.Loaded, pose: CameraPose) = poses.remember(loaded.geometry, loaded.viewKey(), pose)

  private fun updateCamera(change: (CameraUiState) -> CameraUiState) = _state.update { it.copy(camera = change(it.camera)) }

  /**
   * Пересборка показанной сцены. [carryPose] — как поза камеры прежнего вида переходит в новый (тот же файл в других
   * координатах); у нового вида уже есть своя запомненная поза — остаётся она; null — новый вид с домашнего.
   */
  private fun rebuild(
    resetLayers: Boolean = false, carryPose: ((CameraPose) -> CameraPose)? = null, build: suspend (LoadState.Loaded) -> LoadState.Loaded,
  ) {
    val current = _state.value.shownScene ?: return
    rebuildJob?.cancel()
    rebuildJob = viewModelScope.launch {
      // не вышло (например, не хватило памяти) — остаётся прежняя сцена
      val rebuilt = try {
        build(current)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Throwable) {
        println("KoTIN rebuild failed '${current.name}': $e")
        return@launch
      }
      shownMark = TimeSource.Monotonic.markNow()
      if (carryPose != null && poses.recall(rebuilt.viewKey()) == null) {
        poses.recall(current.viewKey())?.let { poses.remember(current.geometry, rebuilt.viewKey(), carryPose(it)) }
      }
      _state.update { state ->
        val settings = if (resetLayers) state.settings.copy(hiddenLayers = rebuilt.hiddenByDefault()) else state.settings
        state.copy(
          loadState = rebuilt,
          camera = state.camera.forNewScene(),
          selection = null,
          showMillis = null,
        ).withRebuiltScene(rebuilt, settings, viewport, LabelFrames.slow, labelsManual) { ++autoSerial }
      }
    }
  }

  private fun LoadState.Loaded.viewKey() = CameraViewKey(sheet, swapXY, scene.drawingFlattened)

  private fun LoadState.Loaded.hiddenByDefault(): Set<String> =
    scene.layerInfos.filter { !it.visibleByDefault }.map { it.name }.toSet()
}
