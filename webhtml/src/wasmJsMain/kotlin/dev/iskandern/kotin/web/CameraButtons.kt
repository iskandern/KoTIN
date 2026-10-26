package dev.iskandern.kotin.web

import androidx.compose.runtime.Composable
import dev.iskandern.kotin.CameraMode
import dev.iskandern.kotin.ZOOM_STEP
import dev.iskandern.kotin.resources.Res
import dev.iskandern.kotin.resources.camera_map
import dev.iskandern.kotin.resources.camera_map_hint
import dev.iskandern.kotin.resources.camera_orbit
import dev.iskandern.kotin.resources.camera_orbit_hint
import dev.iskandern.kotin.resources.zoom_in
import dev.iskandern.kotin.resources.zoom_out
import dev.iskandern.kotin.web.dom.AttrsScope
import dev.iskandern.kotin.web.dom.Div
import dev.iskandern.kotin.web.dom.Text
import dev.iskandern.kotin.web.dom.px
import org.jetbrains.compose.resources.stringResource

/**
 * Кнопки камеры справа снизу: схема управления «Орбита | Карта» (выбранная — тёмная плашка) и зум «− +» к центру кадра.
 * [buttonSize] — высота кнопок.
 */
@Composable
fun CameraButtons(mode: CameraMode, onMode: (CameraMode) -> Unit, onZoom: (Float) -> Unit, buttonSize: Int) {
  Div({ classes("camera-buttons"); ui("camera-buttons") }) {
    Div({ classes("camera-group", "floating"); ui("camera-mode") }) {
      ModeSegment("mode-orbit", stringResource(Res.string.camera_orbit), mode == CameraMode.ORBIT, buttonSize) { onMode(CameraMode.ORBIT) }
      ModeSegment("mode-map", stringResource(Res.string.camera_map), mode == CameraMode.MAP, buttonSize) { onMode(CameraMode.MAP) }
    }
    Div({ classes("camera-group", "floating"); ui("camera-zoom") }) {
      IconBox(Icon.Minus, stringResource(Res.string.zoom_out), buttonSize, { onZoom(ZOOM_STEP) })
      IconBox(Icon.Plus, stringResource(Res.string.zoom_in), buttonSize, { onZoom(1 / ZOOM_STEP) })
    }
  }
}

@Composable
private fun ModeSegment(tag: String, label: String, selected: Boolean, height: Int, onClick: () -> Unit) {
  Div({
    classes("mode-segment", "body-medium")
    ui(tag)
    if (selected) classes("on") else classes("ripple")
    attr("role", "radio")
    attr("aria-checked", selected.toString())
    style { property("height", height.px) }
    if (!selected) onClick { onClick() }
  }) { Text(label) }
}

/**
 * Кнопки камеры телефона — колонка у правого края: «+», «−» и схема управления значком (нажатие — другая схема).
 * Внизу место подсказки и шторки выбранного, поэтому колонка посередине края; [shift] — сдвиг вверх над шторкой, px.
 */
@Composable
fun CameraRail(mode: CameraMode, onMode: (CameraMode) -> Unit, onZoom: (Float) -> Unit, buttonSize: Int, shift: Int, attrs: (AttrsScope.() -> Unit)? = null) {
  Div({
    classes("camera-rail")
    ui("camera-rail")
    style { property("transform", "translateY(calc(-50% + ${shift}px))") }
    attrs?.invoke(this)
  }) {
    // колонка обрезана по форме карточки: подсветка нажатия «+» и «−» скруглена вместе с ней
    Div({ classes("rail-zoom", "floating"); ui("rail-zoom") }) {
      IconBox(Icon.Plus, stringResource(Res.string.zoom_in), buttonSize, { onZoom(1 / ZOOM_STEP) }, "0", iconSize = 22)
      Div({ classes("sep"); style { property("width", buttonSize.px) } })
      IconBox(Icon.Minus, stringResource(Res.string.zoom_out), buttonSize, { onZoom(ZOOM_STEP) }, "0", iconSize = 22)
    }
    val orbit = mode == CameraMode.ORBIT
    Div({ classes("rail-mode", "floating"); ui("rail-mode") }) {
      IconBox(
        if (orbit) Icon.Orbit else Icon.Map,
        stringResource(if (orbit) Res.string.camera_orbit_hint else Res.string.camera_map_hint),
        buttonSize,
        { onMode(if (orbit) CameraMode.MAP else CameraMode.ORBIT) },
        "50%",
        iconSize = 24,
      )
    }
  }
}
