package dev.iskandern.kotin

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.text.rememberTextMeasurer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Modifier
import io.github.erkko68.filament.Box as FBox
import io.github.erkko68.filament.Engine
import io.github.erkko68.filament.MaterialInstance
import io.github.erkko68.filament.compose.FilamentSceneScope
import io.github.erkko68.filament.compose.FilamentView
import io.github.erkko68.filament.compose.FilamentViewState
import io.github.erkko68.filament.compose.rememberFilamentViewState
import io.github.erkko68.filament.compose.rememberFilamentScene
import io.github.erkko68.filament.compose.scene.CameraState
import io.github.erkko68.filament.compose.scene.LinearColor
import io.github.erkko68.filament.compose.scene.Position
import io.github.erkko68.filament.compose.scene.PostProcessing
import io.github.erkko68.filament.compose.scene.Projection
import io.github.erkko68.filament.compose.scene.Skybox
import io.github.erkko68.filament.compose.scene.rememberMaterial
import androidx.compose.ui.text.font.FontFamily
import dev.iskandern.kotin.dxf.DxfColors
import dev.iskandern.kotin.dxf.ScenePalette
import dev.iskandern.kotin.dxf.FACE_ARIMO
import dev.iskandern.kotin.dxf.FACE_CARLITO
import dev.iskandern.kotin.dxf.FACE_DEFAULT
import dev.iskandern.kotin.dxf.FACE_MONO
import dev.iskandern.kotin.dxf.FACE_MONO_BOLD
import dev.iskandern.kotin.dxf.FACE_MONO_ITALIC
import dev.iskandern.kotin.dxf.FACE_NARROW
import dev.iskandern.kotin.dxf.FACE_TINOS
import dev.iskandern.kotin.resources.Res
import dev.iskandern.kotin.resources.arimo
import dev.iskandern.kotin.resources.carlito
import dev.iskandern.kotin.resources.pt_sans_narrow
import dev.iskandern.kotin.resources.tinos
import dev.iskandern.kotin.resources.kotin_symbols
import dev.iskandern.kotin.scene.FACE_SYMBOLS
import dev.iskandern.kotin.scene.ColoredMesh
import dev.iskandern.kotin.scene.DepthBias
import dev.iskandern.kotin.scene.FILL_MATERIAL_PATH
import dev.iskandern.kotin.scene.rememberFillMaterialInstance
import dev.iskandern.kotin.scene.LINE_MATERIAL_PATH
import dev.iskandern.kotin.scene.LineMesh
import dev.iskandern.kotin.scene.MIN_LINE_WIDTH_PX
import dev.iskandern.kotin.scene.HALO_DP
import dev.iskandern.kotin.scene.OUTLINE_RGB
import dev.iskandern.kotin.scene.OUTLINE_WIDTH_DP
import dev.iskandern.kotin.scene.POINT_HALO_DP
import dev.iskandern.kotin.scene.RIBBON_CAPSULE_MATERIAL_PATH
import dev.iskandern.kotin.scene.RIBBON_MATERIAL_PATH
import dev.iskandern.kotin.scene.TEXT_MATERIAL_PATH
import dev.iskandern.kotin.scene.TextMeshes
import dev.iskandern.kotin.scene.TextQuads
import dev.iskandern.kotin.scene.buildGlyphAtlas
import dev.iskandern.kotin.scene.fontCharacters
import dev.iskandern.kotin.scene.applyTone
import dev.iskandern.kotin.scene.buildSelectedLabelsMesh
import dev.iskandern.kotin.scene.SELECTED_LABELS_BLEND_ORDER
import dev.iskandern.kotin.scene.SELECTED_MARKS_BLEND_ORDER
import dev.iskandern.kotin.scene.buildSelectedMarksMesh
import dev.iskandern.kotin.scene.CANDIDATE_DIGITS
import dev.iskandern.kotin.scene.CANDIDATE_MARKS_BLEND_ORDER
import dev.iskandern.kotin.scene.buildCandidateMarksMesh
import dev.iskandern.kotin.scene.buildTextMeshes
import dev.iskandern.kotin.scene.rememberGlyphTexture
import dev.iskandern.kotin.scene.rememberTextMaterialInstance
import dev.iskandern.kotin.scene.GlyphAtlas
import dev.iskandern.kotin.scene.RibbonMesh
import dev.iskandern.kotin.scene.buildRibbon
import dev.iskandern.kotin.scene.rememberRibbonMaterialInstance
import dev.iskandern.kotin.scene.SURFACE_MATERIAL_PATH
import dev.iskandern.kotin.scene.TRANSPARENT_FILL_MATERIAL_PATH
import dev.iskandern.kotin.model.ALPHA_OPAQUE
import dev.iskandern.kotin.scene.bakeSurfaceColors
import dev.iskandern.kotin.scene.displayColor
import dev.iskandern.kotin.scene.rememberLineMaterialInstance
import dev.iskandern.kotin.scene.rememberSurfaceMaterialInstance
import dev.iskandern.kotin.scene.scaledPositions
import dev.iskandern.kotin.scene.plus
import dev.iskandern.kotin.scene.times
import dev.iskandern.kotin.scene.RenderScene
import dev.iskandern.kotin.scene.linearColor
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import dev.iskandern.kotin.model.toRadians

/** Что показывать. [showLines] — отрезки чертежа (подложка), [verticalScale] — вертикальное преувеличение рельефа. */
data class ViewSettings(
  val showSurface: Boolean = true,
  /** Колодцы и трубы LandXML (рукотворные сетки), отдельно от рельефа. */
  val showStructures: Boolean = true,
  val showWire: Boolean = true,
  val showLines: Boolean = true,
  val showLabels: Boolean = true,
  /** Прореживать подписи по масштабу (важные остаются, остальные появляются при приближении); выключено — показаны все. */
  val thinLabels: Boolean = false,
  val showPoints: Boolean = true,
  /** Плашки «Ещё объект» на далёкие объекты у края; плашку «Чертёж» не касается. */
  val showOutlierPointers: Boolean = true,
  /** Толщина точек (код 39 у POINT) — отрезки вдоль нормали; видна только вместе с точками. */
  val showPointThickness: Boolean = false,
  val showLineWeights: Boolean = true,
  /** Не рисовать подписи и точки, закрытые поверхностью от камеры. */
  val hideOccluded: Boolean = true,
  val verticalScale: Float = 1f,
  val hiddenLayers: Set<String> = emptySet(),
  /** Пикетаж с промежуточными пикетами; выключено — только концы трасс (слой [StationLabels.MID_LAYER] скрыт). */
  val allStations: Boolean = true,
)

class SceneCamera(val state: CameraState, val controller: SceneCameraController)

/**
 * Камера смотрит на центр сцены сверху-сбоку, плоский чертёж — строго сверху; ключуется сценой, чтобы новая
 * поверхность открывалась целиком. [state] — один на экран: вид рендера живёт дольше сцены и не пересоздаётся при её
 * смене (новый `CameraState` до первого изменения размера вида рисовал бы с отношением сторон 1).
 * [verticalScale] — вертикальное преувеличение: от него зависят габариты сцены и ближняя плоскость. [aspect] — ширина
 * кадра к высоте при открытии: по нему чертёж вписывается в кадр. [initialPose] — поза из ViewModel, с которой камера
 * продолжает после пересоздания экрана; без неё — домашний вид.
 */
@Composable
fun rememberSceneCamera(
  state: CameraState, scene: RenderScene, verticalScale: Float, aspect: Float = 1f, initialPose: CameraPose? = null,
): SceneCamera {
  val r = scene.radius
  val flat = isFlatScene(scene)
  val controller = remember(state, scene) {
    SceneCameraController(state, r, FOV_DEGREES, flat, initialPose, homePose(scene, aspect))
      // проекция новой сцены — сразу, до первого кадра: ближняя и дальняя плоскости прежней обрезали бы её
      .also { state.projection = perspective(state.eye, scene, verticalScale) }
  }
  // размер кадра сменился (окно, поворот экрана): нетронутый вид вписывается заново; дребезг размера при протяжке окна
  // пережидаем
  val refits = rememberCoroutineScope()
  LaunchedEffect(controller, aspect) {
    delay(REFIT_SETTLE_MS)
    controller.refitHome(homePose(scene, aspect), refits)
  }
  // Шаг буфера глубины ~ D² / near, поэтому near держим как можно дальше — у ближайшей точки габаритов сцены
  // (ближе геометрия быть не может, ничего не обрежется).
  // расстояния считаем от габаритов сцены, а не от target: pivot может уйти за её пределы.
  LaunchedEffect(state, scene, verticalScale) {
    snapshotFlow { state.eye }.collect { e -> state.projection = perspective(e, scene, verticalScale) }
  }
  return remember(state, controller) { SceneCamera(state, controller) }
}

/** Плоская сцена: разброс по высоте почти нулевой относительно габаритов в плане (чертёж без рельефа). */
internal fun isFlatScene(scene: RenderScene): Boolean =
  scene.halfY <= max(scene.halfX, scene.halfZ) * FLAT_HEIGHT_RATIO

/**
 * Стартовое положение камеры (центр сцены — в нуле). Кадр — по основной массе чертежа ([RenderScene.home]), без неё —
 * по всей сцене. Плоский чертёж — строго сверху, габариты кадра в плане вписаны в кадр с отношением сторон [aspect];
 * иначе вся сфера кадра в кадре: расстояние r / sin(половины угла обзора) с запасом, направление — сверху-сбоку.
 */
internal fun homeEye(scene: RenderScene, aspect: Float): Position {
  val home = scene.home
  val x = home?.x ?: 0f
  val z = home?.z ?: 0f
  val halfX = home?.halfX ?: scene.halfX
  val halfY = home?.halfY ?: scene.halfY
  val halfZ = home?.halfZ ?: scene.halfZ
  val radius = max(sqrt(halfX * halfX + halfY * halfY + halfZ * halfZ), 1f)
  if (isFlatScene(scene)) {
    val half = max(halfZ, halfX / aspect.coerceAtLeast(MIN_ASPECT))
    val distance = max(half / tan(toRadians(FOV_DEGREES / 2)).toFloat() * FLAT_FIT_MARGIN, radius * MIN_FLAT_DISTANCE)
    return Position(x, scene.halfY + distance, z)
  }
  val distance = radius / sin(toRadians(FOV_DEGREES / 2)).toFloat() * FIT_MARGIN
  val y = home?.y ?: 0f
  return Position(x, y + distance * VIEW_DIR_Y, z + distance * VIEW_DIR_Z)
}

/** Куда смотрит камера исходного вида: середина основной массы ([homeEye]), без неё — центр сцены. */
internal fun homePose(scene: RenderScene, aspect: Float): CameraPose {
  val eye = homeEye(scene, aspect)
  val home = scene.home ?: return CameraPose.lookingAt(0f, 0f, 0f, eye.x, eye.y, eye.z)
  // плоский чертёж — как прежде, на уровне центра сцены
  val y = if (isFlatScene(scene)) 0f else home.y
  return CameraPose.lookingAt(home.x, y, home.z, eye.x, eye.y, eye.z)
}

private fun perspective(eye: Position, scene: RenderScene, verticalScale: Float) = Projection.Perspective(
  fovDegrees = FOV_DEGREES,
  near = nearPlane(eye.x, eye.y, eye.z, scene, verticalScale).toDouble(),
  far = (length(eye) + scene.radius * max(verticalScale, 1f) * FAR_TO_RADIUS).toDouble(),
)

/**
 * Ближняя плоскость для камеры в (x, y, z): доля расстояния до габаритов сцены (с вертикальным масштабом
 * [verticalScale] и запасом [BOX_MARGIN]; 0 внутри). Доля меньше единицы — с запасом на угол обзора (до плоскости
 * считается по оси взгляда, а не по лучу), на ленты толстых линий и плашки подписей, которые шейдер подтягивает к камере.
 */
internal fun nearPlane(x: Float, y: Float, z: Float, scene: RenderScene, verticalScale: Float): Float {
  val dx = max(kotlin.math.abs(x) - scene.halfX - BOX_MARGIN, 0f)
  val dy = max(kotlin.math.abs(y) - scene.halfY * verticalScale - BOX_MARGIN, 0f)
  val dz = max(kotlin.math.abs(z) - scene.halfZ - BOX_MARGIN, 0f)
  return max(sqrt(dx * dx + dy * dy + dz * dz) * NEAR_TO_DISTANCE, MIN_NEAR)
}

private fun length(p: Position): Float = sqrt(p.x * p.x + p.y * p.y + p.z * p.z)

internal const val FOV_DEGREES = 45.0
private const val FIT_MARGIN = 1.05f
// высота не больше 1 % габарита в плане — сцена плоская, открывается сверху
private const val FLAT_HEIGHT_RATIO = 0.01f
// подписи в габариты сцены не входят и выступают за точки вставки — запас по краям кадра
private const val FLAT_FIT_MARGIN = 1.1f
private const val REFIT_SETTLE_MS = 120L
private const val MIN_ASPECT = 0.1f
private const val MIN_FLAT_DISTANCE = 0.01f
// единичное направление от центра сцены к камере: ~35° над горизонтом
private val VIEW_DIR_Y = 0.9f / sqrt(0.9f * 0.9f + 1.3f * 1.3f)
private val VIEW_DIR_Z = 1.3f / sqrt(0.9f * 0.9f + 1.3f * 1.3f)
private const val NEAR_TO_DISTANCE = 0.4f
private const val FAR_TO_RADIUS = 2f
private const val MIN_NEAR = 0.05f
private const val BOX_MARGIN = 1f

/**
 * Разведение совпадающих слоёв по глубине без сдвига геометрии: вершинный шейдер (`biasDepth` в `Materials.kt`)
 * тянет вершину к камере вдоль луча взгляда — точка на экране та же, отметки в данных не меняются, меньше
 * становится только глубина. Возвращает параметр `depthBias` на один шаг буфера глубины: шейдер сдвигает вершину
 * на долю `depthBias × глубина` расстояния до камеры, т. е. на глубина² / (near · 2¹⁶). Глубина в Filament
 * обратная (d ≈ near / z), шаг 16-битного буфера по z — как раз z² / (near · 2¹⁶). Сам буфер и на Android, и в web
 * 24-битный (Filament просит у EGL глубину 24 бита и на 16 не откатывается; #245) — единица здесь с запасом в 256 раз.
 */
internal fun depthBiasPerUnit(near: Float): Float = 1f / (near * DEPTH_STEPS)

private const val DEPTH_STEPS = 65536f

/**
 * Шагов 16-битного буфера глубины между соседними по рангу слоями. Прежний замер на эмуляторе (вид сверху с 900 м,
 * near = 45 м): подъём на 10 см (~0,4 шага) мигал, на 1 м (~4 шага) — нет.
 */
private const val UNITS_PER_RANK = 4f

/**
 * Доля глубины на единицу сдвига — аналог множителя наклона в polygonOffset. На наклонной грани глубина в пределах
 * пикселя меняется на ~глубина × угол пикселя (≈ 0,001 рад) / tg угла к лучу, и постоянного шага буфера мало: каркас
 * рвётся пунктиром о свою поверхность. На ранг — 0,4 % глубины (на 900 м — 3,6 м вдоль луча, на экране не видно).
 */
private const val DEPTH_FRACTION_PER_UNIT = 0.001f

/**
 * Шагов буфера глубины на ступень порядка прорисовки заливок — только постоянная часть сдвига, без запаса на наклон:
 * совпадающие заливки лежат в одной плоскости.
 */
private const val FILL_STEP_UNITS = 1f

/**
 * Потолок общего сдвига всех ступеней заливок — доля расстояния до камеры (как подъём каркаса на ранг). Шаг буфера
 * растёт с квадратом глубины: вид сверху (~2,5 ближних плоскостей) — [dev.iskandern.kotin.scene.MAX_FILL_STEPS]
 * ступеней как раз укладываются, дальше ступень сжимается, и в 16-битном буфере соседние могут слиться. Зато заливки
 * не тянутся к камере на десятки процентов и не проступают сквозь рельеф в наклонном виде.
 */
private const val FILL_MAX_SHIFT = 0.005f

// порядок отрисовки (RenderableManager.priority, 0..7, подписи — 7): при равной глубине виден нарисованный позже
private const val SURFACE_PRIORITY = 2
private const val WIRE_PRIORITY = 3
private const val FILL_PRIORITY = 4
// линии вместе с заливками: порядок между ними решает глубина (линии на шаг выше), а приоритеты 5–7 нужны обводке
private const val LINE_PRIORITY = FILL_PRIORITY

@Composable
/**
 * [engine] — общий на всё время работы: на web у Filament один WebGL-контекст, и новый движок на каждый
 * открытый файл ломает прежний (сцена следующего файла не рисуется).
 */
fun SceneView(
  engine: Engine,
  scene: RenderScene,
  camera: SceneCamera,
  settings: ViewSettings,
  modifier: Modifier = Modifier,
  /** Выбранный объект: подсвечивается поверх сцены; null — ничего не выбрано. */
  selection: SelectionGeometry? = null,
  /** Слой выбранного объекта. В сцене не подкрашивается: подсвечен только сам объект, слой — строкой в списке. */
  @Suppress("UNUSED_PARAMETER")
  highlightLayer: String? = null,
  /**
   * Номера кандидатов выбора: места попадания (по три числа, с вертикальным масштабом) и выбранный; null — номеров нет.
   * Рисуются в сцене поверх всего, нажатие на них разбирает [onTap].
   */
  candidateHits: FloatArray? = null,
  candidateIndex: Int = 0,
  /** Долгое нажатие на сцену: экранная точка и размер вида. */
  onLongPress: (Offset, IntSize) -> Unit = { _, _ -> },
  /** Короткое нажатие на месте: экранная точка и размер вида. */
  onTap: (Offset, IntSize) -> Unit = { _, _ -> },
  /** Сцена нарисована в первый раз: первый кадр после того, как материалы готовы и буферы созданы. */
  onShown: () -> Unit = {},
  /** Подписи и значки [scene] нарисованы в первый раз (атлас и квады готовы). */
  onTextShown: () -> Unit = {},
  /**
   * Сцена, которая придёт на смену [scene] (пересборка: оси, «в плоскость», лист; новый файл). Её подписи и значки
   * готовятся заранее, пока на экране прежняя; готово — [onNextReady], и экран меняет сцену без пустого кадра.
   */
  next: RenderScene = scene,
  onNextReady: (RenderScene) -> Unit = {},
  /** Вид Filament: через него экран читает историю кадров рендерера (замер ступени подписей). */
  viewState: FilamentViewState = rememberFilamentViewState(),
) {
  val shown by rememberUpdatedState(onShown)
  val k = settings.verticalScale
  // ближняя плоскость — та же, что у проекции камеры; от неё шаг разведения слоёв по глубине
  val depthUnit by remember(camera, scene, k) {
    derivedStateOf {
      camera.state.eye.let { DepthBias(depthBiasPerUnit(nearPlane(it.x, it.y, it.z, scene, k)), DEPTH_FRACTION_PER_UNIT) }
    }
  }
  val box = remember(scene, k) { FBox(floatArrayOf(0f, 0f, 0f), floatArrayOf(scene.halfX, scene.halfY * k + 1f, scene.halfZ)) }
  val topRank = (scene.surfaceRank.values.maxOrNull() ?: 0) + 1
  val surfaceColors = remember(scene) { surfaceColors(scene) }

  // рельеф — опора жестов карты: сетка в плане строится в фоне, жесты берут видимость и масштаб текущих настроек
  val terrain = remember(scene) { TerrainIndex(scene) }
  LaunchedEffect(terrain) { withContext(Dispatchers.Default) { terrain.build() } }
  val terrainSettings by rememberUpdatedState(settings)
  DisposableEffect(camera.controller, terrain) {
    camera.controller.terrainDistance = { ray -> terrain.distance(ray, terrainSettings) }
    onDispose { camera.controller.terrainDistance = null }
  }

  // подписи и точки — квады с SDF-атласом в самой сцене: раскладку по камере делает шейдер, на кадр CPU не нужен.
  // Атлас рисует Compose (нужен шрифт), геометрию квадов собираем в фоне
  val density = LocalDensity.current
  // пикселей кадра на dp
  val px = density.density
  val measurer = rememberTextMeasurer()
  val fonts = rememberLabelFonts()
  val fontChars by produceState<Map<Int, Set<Char>>?>(null) {
    value = runCatching { loadLabelFontChars() }.getOrDefault(emptyMap())
  }
  // подписи и значки — сцены на экране и следующей: следующая сменит эту, когда её подписи готовы
  var prepared by remember { mutableStateOf<List<PreparedText>>(emptyList()) }
  val shownScene by rememberUpdatedState(scene)
  LaunchedEffect(next, fonts, fontChars) {
    if (prepared.any { it.scene === next }) return@LaunchedEffect
    // символы по гарнитурам подписей
    val chars = HashMap<Int, HashSet<Char>>()
    next.labels.forEach { label -> chars.getOrPut(label.face) { HashSet() }.addAll(label.text.asIterable()) }
    // цифры номеров кандидатов выбора — в атласе всегда
    chars.getOrPut(FACE_DEFAULT) { HashSet() }.addAll(CANDIDATE_DIGITS.asIterable())
    // шрифты на web грузятся не сразу: атлас — когда готовы нужные гарнитуры, Golos (в нём значки точек) и знаки
    if (fontChars == null || (chars.keys + FACE_DEFAULT + FACE_SYMBOLS).any { it !in fonts }) return@LaunchedEffect
    // те же символы (оси, «в плоскость», лист с тем же текстом) — тот же атлас, текстура не перезаливается
    val atlas = prepared.firstOrNull { it.chars == chars }?.atlas ?: buildGlyphAtlas(chars, fonts, fontChars.orEmpty(), measurer, density)
    val meshes = withContext(Dispatchers.Default) { buildTextMeshes(next, atlas) }
    prepared = prepared.filter { it.scene === shownScene } + PreparedText(next, chars, atlas, meshes)
  }
  // сцену на экране сменили: подписи прежней больше не нужны
  LaunchedEffect(scene) { prepared = prepared.filter { it.scene === scene || it.scene === next } }
  // следующая готова (или ей нечего готовить) — экран её показывает; не дождались за MAX_HOLD_MS — всё равно
  val nextReady = next === scene || prepared.any { it.scene === next } || (next.labels.isEmpty() && next.points.isEmpty())
  val nextReadyCallback by rememberUpdatedState(onNextReady)
  LaunchedEffect(next, nextReady) {
    if (next === scene) return@LaunchedEffect
    if (!nextReady) delay(MAX_HOLD_MS)
    nextReadyCallback(next)
  }
  val text = prepared.firstOrNull { it.scene === scene }
  val atlas = text?.atlas
  val textMeshes = text?.meshes
  val textShown by rememberUpdatedState(onTextShown)
  LaunchedEffect(textMeshes) {
    if (textMeshes == null) return@LaunchedEffect
    withFrameNanos { }
    textShown()
  }

  val filamentScene = rememberFilamentScene(engine = engine) {
    Skybox(color = BACKGROUND)
    // свои unlit-материалы: стандартные filament-compose не собраны под OpenGL ES 2.0
    val surfaceMaterial = rememberMaterial { Res.readBytes(SURFACE_MATERIAL_PATH) } ?: return@rememberFilamentScene
    val lineMaterial = rememberMaterial { Res.readBytes(LINE_MATERIAL_PATH) } ?: return@rememberFilamentScene
    val ribbonMaterial = rememberMaterial { Res.readBytes(RIBBON_MATERIAL_PATH) } ?: return@rememberFilamentScene
    val textMaterial = rememberMaterial { Res.readBytes(TEXT_MATERIAL_PATH) } ?: return@rememberFilamentScene
    val fillMaterial = rememberMaterial { Res.readBytes(FILL_MATERIAL_PATH) } ?: return@rememberFilamentScene
    val transparentFillMaterial = rememberMaterial { Res.readBytes(TRANSPARENT_FILL_MATERIAL_PATH) } ?: return@rememberFilamentScene
    // лента обводки выбранного (капсула) не стоит в очереди материалов перед первым кадром: грузится после него или при
    // первом выборе и остаётся на всё время сцены; пока её нет, обводка линий ждёт
    var capsuleWanted by remember { mutableStateOf(false) }
    val capsuleMaterial = if (capsuleWanted || selection != null) rememberMaterial { Res.readBytes(RIBBON_CAPSULE_MATERIAL_PATH) } else null
    // заливки по слоям и признаку полупрозрачности: на группу — один экземпляр материала
    val fillGroups = remember(scene) { scene.fills.groupBy { it.layer to (it.alpha < ALPHA_OPAQUE) } }

    for (surface in scene.surfaces) {
      if (surface.layer in settings.hiddenLayers) continue
      val body = if (surface.structure) settings.showStructures else settings.showSurface
      // у труб каркас — рёбра их же сетки: пропадает вместе с ней
      if (surface.structure && !settings.showStructures) continue
      val color = surfaceColors.getValue(surface.layer)
      val rank = scene.surfaceRank.getValue(surface)
      // выбранная поверхность — в тоне подсветки со своей светотенью; слой в сцене не подкрашивается
      val isSelected = selection != null && surface.triangleObjects == null && surface.objectId == selection.objectId
      key(surface) {
        val positions = remember(surface, k) { scaledPositions(surface.positions, k) }
        if (body) {
          val colors = remember(surface, k) { bakeSurfaceColors(surface.normals, k, color) }
          val instance = rememberSurfaceMaterialInstance(surfaceMaterial, rank * UNITS_PER_RANK * depthUnit)
          instance.applyTone(color, if (isSelected) HIGHLIGHT_SURFACE_RGB else null)
          ColoredMesh(positions, colors, surface.indices, instance, box, SURFACE_PRIORITY)
        }
        if (settings.showWire) {
          // каркас на полшага ближе своей поверхности: и не мерцает о неё, и уходит под вышележащий слой
          val wireColor = if (body) WIRE_ON_SURFACE else displayColor(color)
          LineMesh(
            positions = positions,
            indices = surface.edges,
            material = rememberLineMaterialInstance(lineMaterial, wireColor, (rank + 0.5f) * UNITS_PER_RANK * depthUnit),
            boundingBox = box,
            priority = WIRE_PRIORITY,
          )
        }
        // внешняя граница — с поверхностью или каркасом, лентой на одном шаге глубины с каркасом
        val outline = surface.outline
        if (outline != null && outline.isNotEmpty() && (body || settings.showWire)) {
          val ribbon = remember(surface, k) { buildRibbon(positions, outline) }
          RibbonMesh(
            ribbon = ribbon,
            material = rememberRibbonMaterialInstance(
              ribbonMaterial, rgbColor(OUTLINE_RGB), OUTLINE_WIDTH_DP * px, (rank + 0.5f) * UNITS_PER_RANK * depthUnit,
            ),
            boundingBox = box,
            priority = WIRE_PRIORITY,
          )
        }
      }
    }

    val glyphs = atlas
    val meshes = textMeshes
    if (glyphs != null && meshes != null) {
      // подписи прячутся за рельефом тестом глубины; у выровненного в плоскость чертежа отметки не настоящие — не прячем
      val depthTest = settings.hideOccluded && settings.showSurface && !scene.drawingFlattened
      // текстура атласа и экземпляр материала — одни на сцену
      val glyphTexture = rememberGlyphTexture(glyphs)
      val textInstance = rememberTextMaterialInstance(textMaterial, glyphTexture, px, depthTest, settings.thinLabels)
      // белый ореол — свой проход перед текстом; у значков точек тоньше, без подкраски слоя
      val textHalo = rememberTextMaterialInstance(textMaterial, glyphTexture, px, depthTest, settings.thinLabels, HALO_DP)
      val pointHalo = rememberTextMaterialInstance(textMaterial, glyphTexture, px, depthTest, settings.thinLabels, POINT_HALO_DP)
      // ореолы выбранного значка — жёлтый и тёмный кант под ним; экземпляры на сцену, а не на выбор
      val markHalo = rememberTextMaterialInstance(textMaterial, glyphTexture, px, depthTest, settings.thinLabels, HIGHLIGHT_MARK_HALO_DP, HIGHLIGHT_RGB)
      val markEdge = rememberTextMaterialInstance(
        textMaterial, glyphTexture, px, depthTest, settings.thinLabels, HIGHLIGHT_MARK_HALO_DP + HIGHLIGHT_EDGE_DP, DxfColors.INK,
      )
      if (settings.showLabels) {
        for (mesh in meshes.labels) {
          if (mesh.layer in settings.hiddenLayers) continue
          key(mesh) { TextQuads(mesh, textInstance, k, textHalo) }
        }
      }
      if (settings.showPoints) {
        for (mesh in meshes.points) {
          if (mesh.layer in settings.hiddenLayers) continue
          // слой выбранного не подкрашивается — решение владельца; ореол значков точек тоньше
          key(mesh) { TextQuads(mesh, textInstance, k, pointHalo) }
        }
      }
      // выбранная точка — свой значок тёмным в жёлтом ореоле по силуэту с тёмным кантом; подпись без геометрии — так же
      // точкой. Поверх соседних подписей и значков и плашки своей подписи
      if (selection != null && (selection.points.isNotEmpty() || selection.anchors.isNotEmpty())) {
        val marks = remember(selection, glyphs) {
          val points = selection.points.take(MAX_OBJECT_MARKS)
          val anchors = selection.anchors.copyOf(minOf(selection.anchors.size, (MAX_OBJECT_MARKS - points.size) * 3))
          buildSelectedMarksMesh(points, anchors, scene.pointMode, scene.pointSize, DxfColors.INK, glyphs)
        }
        if (marks != null) key(marks) { TextQuads(marks, textInstance, k, markHalo, markEdge, SELECTED_MARKS_BLEND_ORDER) }
      }
      // подписи выбранного — тёмный текст на жёлтой плашке
      if (selection != null && selection.labels.isNotEmpty() && settings.showLabels && selection.labels[0].layer !in settings.hiddenLayers) {
        val marked = remember(selection, glyphs) { buildSelectedLabelsMesh(selection.labels, DxfColors.INK, HIGHLIGHT_RGB, glyphs) }
        if (marked != null) key(marked) { TextQuads(marked, textInstance, k, blendOrder = SELECTED_LABELS_BLEND_ORDER) }
      }
      // номера кандидатов — тёмные кружки с белым номером, выбранный жёлтый; поверх рельефа и всех подписей, с белым
      // кантом: кружки рядом и наложенные не сливаются. Места попадания уже с вертикальным масштабом — без своего
      if (candidateHits != null) {
        val candidates = remember(candidateHits, candidateIndex, glyphs) {
          buildCandidateMarksMesh(candidateHits, candidateIndex, DxfColors.INK, CANDIDATE_INK_RGB, HIGHLIGHT_RGB, DxfColors.INK, glyphs)
        }
        if (candidates != null) {
          val numberInstance = rememberTextMaterialInstance(textMaterial, glyphTexture, px, depthTest = false, thin = settings.thinLabels)
          val numberEdge = rememberTextMaterialInstance(textMaterial, glyphTexture, px, depthTest = false, thin = settings.thinLabels, CANDIDATE_EDGE_DP)
          key(candidates) { TextQuads(candidates, numberInstance, 1f, numberEdge, blendOrder = CANDIDATE_MARKS_BLEND_ORDER) }
        }
      }
    }

    // линии — над потолком ступеней заливок
    val linesBias = (topRank * UNITS_PER_RANK * depthUnit).let {
      if (scene.fillSteps > 1) it.copy(fraction = it.fraction + FILL_MAX_SHIFT) else it
    }
    if (settings.showLines) {
      // заливки — ближе каркаса верхней поверхности, но дальше контура и линий чертежа; между собой — по порядку
      // прорисовки DXF: ступень на шаг буфера глубины ближе (всего не больше FILL_MAX_SHIFT), линии — над потолком
      val fillBias = (topRank - 0.25f) * UNITS_PER_RANK * depthUnit
      val stepBias = FILL_STEP_UNITS * depthUnit.perDepth
      val maxStepShift = if (scene.fillSteps > 0) FILL_MAX_SHIFT / scene.fillSteps else 0f
      // материал заливок — свой на слой (и отдельно полупрозрачные, код 440: смешивание, глубину не пишут, линии под
      // ними видны): подсветка слоя — параметр материала, и сетки остальных слоёв ему не мешают
      for ((group, fills) in fillGroups) {
        val (layer, transparent) = group
        if (layer in settings.hiddenLayers) continue
        key(group) {
          val instance = rememberFillMaterialInstance(if (transparent) transparentFillMaterial else fillMaterial, fillBias, stepBias, maxStepShift)
          for (fill in fills) {
            key(fill) {
              val positions = remember(fill, k) { scaledPositions(fill.positions, k) }
              val alpha = fill.alpha / 255f
              val colors = remember(fill) { fill.colors?.let { vertexColors(it, alpha) } ?: flatColors(fill.color, fill.positions.size / 3, alpha) }
              ColoredMesh(positions, colors, fill.indices, instance, box, FILL_PRIORITY, fill.steps)
            }
          }
        }
      }
    }
    // лентами не толще RIBBON_SEGMENT_BUDGET отрезков на сцену: лента — четыре вершины на отрезок вместо общей пары
    val thickLines = remember(scene) { scene.lines.sumOf { it.lineCount } <= RIBBON_SEGMENT_BUDGET }
    for (lines in scene.lines) {
      val visible = if (lines.pointThickness) settings.showPoints && settings.showPointThickness else settings.showLines
      if (!visible || lines.layer in settings.hiddenLayers) continue
      key(lines) {
        val positions = remember(lines, k) { scaledPositions(lines.positions, k) }
        // цвет из DXF уже в sRGB, постобработки нет — идёт на экран как есть
        val color = rgbColor(lines.color)
        // толщина — в dp: вес линии DXF ([RenderLines.widthPx]) при «Толщине линий», но не тоньше MIN_DRAWING_LINE_DP —
        // тонкие линии в 1 px рядом с жирными точками и обводкой терялись. Огромный чертёж — тонкие GL-линии (память)
        val weighted = settings.showLineWeights && lines.widthPx > MIN_LINE_WIDTH_PX
        if (weighted || thickLines) {
          val widthDp = max(if (weighted) lines.widthPx else 0f, MIN_DRAWING_LINE_DP)
          val ribbon = remember(lines, k) { buildRibbon(positions, lines.indices) }
          RibbonMesh(
            ribbon = ribbon,
            material = rememberRibbonMaterialInstance(ribbonMaterial, color, widthDp * px, linesBias),
            boundingBox = box,
            priority = LINE_PRIORITY,
          )
        } else {
          LineMesh(
            positions = positions,
            indices = lines.indices,
            material = rememberLineMaterialInstance(lineMaterial, color, linesBias),
            boundingBox = box,
            priority = LINE_PRIORITY,
          )
        }
      }
    }

    // выбранный объект: заливка треугольников — над всем; отрезки — жёлтой обводкой с тёмным кантом и копией самой
    // линии (её цвет и толщина) поверх. Слои обводки не пишут глубину и друг с другом по глубине не сравниваются:
    // порядок кант → жёлтая → копия линии задан приоритетами, а тест глубины остаётся только против сцены (рельеф,
    // поверхности) — ленты чуть выше линий чертежа. Ленты одного слоя на соседних отрезках иначе дрались за пиксели
    // на стыках: квады разной ширины лежат в чуть разных плоскостях, и долями шага буфера их не развести. Линии других
    // объектов в полосе обводки скрыты. Поверхность целиком — тоном (см. выше); значки у точек и подписей — квадами подписей
    if (selection != null) {
      val highlightBias = linesBias.copy(fraction = linesBias.fraction + HIGHLIGHT_EXTRA_BIAS)
      key(selection) {
        val fillInstance = rememberFillMaterialInstance(transparentFillMaterial, highlightBias, 0f, 0f)
        HighlightTriangles(selection.triangles, k, HIGHLIGHT_RGB, HIGHLIGHT_ALPHA, fillInstance, box)
        // ленты обводки — на материале капсулы (круглые стыки); он подгружается отдельно, см. выше
        val capsule = capsuleMaterial
        if (capsule != null) {
          // над линиями — только на шаги буфера, без доли глубины: доля уводила бы обводку к камере сквозь рельеф
          val aboveLines = linesBias + DepthBias(HIGHLIGHT_ABOVE_UNITS * depthUnit.perDepth, 0f)
          val edgeInstance = rememberRibbonMaterialInstance(
            capsule, rgbColor(DxfColors.INK, contrast = false), (HIGHLIGHT_WIDTH_DP + 2 * HIGHLIGHT_EDGE_DP) * px, aboveLines, depthWrite = false,
          )
          val ribbonInstance = rememberRibbonMaterialInstance(
            capsule, rgbColor(HIGHLIGHT_RGB, contrast = false), HIGHLIGHT_WIDTH_DP * px, aboveLines, depthWrite = false,
          )
          // копия линии — как сама линия: лента по весу или толстая, иначе тонкая GL-линия (см. слои линий выше)
          val lineColor = rgbColor(selection.lineRgb)
          val weighted = settings.showLineWeights && selection.lineWidthPx > MIN_LINE_WIDTH_PX
          val lineInstance = if (weighted || thickLines) {
            rememberRibbonMaterialInstance(
              capsule, lineColor, max(if (weighted) selection.lineWidthPx else 0f, MIN_DRAWING_LINE_DP) * px, aboveLines, depthWrite = false,
            )
          } else {
            rememberLineMaterialInstance(lineMaterial, lineColor, aboveLines, depthWrite = false)
          }
          HighlightSegments(selection.segments, k, ribbonInstance, edgeInstance, lineInstance, weighted || thickLines, box)
          // контур заливок — та же обводка без копии линии: заливка своего цвета остаётся под ней
          HighlightSegments(selection.outline, k, ribbonInstance, edgeInstance, null, false, box)
        }
      }
    }

    // сюда тело доходит, когда все материалы готовы: буферы применены, а кадр после них и есть первое изображение
    // (подписи и точки могут подойти следом, когда соберётся атлас)
    LaunchedEffect(scene) {
      withFrameNanos { }
      shown()
      capsuleWanted = true
    }
  }

  val longPress by rememberUpdatedState(onLongPress)
  val tap by rememberUpdatedState(onTap)
  Box(modifier.fillMaxSize()) {
    FilamentView(
      scene = filamentScene,
      modifier = Modifier.fillMaxSize().sceneGestures(camera.controller, { position, size -> tap(position, size) }) { position, size ->
        longPress(position, size)
      },
      cameraState = camera.state,
      viewState = viewState,
      // на OpenGL ES 2.0 постобработки нет; освещение и гамма уже в цветах вершин
      postProcessing = PostProcessing(enabled = false),
      shadows = null,
    )
  }
}

/** Наименьшая толщина линий чертежа на экране, dp: и у линий без своего веса (DXF 0 / по умолчанию). */
private const val MIN_DRAWING_LINE_DP = 1.5f

/**
 * Сколько отрезков чертежа ещё рисуется лентами. Лента — 4 вершины по 7 чисел и 6 индексов на отрезок (~136 байт),
 * 300 тысяч — ~40 МБ; больше — линии без своего веса остаются GL-линиями в 1 px (на ES 2.0 glLineWidth толще 1 не
 * гарантирован), как было.
 */
private const val RIBBON_SEGMENT_BUDGET = 300_000

/** Подписи и значки точек [scene]: атлас символов [chars] и сетки квадов. */
private class PreparedText(val scene: RenderScene, val chars: Map<Int, Set<Char>>, val atlas: GlyphAtlas, val meshes: TextMeshes)

/** Дольше этого прежняя сцена не держится, даже если подписи новой не готовы (шрифты ещё грузятся). */
private const val MAX_HOLD_MS = 1500L

/** Закрашенные треугольники подсветки ([triangles] — по девять чисел) полупрозрачным [rgb]. */
@Composable
private fun FilamentSceneScope.HighlightTriangles(
  triangles: FloatArray, verticalScale: Float, rgb: Int, alpha: Float, material: MaterialInstance, box: FBox,
) {
  if (triangles.isEmpty()) return
  val positions = remember(triangles, verticalScale) { scaledPositions(triangles, verticalScale) }
  val count = positions.size / 3
  val colors = remember(triangles, rgb, alpha) { flatColors(rgb, count, alpha, contrast = false) }
  val steps = remember(triangles) { FloatArray(count) }
  val indices = remember(triangles) { IntArray(count) { it } }
  ColoredMesh(positions, colors, indices, material, box, HIGHLIGHT_PRIORITY, steps)
}

/**
 * Отрезки подсветки ([segments] — по шесть чисел): одна сетка ленты на материалах канта [edge] и ленты [material], поверх —
 * копия линии на материале [line] (лента при [lineAsRibbon], иначе GL-линия); [line] null — без копии (контур заливки).
 */
@Composable
private fun FilamentSceneScope.HighlightSegments(
  segments: FloatArray, verticalScale: Float, material: MaterialInstance, edge: MaterialInstance, line: MaterialInstance?,
  lineAsRibbon: Boolean, box: FBox,
) {
  if (segments.isEmpty()) return
  val positions = remember(segments, verticalScale) { scaledPositions(segments, verticalScale) }
  val indices = remember(positions) { IntArray(positions.size / 3) { it } }
  val ribbon = remember(segments, verticalScale) { buildRibbon(positions, indices) }
  RibbonMesh(ribbon = ribbon, material = edge, boundingBox = box, priority = HIGHLIGHT_EDGE_PRIORITY)
  RibbonMesh(ribbon = ribbon, material = material, boundingBox = box, priority = HIGHLIGHT_RIBBON_PRIORITY)
  if (line == null) return
  if (lineAsRibbon) RibbonMesh(ribbon = ribbon, material = line, boundingBox = box, priority = HIGHLIGHT_LINE_PRIORITY)
  else LineMesh(positions = positions, indices = indices, material = line, boundingBox = box, priority = HIGHLIGHT_LINE_PRIORITY)
}

// подсветка выбранного: обводка линий, ореол значка точки, плашка подписи, заливка треугольников
private const val HIGHLIGHT_RGB = 0xF5C400
// номер в тёмном кружке кандидата и белый кант кружков, dp
private const val CANDIDATE_INK_RGB = 0xFFFFFF
private const val CANDIDATE_EDGE_DP = 1.5f
// тон выбранной поверхности при полном свете (светотень своя)
private const val HIGHLIGHT_SURFACE_RGB = 0xD6C45C
private const val HIGHLIGHT_ALPHA = 0.45f
// ширина жёлтой обводки выбранной линии, dp
private const val HIGHLIGHT_WIDTH_DP = 6f
// тёмный кант (DxfColors.INK) снаружи жёлтой обводки линии и ореола значка, dp
private const val HIGHLIGHT_EDGE_DP = 1f
// заливка выбранного — прозрачная, выше линий (4), ниже подписей (7)
private const val HIGHLIGHT_PRIORITY = 6
// слои обводки без записи глубины, порядок — приоритетами после всех линий: кант, жёлтая лента, копия выбранной линии;
// подписи (7) — в прозрачном проходе, после всего непрозрачного
private const val HIGHLIGHT_EDGE_PRIORITY = 5
private const val HIGHLIGHT_RIBBON_PRIORITY = 6
private const val HIGHLIGHT_LINE_PRIORITY = 7
// шагов буфера глубины над линиями чертежа у слоёв обводки: тест глубины против сцены проходит там, где линия.
// Лента обводки лежит на глубине плоскости чертежа (CAPSULE_VERTEX_GROUND), а пересекающая линия-билборд — на
// глубине своей осевой: на её боковине (0,75 dp) в наклонном виде земля ближе на 0,75 dp × ctg угла места,
// 48 шагов покрывают это от ~23°
private const val HIGHLIGHT_ABOVE_UNITS = 48f
// сверх сдвига линий: заливка подсветки поверх них и поверх поверхности под ней
private const val HIGHLIGHT_EXTRA_BIAS = 0.002f
// жёлтый ореол по силуэту значка выбранной точки, dp; с кантом — 4 dp (предел поля SDF значков, MARKER_HALO_PX_PER_DP)
private const val HIGHLIGHT_MARK_HALO_DP = 3f
// у объекта из тысяч подписей и точек значки — у первых; остальное видно и так
private const val MAX_OBJECT_MARKS = 2000


/**
 * Цвета сеток по слою: поверхность — из [ScenePalette] по своему номеру среди поверхностей (у TIN в DXF цвет слоя часто
 * один и тот же), колодцы и трубы — цветом слоя своей сети: он зависит только от типа сети.
 */
fun surfaceColors(scene: RenderScene): Map<String, LinearColor> {
  val layerColors = scene.layerInfos.associate { it.name to it.color }
  val colors = LinkedHashMap<String, LinearColor>()
  var surfaces = 0
  for (surface in scene.surfaces) {
    if (surface.layer in colors) continue
    val rgb = if (surface.structure) layerColors[surface.layer] ?: ScenePalette.surface(0) else ScenePalette.surface(surfaces++)
    colors[surface.layer] = linearColor(rgb)
  }
  return colors
}

/** Цвет 0xRRGGBB для материала; [contrast] — подстроить под светлый фон ([DxfColors.onBackground]), у подсветки — нет. */
private fun rgbColor(rgb: Int, contrast: Boolean = true): LinearColor {
  val c = if (contrast) DxfColors.onBackground(rgb) else rgb
  return LinearColor(((c shr 16) and 0xFF) / 255f, ((c shr 8) and 0xFF) / 255f, (c and 0xFF) / 255f)
}

// одинаковый цвет во всех вершинах; цвет из DXF уже в sRGB
private fun flatColors(rgb: Int, vertexCount: Int, alpha: Float = 1f, contrast: Boolean = true): FloatArray {
  val colors = FloatArray(vertexCount * 4)
  val c = if (contrast) DxfColors.onBackground(rgb) else rgb
  val r = ((c shr 16) and 0xFF) / 255f
  val g = ((c shr 8) and 0xFF) / 255f
  val b = (c and 0xFF) / 255f
  for (v in 0 until vertexCount) {
    colors[v * 4] = r
    colors[v * 4 + 1] = g
    colors[v * 4 + 2] = b
    colors[v * 4 + 3] = alpha
  }
  return colors
}

// цвет 0xRRGGBB на вершину → RGBA
private fun vertexColors(rgb: IntArray, alpha: Float = 1f): FloatArray {
  val colors = FloatArray(rgb.size * 4)
  for (v in rgb.indices) {
    val c = DxfColors.onBackground(rgb[v])
    colors[v * 4] = ((c shr 16) and 0xFF) / 255f
    colors[v * 4 + 1] = ((c shr 8) and 0xFF) / 255f
    colors[v * 4 + 2] = (c and 0xFF) / 255f
    colors[v * 4 + 3] = alpha
  }
  return colors
}

/** Шрифты гарнитур подписей ([FACE_DEFAULT], [FACE_ARIMO]…), уже загруженные; на web появляются по мере загрузки. */
@Composable
fun rememberLabelFonts(): Map<Int, FontFamily> {
  val fonts = LABEL_FONTS.map { it.face to rememberFont(it.spec) }
  return remember(fonts) { fonts.mapNotNull { (face, font) -> font?.let { face to FontFamily(it) } }.toMap() }
}

/** Гарнитура подписей: файл в `composeResources/font` ([file] — имя без `.ttf`, для чтения cmap). */
internal class LabelFont(val face: Int, val spec: FontSpec, val file: String)

/** Гарнитуры подписей сцены; как выбрать — в DxfFonts.kt. */
internal val LABEL_FONTS: List<LabelFont> = listOf(
  LabelFont(FACE_DEFAULT, GOLOS_TEXT, "golos_text_regular"),
  // знаки, которых нет в гарнитурах подписей (⌀, ∅, греческие, знаки допусков): только запасной шрифт
  LabelFont(FACE_SYMBOLS, FontSpec(Res.font.kotin_symbols), "kotin_symbols"),
  LabelFont(FACE_ARIMO, FontSpec(Res.font.arimo), "arimo"),
  LabelFont(FACE_TINOS, FontSpec(Res.font.tinos), "tinos"),
  LabelFont(FACE_CARLITO, FontSpec(Res.font.carlito), "carlito"),
  LabelFont(FACE_NARROW, FontSpec(Res.font.pt_sans_narrow), "pt_sans_narrow"),
  LabelFont(FACE_MONO, MONO_REGULAR, "jetbrains_mono_regular"),
  LabelFont(FACE_MONO_BOLD, MONO_BOLD, "jetbrains_mono_bold"),
  LabelFont(FACE_MONO_ITALIC, MONO_ITALIC, "jetbrains_mono_semibold_italic"),
)

/** Символы, которые есть в файлах гарнитур (по cmap): остальные рисует Golos, нет в нём — шрифт платформы. */
private suspend fun loadLabelFontChars(): Map<Int, Set<Char>> =
  LABEL_FONTS.associate { it.face to fontCharacters(Res.readBytes("font/${it.file}.ttf")) }

// светлая тема: фон #E3E7EB (= DxfColors.BACKGROUND), значения идут на экран как есть
private val BACKGROUND = LinearColor(0.890f, 0.906f, 0.922f)
// тёмные рёбра поверх поверхности — #15202B на 38 % над зелёной заливкой, смешано заранее (линии непрозрачны)
private val WIRE_ON_SURFACE = LinearColor(0.41f, 0.48f, 0.40f)
