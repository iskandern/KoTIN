package dev.iskandern.kotin

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import dev.iskandern.kotin.resources.Res
import dev.iskandern.kotin.resources.camera_map
import dev.iskandern.kotin.resources.camera_orbit
import dev.iskandern.kotin.resources.zoom_in
import dev.iskandern.kotin.resources.zoom_out

/** Шаг кнопок «− +»: во сколько раз меняется расстояние до точки интереса. */
internal const val ZOOM_STEP = 1.25f

/** Белая плавающая карточка поверх сцены: мягкая тень, рамка; жесты по ней до сцены не доходят. */
fun Modifier.floatingCard(shape: Shape, elevation: Dp = 3.dp): Modifier =
  shadow(elevation, shape, clip = false).background(KotinColors.Card, shape).border(1.dp, KotinColors.Outline, shape).blockSceneGestures()

/**
 * Плавающая карточка, которая появляется и уходит ([Appear]): масштаб от [origin], прозрачность тени и содержимого.
 * Содержимое гаснет отдельным слоем по форме карточки — буфер размером с неё, края скруглены с первого кадра; тень
 * снаружи этого слоя, её прозрачность — в самом рисовании, поэтому за краем буфера она не режется. ModulateAlpha на
 * общем слое не годится: на web (Skia) он не затемняет дочерние слои — карточка уменьшалась непрозрачной и пропадала.
 */
fun Modifier.appearingCard(shape: Shape, elevation: Dp, appear: () -> Float, origin: TransformOrigin): Modifier =
  graphicsLayer {
    val scale = APPEAR_SCALE + (1f - APPEAR_SCALE) * appear()
    scaleX = scale
    scaleY = scale
    transformOrigin = origin
  }.softShadow(shape, elevation, appear).graphicsLayer {
    alpha = appear()
    this.shape = shape
    clip = true
  }.background(KotinColors.Card).border(1.dp, KotinColors.Outline, shape).blockSceneGestures()

/**
 * Мягкая тень панели под [shape] с непрозрачностью [alpha] — своя, слоями скруглённых прямоугольников, а не тень платформы: у большой высоты
 * (меню 12 dp, шторки 16–24 dp) аналитическая тень Skia у края экрана теряла кусок под скруглённым углом, и из-под
 * карточки торчал прямоугольный угол. Тень чуть смещена вниз, как от света сверху.
 */
fun Modifier.softShadow(shape: Shape, elevation: Dp, alpha: () -> Float = { 1f }): Modifier = drawBehind {
  drawSoftShadow(shape, elevation, alpha())
}

/** Тень [softShadow] в текущем [DrawScope] — для тех, кому нужна не вся (шторке — только над верхним краем). */
fun DrawScope.drawSoftShadow(shape: Shape, elevation: Dp, a: Float = 1f) {
  if (a <= 0f) return
  val outline = shape.createOutline(size, layoutDirection, this)
  val rect = outline.bounds
  // у шторок скруглены не все углы — тени хватает наибольшего
  val radius = (outline as? Outline.Rounded)?.roundRect?.run {
    maxOf(topLeftCornerRadius.x, topRightCornerRadius.x, bottomLeftCornerRadius.x, bottomRightCornerRadius.x)
  } ?: 0f
  val spread = elevation.toPx()
  val dy = spread * SHADOW_OFFSET
  for (i in 1..SHADOW_STEPS) {
    val s = spread * i / SHADOW_STEPS
    drawRoundRect(
      KotinColors.Ink.copy(alpha = SHADOW_ALPHA / SHADOW_STEPS * a),
      topLeft = Offset(rect.left - s, rect.top - s + dy), size = Size(rect.width + 2 * s, rect.height + 2 * s),
      cornerRadius = CornerRadius(radius + s),
    )
  }
}

// суммарная непрозрачность тени у самой карточки, число слоёв и смещение вниз (доля высоты)
private const val SHADOW_ALPHA = 0.16f
private const val SHADOW_STEPS = 8
private const val SHADOW_OFFSET = 0.3f

/** Масштаб, с которого панель вырастает при появлении. */
private const val APPEAR_SCALE = 0.94f

/** Длительность ухода панели, мс. */
internal const val EXIT_MS = 150

/**
 * Показывает [content], пока [visible], и ещё на время ухода: [content] получает ход появления 0..1 — для
 * [appearingCard]. Видимая с самого начала — сразу 1, без анимации. Появление — [MOTION_MS] с торможением в конце,
 * уход короче: оба начинаются сразу, без медленного разгона.
 */
@Composable
fun Appear(visible: Boolean, modifier: Modifier = Modifier, content: @Composable (appear: () -> Float) -> Unit) {
  val progress = remember { Animatable(if (visible) 1f else 0f) }
  LaunchedEffect(visible) { progress.animateTo(if (visible) 1f else 0f, tween(if (visible) MOTION_MS else EXIT_MS, easing = LinearOutSlowInEasing)) }
  val shown by remember { derivedStateOf { progress.value > 0f } }
  if (visible || shown) Box(modifier) { content { progress.value } }
}

/**
 * Жесты и колесо над элементом не уходят в сцену под ним: Compose отдаёт событие верхнему элементу с обработчиком,
 * соседям ниже — нет. Сами события не поглощаются — кнопки и прокрутка внутри работают.
 */
fun Modifier.blockSceneGestures(): Modifier = pointerInput(Unit) {
  awaitPointerEventScope { while (true) awaitPointerEvent() }
}

/** Кнопка-значок [size] × [size]: без своего фона, нажатие подсвечивается в пределах [shape]. */
@Composable
fun IconBox(icon: ImageVector, description: String, size: Dp, onClick: () -> Unit, shape: Shape = RoundedCornerShape(10.dp), tint: Color = KotinColors.Ink, iconSize: Dp = 20.dp) {
  Box(
    Modifier.size(size).clip(shape).clickable(onClickLabel = description, role = Role.Button, onClick = onClick),
    contentAlignment = Alignment.Center,
  ) { Icon(icon, description, Modifier.size(iconSize), tint = tint) }
}

/**
 * Кнопки камеры справа снизу: схема управления «Орбита | Карта» (выбранная — тёмная плашка) и зум «− +» к центру кадра.
 * [buttonSize] — высота кнопок.
 */
@Composable
fun CameraButtons(mode: CameraMode, onMode: (CameraMode) -> Unit, onZoom: (Float) -> Unit, buttonSize: Dp, modifier: Modifier = Modifier) {
  val cardShape = RoundedCornerShape(14.dp)
  Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
    Row(Modifier.floatingCard(cardShape).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
      ModeSegment(stringResource(Res.string.camera_orbit), mode == CameraMode.ORBIT, buttonSize) { onMode(CameraMode.ORBIT) }
      ModeSegment(stringResource(Res.string.camera_map), mode == CameraMode.MAP, buttonSize) { onMode(CameraMode.MAP) }
    }
    Row(Modifier.floatingCard(cardShape).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
      IconBox(KotinIcons.Minus, stringResource(Res.string.zoom_out), buttonSize, { onZoom(ZOOM_STEP) })
      IconBox(KotinIcons.Plus, stringResource(Res.string.zoom_in), buttonSize, { onZoom(1 / ZOOM_STEP) })
    }
  }
}

@Composable
private fun ModeSegment(label: String, selected: Boolean, height: Dp, onClick: () -> Unit) {
  val shape = RoundedCornerShape(10.dp)
  Box(
    Modifier
      .height(height)
      .clip(shape)
      .background(if (selected) KotinColors.Ink else Color.Transparent)
      .clickable(enabled = !selected, role = Role.RadioButton, onClick = onClick)
      .padding(horizontal = 14.dp),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      label,
      color = if (selected) KotinColors.Card else KotinColors.Ink,
      style = MaterialTheme.typography.bodyMedium,
      fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
    )
  }
}
