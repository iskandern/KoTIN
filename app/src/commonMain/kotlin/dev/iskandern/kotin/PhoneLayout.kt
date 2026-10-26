package dev.iskandern.kotin

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.calculateTargetValue
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.splineBasedDecay
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import dev.iskandern.kotin.resources.Res
import dev.iskandern.kotin.resources.camera_map_hint
import dev.iskandern.kotin.resources.camera_orbit_hint
import dev.iskandern.kotin.resources.close_menu
import dev.iskandern.kotin.resources.zoom_in
import dev.iskandern.kotin.resources.zoom_out

/** Сдвиг шторки ниже свёрнутой, после которого она закрывается. */
private val SHEET_DISMISS_DRAG = 64.dp

/**
 * Кнопки камеры телефона — колонка у правого края: «+», «−» и схема управления значком (нажатие — другая схема).
 * Внизу место подсказки и шторки выбранного, поэтому колонка посередине края.
 */
@Composable
fun CameraRail(mode: CameraMode, onMode: (CameraMode) -> Unit, onZoom: (Float) -> Unit, buttonSize: Dp, modifier: Modifier = Modifier) {
  Column(modifier.uiTag("camera-rail"), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
    // колонка обрезана по форме карточки: подсветка нажатия «+» и «−» скруглена вместе с ней, а не квадратная
    val pill = RoundedCornerShape(24.dp)
    Column(Modifier.uiTag("rail-zoom").floatingCard(pill).clip(pill)) {
      IconBox(KotinIcons.Plus, stringResource(Res.string.zoom_in), buttonSize, { onZoom(1 / ZOOM_STEP) }, RectangleShape, iconSize = 22.dp)
      Box(Modifier.width(buttonSize).height(1.dp).background(KotinColors.Scene))
      IconBox(KotinIcons.Minus, stringResource(Res.string.zoom_out), buttonSize, { onZoom(ZOOM_STEP) }, RectangleShape, iconSize = 22.dp)
    }
    val orbit = mode == CameraMode.ORBIT
    Box(Modifier.uiTag("rail-mode").floatingCard(CircleShape)) {
      IconBox(
        if (orbit) KotinIcons.Orbit else KotinIcons.Map,
        stringResource(if (orbit) Res.string.camera_orbit_hint else Res.string.camera_map_hint),
        buttonSize,
        { onMode(if (orbit) CameraMode.MAP else CameraMode.ORBIT) },
        CircleShape,
        iconSize = 24.dp,
      )
    }
  }
}

/**
 * Шторка снизу во всю ширину: ручка сверху и [content]. Своя, а не модальная: сцена над шторкой по-прежнему получает
 * жесты. Свёрнутая — не выше [collapsedHeight], развёрнутая ([expanded]) — не выше [expandedHeight]; [open] — шторка
 * показана (после закрытия жестом новое открытие начинается с покоя).
 * Тянут за ручку и за то, на что [content] повесит жест (первый аргумент; у карточки — шапка): высота идёт за пальцем,
 * вверх — до развёрнутой, вниз — до свёрнутой, дальше шторка уезжает вниз. Отпустили — продолжает движение со скоростью
 * пальца и пружиной доезжает до положения, ближайшего к точке, куда долетела бы по инерции ([onExpand]). Отпустили ниже
 * свёрнутой и эта точка ниже неё больше чем на [SHEET_DISMISS_DRAG] — уезжает вниз и закрывается ([onClose]).
 * Выше предела (развёрнутая или всё содержимое уже видно) — натяжение: шторка целиком идёт вверх туже пальца и упирается
 * через [SHEET_STRETCH]; отпустили — возвращается к пределу с лёгким отскоком.
 * Второй аргумент [content] — высоту сейчас ведёт жест: своя анимация высоты у содержимого не нужна, она бы отставала.
 */
@Composable
fun BottomSheet(
  open: Boolean, onClose: () -> Unit, expanded: Boolean, onExpand: (Boolean) -> Unit, collapsedHeight: Dp, expandedHeight: Dp,
  modifier: Modifier = Modifier, content: @Composable (drag: Modifier, dragging: Boolean) -> Unit,
) {
  val shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
  val density = LocalDensity.current
  val collapsedPx = with(density) { collapsedHeight.toPx() }
  val expandedPx = with(density) { maxOf(expandedHeight, collapsedHeight).toPx() }
  val dismissPx = with(density) { SHEET_DISMISS_DRAG.toPx() }
  val hiddenPx = with(density) { (SHEET_ELEVATION * 2).toPx() }
  val stretchPx = with(density) { SHEET_STRETCH.toPx() }
  val decay = remember(density) { splineBasedDecay<Float>(density) }
  val scope = rememberCoroutineScope()
  val sheet = remember { SheetDrag() }
  // жест и доводка: высота верха шторки над низом, px (ниже свёрнутой — шторка сдвинута вниз, выше предела — вверх);
  // null — в покое
  var position by remember { mutableStateOf<Float?>(null) }
  LaunchedEffect(open) { if (open) position = null }
  // композиция читает только «ведут или нет»: высота по кадрам идёт в раскладку и шторку не пересобирает
  val dragging by remember { derivedStateOf { position != null } }
  val close by rememberUpdatedState(onClose)
  val expand by rememberUpdatedState(onExpand)
  val drag = Modifier.pointerInput(expanded, collapsedPx, expandedPx) {
    detectVerticalDragGestures(
      onDragStart = {
        sheet.settle?.cancel()
        sheet.velocity.resetTracking()
        sheet.startExpanded = expanded
        sheet.startHeight = sheet.height
        sheet.rest = minOf(collapsedPx, sheet.height.toFloat())
        if (position == null) position = sheet.height.toFloat()
      },
      onDragEnd = {
        val from = position ?: return@detectVerticalDragGestures
        val rest = sheet.rest
        // предел натяжения; повели вниз от развёрнутой — содержимое уже не целиком и предел снова «развёрнутая»,
        // а настоящий — высота при начале жеста
        val top = (if (sheet.startExpanded) minOf(sheet.top, sheet.startHeight) else sheet.top).toFloat()
        // скорость шторки, px/с: вверх — плюс; куда она с ней долетела бы по инерции
        val speed = sheet.velocity.calculateVelocity().y
        val aim = decay.calculateTargetValue(from, speed)
        // закрывает только шторку, опущенную ниже свёрнутой: взмах вниз выше неё — сворачивает
        if (from <= rest && aim < rest - dismissPx) {
          // уезжает вниз со скоростью пальца вместе с тенью и закрывается, когда скрылась
          val hidden = rest - sheet.height - hiddenPx
          sheet.settle = scope.launch {
            settle(from, hidden - dismissPx, speed, bound = hidden) { position = it }
            close()
          }
          return@detectVerticalDragGestures
        }
        // развёрнутая — предел натяжения. Отпустили в зоне натяжения — возврат к пределу с отскоком, куда бы ни вёл
        // взмах; скорость вниз там не берём: у края зоны шторка стоит, а подгонка по последним точкам даёт ложный рывок вниз
        val back = from > top
        val up = top > rest + 1f && (back || aim > (rest + top) / 2)
        val to = if (back || up) top else rest
        sheet.settle = scope.launch {
          if (back) settle(from, to, speed.coerceAtLeast(0f), SHEET_BOUNCE, bound = null) { position = it }
          // высота содержимого может выясниться только по ходу: дошла до неё — упор
          else settle(from, to, speed, ceiling = if (up) ({ sheet.top.toFloat() }) else null) { position = it }
          expand(up)
          position = null
        }
      },
      onDragCancel = {
        val from = position ?: return@detectVerticalDragGestures
        sheet.settle = scope.launch {
          settle(from, if (sheet.startExpanded) sheet.startHeight.toFloat() else sheet.rest) { position = it }
          position = null
        }
      },
    ) { change, dy ->
      change.consume()
      // выше предела — натяжение: чем дальше, тем меньше шторка идёт за пальцем, на краю зоны упирается
      val top = sheet.top.toFloat()
      // натяжение — от того, что видно: предел, выяснившийся по ходу (высота содержимого), шторку не дёргает
      val from = minOf(position ?: sheet.height.toFloat(), top + sheet.over)
      val over = from - top
      val to = (from - if (dy < 0f && over > 0f) dy * max(0f, 1f - over / stretchPx) else dy).coerceIn(0f, top + stretchPx)
      position = to
      // скорость — по пути шторки (ручка и шапка едут вместе с пальцем, их координаты стоят); в зоне натяжения она меньше
      // скорости пальца
      sheet.velocity.addPosition(change.uptimeMillis, Offset(0f, to))
    }
  }
  Column(
    modifier
      .uiTag("sheet")
      .fillMaxWidth()
      // высота — по жесту или по состоянию; ниже свёрнутой — сдвиг вниз, выше предела — вверх (высота та же: содержимое
      // не перемеряется). Читается при раскладке: жест не пересобирает шторку
      .layout { measurable, constraints ->
        val p = position
        val full = expandedPx.roundToInt().coerceIn(constraints.minHeight, constraints.maxHeight)
        val limit = (if (p != null) minOf(maxOf(p, sheet.rest), sheet.top.toFloat()) else if (expanded) expandedPx else collapsedPx)
          .roundToInt().coerceIn(constraints.minHeight, constraints.maxHeight)
        val placeable = measurable.measure(constraints.copy(maxHeight = limit))
        sheet.height = placeable.height
        // предел натяжения: высота содержимого, если оно видно целиком, иначе развёрнутая; содержимое во весь прежний
        // предел — он и остаётся
        val known = sheet.top
        sheet.top = when {
          placeable.height < limit -> placeable.height
          limit == known -> known
          else -> full
        }
        // натяжение — только за пределом, известным до этого замера: выше только что выяснившегося шторку не поднимаем
        sheet.over = if (p != null && p > known) (p - sheet.top).coerceIn(0f, stretchPx) else 0f
        val shift = when {
          p == null -> 0
          p < sheet.rest -> (sheet.rest - p).roundToInt()
          else -> -sheet.over.roundToInt()
        }
        layout(placeable.width, placeable.height) { placeable.place(0, shift) }
      }
      // тень видна только над верхним краем (по бокам — край экрана, снизу — сама шторка): остальное не рисуем.
      // Фон продлён вниз за край экрана на зону натяжения: поднятая натяжением шторка снизу без щели
      .drawBehind {
        val corner = 20.dp.toPx()
        val spread = SHEET_ELEVATION.toPx() * 2
        clipRect(-spread, -spread, size.width + spread, corner) { drawSoftShadow(shape, SHEET_ELEVATION) }
        drawRect(KotinColors.Card, Offset(0f, size.height - corner), Size(size.width, corner + stretchPx))
      }
      .background(KotinColors.Card, shape)
      .blockSceneGestures()
      .windowInsetsPadding(WindowInsets.navigationBars),
  ) {
    Box(Modifier.uiTag("sheet-handle-area").fillMaxWidth().height(24.dp).then(drag), contentAlignment = Alignment.Center) {
      Box(Modifier.uiTag("sheet-handle").size(36.dp, 4.dp).background(KotinColors.Handle, RoundedCornerShape(2.dp)))
    }
    content(drag, dragging)
  }
}

/**
 * Доводка шторки от [from] к [to] пружиной [spec] с начальной скоростью [velocity] (px/с): значения по кадрам
 * в [onValue]. За [bound] (по умолчанию — сама цель; null — без границы) со стороны подхода не перелетает: дошла —
 * доводка кончилась. [ceiling] — верхняя граница, которая может уменьшиться по ходу (предел по высоте содержимого).
 * Первый кадр — уже на кадр вперёд: animateTo начинает с исходного значения, и после отпускания шторка кадр стояла бы.
 * Пружина без памяти, поэтому её же движение с кадра вперёд — то же самое движение.
 */
private suspend fun settle(
  from: Float, to: Float, velocity: Float = 0f, spec: SpringSpec<Float> = SHEET_SPRING, bound: Float? = to,
  ceiling: (() -> Float)? = null, onValue: (Float) -> Unit,
) {
  val ahead = TargetBasedAnimation(spec, Float.VectorConverter, from, to, velocity)
  val above = bound != null && bound >= from
  val start = ahead.getValueFromNanos(FRAME_NANOS).let {
    when {
      bound == null -> it
      above -> it.coerceAtMost(bound)
      else -> it.coerceAtLeast(bound)
    }
  }
  val sheet = Animatable(start)
  if (bound != null) {
    if (above) sheet.updateBounds(upperBound = bound) else sheet.updateBounds(lowerBound = bound)
  }
  sheet.animateTo(to, spec, ahead.getVelocityVectorFromNanos(FRAME_NANOS).value) {
    val top = ceiling?.invoke()
    if (top != null && value >= top) {
      onValue(top)
      updateBounds(upperBound = top)
    } else {
      onValue(value)
    }
  }
}

/** Кадр 60 Гц, нс: на столько вперёд начинается доводка шторки. */
private const val FRAME_NANOS = 16_666_667L

/** Пружина доводки шторки: без отскока; длительность — по расстоянию и скорости пальца. */
private val SHEET_SPRING = spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow, visibilityThreshold = 0.5f)

/** Пружина возврата из зоны натяжения: с лёгким отскоком. */
private val SHEET_BOUNCE = spring(Spring.DampingRatioLowBouncy, Spring.StiffnessMediumLow, visibilityThreshold = 0.5f)

/** Зона натяжения шторки выше предела: на её краю шторка упирается. */
private val SHEET_STRETCH = 48.dp

/**
 * Жест шторки: замер высоты (px), предел натяжения и видимое натяжение (px), состояние при начале жеста, высота
 * свёрнутой и доводка после отпускания.
 */
private class SheetDrag {
  var height = 0
  var top = 0
  var over = 0f
  var startHeight = 0
  var startExpanded = false
  var rest = 0f
  var settle: Job? = null
  val velocity = VelocityTracker()
}

/** Высота тени шторки. */
private val SHEET_ELEVATION = 16.dp

/** Боковое меню телефона: выезжает слева поверх затемнённой сцены; нажатие на затемнение закрывает. */
@Composable
fun MenuDrawer(open: Boolean, width: Dp, onClose: () -> Unit, content: @Composable () -> Unit) {
  AnimatedVisibility(open, enter = fadeIn(tween(MOTION_MS)), exit = fadeOut(tween(MOTION_MS))) {
    Box(
      Modifier.uiTag("drawer-scrim").fillMaxSize().background(KotinColors.Scrim)
        .clickable(remember { MutableInteractionSource() }, indication = null, onClickLabel = stringResource(Res.string.close_menu), onClick = onClose),
    )
  }
  AnimatedVisibility(open, enter = slideInHorizontally(tween(MOTION_MS)) { -it }, exit = slideOutHorizontally(tween(MOTION_MS)) { -it }) {
    val shape = RoundedCornerShape(topEnd = 20.dp, bottomEnd = 20.dp)
    Box(
      Modifier.uiTag("drawer").width(width).fillMaxHeight()
        .softShadow(shape, 24.dp)
        .background(KotinColors.Card, shape)
        .blockSceneGestures()
        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Start + WindowInsetsSides.Vertical)),
    ) { content() }
  }
}
