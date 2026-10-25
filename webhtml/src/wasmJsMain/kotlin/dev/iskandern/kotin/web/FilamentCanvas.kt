package dev.iskandern.kotin.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.unit.IntSize
import dev.iskandern.kotin.web.dom.AttrsScope
import dev.iskandern.kotin.web.dom.Existing
import io.github.erkko68.filament.BlendMode
import io.github.erkko68.filament.Engine
import io.github.erkko68.filament.NativeSurface
import io.github.erkko68.filament.SwapChain
import io.github.erkko68.filament.Viewport
import io.github.erkko68.filament.compose.FilamentScene
import io.github.erkko68.filament.compose.FilamentViewState
import io.github.erkko68.filament.compose.internal.rememberOwned
import io.github.erkko68.filament.compose.scene.CameraState
import kotlinx.browser.window
import kotlin.math.roundToInt

/**
 * Вид сцены [scene] в canvas страницы ([sceneCanvas]: в нём контекст WebGL движка) — то же, что `FilamentView`
 * filament-compose, но без Compose UI: размер — по CSS-размеру canvas, кадр — каждый кадр композиции. Без постобработки
 * и теней: освещение и гамма уже в цветах вершин.
 */
@Composable
fun FilamentCanvas(
  scene: FilamentScene,
  cameraState: CameraState,
  viewState: FilamentViewState,
  attrs: (AttrsScope.() -> Unit)? = null,
) {
  val engine = scene.engine
  val renderer = rememberOwned(engine, create = { engine.createRenderer() }) { engine.destroy(it) }
  val view = rememberOwned(engine, scene.scene, dependsOn = listOf(scene.scene), create = { engine.createView() }) { engine.destroy(it) }
  val camera = rememberOwned(engine, create = { engine.createCamera(engine.entityManager.create()) }) {
    engine.destroyCameraComponent(it.entity)
    engine.entityManager.destroy(it.entity)
  }
  val holder = remember { SwapChainHolder() }

  DisposableEffect(view, scene.scene, camera) {
    view.scene = scene.scene
    view.camera = camera
    view.isFrustumCullingEnabled = true
    view.isShadowingEnabled = false
    view.isPostProcessingEnabled = false
    view.blendMode = BlendMode.OPAQUE
    onDispose {}
  }
  DisposableEffect(viewState, view, renderer) {
    viewState.attach(view, renderer)
    onDispose { viewState.detach() }
  }
  DisposableEffect(cameraState, camera) {
    cameraState.attach(camera)
    onDispose { cameraState.detach(camera) }
  }
  val snapshot = cameraState.snapshot()
  SideEffect { snapshot.applyTo(camera, cameraState.aspect) }

  // размер canvas в пикселях устройства — по его размеру на странице
  DisposableEffect(view, camera, cameraState) {
    val canvas = sceneCanvas
    fun resize() {
      val ratio = window.devicePixelRatio
      val width = (canvas.clientWidth * ratio).roundToInt()
      val height = (canvas.clientHeight * ratio).roundToInt()
      if (width <= 0 || height <= 0) return
      if (canvas.width != width || canvas.height != height) {
        canvas.width = width
        canvas.height = height
      }
      view.viewport = Viewport(0, 0, width, height)
      viewState.layoutSize = IntSize(width, height)
      cameraState.aspect = width.toDouble() / height
      cameraState.snapshot().applyTo(camera, cameraState.aspect)
    }
    resize()
    val observer = observeResize(canvas) { resize() }
    onDispose { unobserve(observer) }
  }

  LaunchedEffect(renderer, view) {
    while (true) {
      withFrameNanos {
        if (sceneCanvas.width > 1) {
          val swapChain = holder.swapChain ?: engine.createSwapChain(NativeSurface(sceneCanvas)).also { holder.swapChain = it }
          if (renderer.beginFrame(swapChain, Engine.steadyClockTimeNano)) {
            renderer.render(view)
            renderer.endFrame()
          }
        }
      }
    }
  }
  DisposableEffect(engine) {
    onDispose { holder.swapChain?.let { engine.destroy(it) } }
  }

  Existing(sceneCanvas, attrs)
}

private class SwapChainHolder {
  var swapChain: SwapChain? = null
}

@JsFun("(element, callback) => { const o = new ResizeObserver(() => callback()); o.observe(element); return o; }")
private external fun observeResize(element: org.w3c.dom.Element, callback: () -> Unit): JsAny

@JsFun("(observer) => observer.disconnect()")
private external fun unobserve(observer: JsAny)
