package dev.iskandern.kotin

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.produceState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import io.github.erkko68.filament.Engine
import io.github.erkko68.filament.compose.FilamentView
import io.github.erkko68.filament.compose.FilamentViewState
import io.github.erkko68.filament.compose.rememberFilamentViewState
import io.github.erkko68.filament.compose.rememberFilamentScene
import io.github.erkko68.filament.compose.scene.PostProcessing
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
import dev.iskandern.kotin.scene.buildGlyphAtlas
import dev.iskandern.kotin.scene.RenderScene

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
  SceneTerrain(scene, camera, settings)

  // атлас подписей рисует Compose (нужен шрифт)
  val density = LocalDensity.current
  // пикселей кадра на dp
  val px = density.density
  val measurer = rememberTextMeasurer()
  val fonts = rememberLabelFonts()
  val fontChars by produceState<Map<Int, Set<Char>>?>(null) {
    value = runCatching { loadLabelFontChars() }.getOrDefault(emptyMap())
  }
  val text = rememberSceneText(
    scene, next, fonts.keys, fontChars,
    buildAtlas = { chars -> buildGlyphAtlas(chars, fonts, fontChars.orEmpty(), measurer, density) },
    onNextReady = onNextReady, onTextShown = onTextShown,
  )

  val filamentScene = rememberFilamentScene(engine = engine) {
    SceneContent(scene, camera, settings, selection, candidateHits, candidateIndex, text, px, onShown)
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

/** Шрифты гарнитур подписей ([FACE_DEFAULT], [FACE_ARIMO]…), уже загруженные; на web появляются по мере загрузки. */
@Composable
fun rememberLabelFonts(): Map<Int, FontFamily> {
  val fonts = LABEL_FONTS.map { it.face to rememberFont(it.spec) }
  return remember(fonts) { fonts.mapNotNull { (face, font) -> font?.let { face to FontFamily(it) } }.toMap() }
}

/** Гарнитура подписей: файл в `composeResources/font` ([file] — имя без `.ttf`, для чтения cmap). */
internal class LabelFont(val face: Int, val spec: FontSpec) {
  val file: String get() = LABEL_FONT_FILES.getValue(face)
}

/** Гарнитуры подписей сцены; файлы — [LABEL_FONT_FILES]. */
internal val LABEL_FONTS: List<LabelFont> = listOf(
  LabelFont(FACE_DEFAULT, GOLOS_TEXT),
  LabelFont(FACE_SYMBOLS, FontSpec(Res.font.kotin_symbols)),
  LabelFont(FACE_ARIMO, FontSpec(Res.font.arimo)),
  LabelFont(FACE_TINOS, FontSpec(Res.font.tinos)),
  LabelFont(FACE_CARLITO, FontSpec(Res.font.carlito)),
  LabelFont(FACE_NARROW, FontSpec(Res.font.pt_sans_narrow)),
  LabelFont(FACE_MONO, MONO_REGULAR),
  LabelFont(FACE_MONO_BOLD, MONO_BOLD),
  LabelFont(FACE_MONO_ITALIC, MONO_ITALIC),
)
