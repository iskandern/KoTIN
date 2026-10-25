package dev.iskandern.kotin.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import dev.iskandern.kotin.LABEL_FONT_FILES
import dev.iskandern.kotin.SceneCamera
import dev.iskandern.kotin.SceneContent
import dev.iskandern.kotin.SceneTerrain
import dev.iskandern.kotin.SelectionGeometry
import dev.iskandern.kotin.ViewSettings
import dev.iskandern.kotin.dxf.FACE_DEFAULT
import dev.iskandern.kotin.loadLabelFontChars
import dev.iskandern.kotin.rememberSceneText
import dev.iskandern.kotin.scene.FACE_SYMBOLS
import dev.iskandern.kotin.scene.GlyphLine
import dev.iskandern.kotin.scene.GlyphRasterizer
import dev.iskandern.kotin.scene.ATLAS_FONT_PX
import dev.iskandern.kotin.scene.RenderScene
import dev.iskandern.kotin.scene.buildGlyphAtlas
import dev.iskandern.kotin.scene.drawMarkerShape
import io.github.erkko68.filament.Engine
import io.github.erkko68.filament.compose.FilamentViewState
import io.github.erkko68.filament.compose.rememberFilamentScene
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLCanvasElement
import kotlin.js.Promise

/**
 * Сцена — тот же `SceneContent`, что у Compose-версии, в canvas страницы ([FilamentCanvas]); жесты камеры —
 * [attachSceneGestures]. Атлас подписей рисует canvas 2D теми же шрифтами. Параметры — как у `SceneView` app;
 * экранные точки и размер вида — в пикселях устройства.
 */
@Composable
fun SceneView(
  engine: Engine,
  scene: RenderScene,
  camera: SceneCamera,
  settings: ViewSettings,
  selection: SelectionGeometry? = null,
  candidateHits: FloatArray? = null,
  candidateIndex: Int = 0,
  onLongPress: (Offset, Float, Float) -> Unit = { _, _, _ -> },
  onTap: (Offset, Float, Float) -> Unit = { _, _, _ -> },
  onShown: () -> Unit = {},
  onTextShown: () -> Unit = {},
  next: RenderScene = scene,
  onNextReady: (RenderScene) -> Unit = {},
  viewState: FilamentViewState,
) {
  SceneTerrain(scene, camera, settings)

  val px = window.devicePixelRatio.toFloat()
  val loaded = rememberLabelFaces()
  val fontChars by produceState<Map<Int, Set<Char>>?>(null) {
    value = runCatching { loadLabelFontChars() }.getOrDefault(emptyMap())
  }
  val text = rememberSceneText(
    scene, next, loaded, fontChars,
    buildAtlas = { chars -> buildGlyphAtlas(chars, loaded, fontChars.orEmpty(), CanvasGlyphRasterizer(loaded, fontChars.orEmpty())) },
    onNextReady = onNextReady, onTextShown = onTextShown,
  )

  val filamentScene = rememberFilamentScene(engine = engine) {
    SceneContent(scene, camera, settings, selection, candidateHits, candidateIndex, text, px, onShown)
  }

  val tap by rememberUpdatedState(onTap)
  val longPress by rememberUpdatedState(onLongPress)
  val gestures = rememberCoroutineScope()
  DisposableEffect(camera.controller) {
    val detach = attachSceneGestures(sceneCanvas, camera.controller, gestures, { p, w, h -> tap(p, w, h) }, { p, w, h -> longPress(p, w, h) })
    onDispose { detach() }
  }
  FilamentCanvas(filamentScene, camera.state, viewState, attrs = { classes("scene-canvas") })
}

/** Имя семейства шрифта гарнитуры подписей в canvas. */
private fun familyOf(face: Int) = "kotin-label-${if (face < 0) "s" else face.toString()}"

/** Гарнитуры подписей, шрифты которых загружены (FontFace): появляются по мере загрузки. */
@Composable
private fun rememberLabelFaces(): Set<Int> {
  var loaded by remember { mutableStateOf(emptySet<Int>()) }
  LaunchedEffect(Unit) {
    for ((face, file) in LABEL_FONT_FILES) {
      launch {
        runCatching { loadFont(familyOf(face), "composeResources/dev.iskandern.kotin.resources/font/$file.ttf").await<JsAny?>() }
          .onSuccess { loaded = loaded + face }
      }
    }
  }
  return loaded
}

@JsFun("(family, url) => new FontFace(family, `url(${'$'}{url})`).load().then((f) => { document.fonts.add(f); return null; })")
private external fun loadFont(family: String, url: String): Promise<JsAny?>

/**
 * Растр атласа canvas 2D: шрифт гарнитуры, символа в нём нет — шрифт интерфейса (Golos), нет и в нём — знаки
 * KoTIN Symbols, затем шрифт браузера; [loaded] — загруженные гарнитуры, [fontChars] — их символы по cmap.
 */
private class CanvasGlyphRasterizer(private val loaded: Set<Int>, fontChars: Map<Int, Set<Char>>) : GlyphRasterizer {
  private val canvas = document.createElement("canvas") as HTMLCanvasElement
  private var ctx = context2d(canvas)
  private val defaultCovered = fontChars[FACE_DEFAULT]
  private val symbolsCovered = fontChars[FACE_SYMBOLS].orEmpty()
  private val fonts = HashMap<Pair<Int, Char>, String>()

  private fun css(family: String?) = "${ATLAS_FONT_PX}px " + (family?.let { "\"$it\"" } ?: "sans-serif")

  private fun faceFont(face: Int) = css(if (face in loaded) familyOf(face) else if (FACE_DEFAULT in loaded) familyOf(FACE_DEFAULT) else null)

  private fun fallbackFont(ch: Char) = when {
    FACE_DEFAULT !in loaded || defaultCovered == null || ch in defaultCovered -> faceFont(FACE_DEFAULT)
    ch in symbolsCovered && FACE_SYMBOLS in loaded -> css(familyOf(FACE_SYMBOLS))
    else -> css(null)
  }

  override fun measure(face: Int, char: Char, fallback: Boolean): GlyphLine {
    val font = if (fallback) fallbackFont(char) else faceFont(face)
    fonts[face to char] = font
    ctx.font = font
    val m = measureText(ctx, char.toString())
    return GlyphLine(m.width.toFloat(), m.ascent.toFloat(), (m.ascent + m.descent).toFloat())
  }

  override fun spaceAdvance(face: Int): Float {
    ctx.font = faceFont(face)
    return measureText(ctx, " ").width.toFloat()
  }

  override fun begin(width: Int, height: Int) {
    canvas.width = width
    canvas.height = height
    ctx = context2d(canvas)
    ctx.fillStyle = "#fff".toJsString()
    ctx.strokeStyle = "#fff".toJsString()
  }

  override fun text(face: Int, char: Char, fallback: Boolean, left: Float, top: Float) {
    ctx.font = fonts.getValue(face to char)
    val ascent = measureText(ctx, char.toString()).ascent
    ctx.fillText(char.toString(), left.toDouble(), top + ascent)
  }

  override fun marker(marker: Char, left: Float, top: Float) {
    drawMarkerShape(
      marker, left, top,
      circle = { cx, cy, radius, stroke ->
        ctx.beginPath()
        ctx.arc(cx.toDouble(), cy.toDouble(), radius.toDouble(), 0.0, 2 * kotlin.math.PI)
        if (stroke > 0f) { ctx.lineWidth = stroke.toDouble(); ctx.stroke() } else ctx.fill()
      },
      line = { x0, y0, x1, y1, width ->
        ctx.beginPath()
        ctx.moveTo(x0.toDouble(), y0.toDouble())
        ctx.lineTo(x1.toDouble(), y1.toDouble())
        ctx.lineWidth = width.toDouble()
        ctx.stroke()
      },
      rect = { x, y, w, h, stroke ->
        if (stroke > 0f) { ctx.lineWidth = stroke.toDouble(); ctx.strokeRect(x.toDouble(), y.toDouble(), w.toDouble(), h.toDouble()) }
        else ctx.fillRect(x.toDouble(), y.toDouble(), w.toDouble(), h.toDouble())
      },
    )
  }

  override fun coverage(): BooleanArray {
    val width = canvas.width
    val height = canvas.height
    val result = BooleanArray(width * height)
    val alpha = alphaMask(ctx, width, height)
    for (i in 0 until width * height) result[i] = byteAt(alpha, i) != 0
    return result
  }
}

private class TextMetrics(val width: Double, val ascent: Double, val descent: Double)

@JsFun("(ctx, s) => { const m = ctx.measureText(s); return [m.width, m.fontBoundingBoxAscent, m.fontBoundingBoxDescent]; }")
private external fun measureRaw(ctx: CanvasRenderingContext2D, s: String): JsAny

@JsFun("(a, i) => a[i]")
private external fun numberAt(array: JsAny, i: Int): Double

private fun measureText(ctx: CanvasRenderingContext2D, s: String): TextMetrics {
  val raw = measureRaw(ctx, s)
  return TextMetrics(numberAt(raw, 0), numberAt(raw, 1), numberAt(raw, 2))
}

@JsFun("(canvas) => canvas.getContext('2d', { willReadFrequently: true })")
private external fun context2d(canvas: HTMLCanvasElement): CanvasRenderingContext2D

// покрытие: альфа ≥ 0,5 — 1, иначе 0, одним проходом в JS
@JsFun("(ctx, w, h) => { const d = ctx.getImageData(0, 0, w, h).data; const out = new Uint8Array(w * h); for (let i = 0; i < w * h; i++) out[i] = d[i * 4 + 3] >= 128 ? 1 : 0; return out; }")
private external fun alphaMask(ctx: CanvasRenderingContext2D, width: Int, height: Int): JsAny

@JsFun("(a, i) => a[i]")
private external fun byteAt(array: JsAny, i: Int): Int
