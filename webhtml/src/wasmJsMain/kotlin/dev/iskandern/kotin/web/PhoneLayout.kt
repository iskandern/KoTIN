package dev.iskandern.kotin.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import dev.iskandern.kotin.resources.Res
import dev.iskandern.kotin.resources.close_menu
import dev.iskandern.kotin.web.dom.AttrsScope
import dev.iskandern.kotin.web.dom.Div
import dev.iskandern.kotin.web.dom.px
import kotlinx.browser.window
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import org.w3c.dom.pointerevents.PointerEvent
import kotlin.math.abs
import kotlin.math.max

/** Сдвиг шторки ниже свёрнутой, после которого она закрывается, px. */
private const val SHEET_DISMISS_DRAG = 64.0

/** Сдвиг пальца, после которого нажатие на шапку становится жестом шторки, px. */
private const val SHEET_SLOP = 8.0

/** Зона натяжения шторки выше предела: на её краю шторка упирается, px. */
private const val SHEET_STRETCH = 48.0

/** Доводка шторки после отпускания, мс: без отскока и с лёгким отскоком из зоны натяжения. */
private const val SETTLE_MS = 260L
private const val SETTLE_EASING = "cubic-bezier(0.2, 0, 0, 1)"
private const val BOUNCE_EASING = "cubic-bezier(0.34, 1.4, 0.64, 1)"

/** Доля секунды, на которую шторка по инерции продолжает движение пальца: куда бы она долетела. */
private const val FLING_SECONDS = 0.25

/**
 * Шторка снизу во всю ширину: ручка сверху и [content]. Своя, а не модальная: сцена над шторкой по-прежнему получает
 * жесты. Свёрнутая — не выше [collapsedHeight], развёрнутая ([expanded]) — не выше [expandedHeight]; [shown] — шторка
 * на экране (ход появления). Тянут за ручку и за то, на что [content] повесит жест (первый аргумент; у карточки — шапка):
 * высота идёт за пальцем, вверх — до развёрнутой, вниз — до свёрнутой, дальше шторка уезжает вниз. Отпустили —
 * доводится к ближайшему положению с учётом скорости пальца ([onExpand]); ниже свёрнутой и взмахом вниз —
 * закрывается ([onClose]). Выше предела — натяжение с упором через [SHEET_STRETCH], отпустили — возврат с отскоком.
 * Второй аргумент [content] — высоту сейчас ведёт жест.
 */
@Composable
fun BottomSheet(
  shown: Boolean, onClose: () -> Unit, expanded: Boolean, onExpand: (Boolean) -> Unit, collapsedHeight: Int, expandedHeight: Int,
  content: @Composable (drag: AttrsScope.() -> Unit, dragging: Boolean) -> Unit,
) {
  val sheet = remember { SheetDrag() }
  val close by rememberUpdatedState(onClose)
  val expand by rememberUpdatedState(onExpand)
  val collapsedPx = collapsedHeight.toDouble()
  val expandedPx = max(expandedHeight, collapsedHeight).toDouble()
  // жест: высота верха шторки над низом, px; null — в покое
  var position by remember { mutableStateOf<Double?>(null) }
  // доводка после отпускания: к какой высоте и с каким переходом
  var settle by remember { mutableStateOf<Settle?>(null) }
  LaunchedEffect(shown) { if (shown) { position = null; settle = null } }

  fun naturalHeight(): Double {
    val element = sheet.element ?: return 0.0
    val scroll = element.querySelector(".attr-scroll") as? HTMLElement
    val grid = scroll?.firstElementChild as? HTMLElement
    val hidden = if (scroll != null && grid != null) grid.offsetHeight - scroll.clientHeight else 0
    // по детям, а не scrollHeight: в него входит и подложка ::before под шторкой
    var children = 0
    var child = element.firstElementChild as? HTMLElement
    while (child != null) {
      children += child.offsetHeight
      child = child.nextElementSibling as? HTMLElement
    }
    return (children + hidden).toDouble()
  }

  val drag: AttrsScope.() -> Unit = {
    style { property("touch-action", "none") }
    onPointerDown { e -> pressDrag(e, sheet) }
  }
  // движение и отпускание — на окне: до порога шторка указатель не захватывает (иначе click уходит с кнопок шапки),
  // и мышь, быстро ушедшая с ручки, присылала бы события уже не ей — жест не начинался, а нажатие оставалось висеть
  val onEnd = rememberUpdatedState { e: PointerEvent ->
    endDrag(e, sheet, position) { target, bounce, dismiss ->
      settle = Settle(target, bounce, dismiss)
      position = target
    }
  }
  val onMove = rememberUpdatedState { e: PointerEvent ->
    // кнопку мыши отпустили вне окна: жест кончился, а не идёт за курсором
    if (e.pointerType == "mouse" && (e.buttons.toInt() and 1) == 0) onEnd.value(e)
    else if (startDrag(e, sheet, expanded, collapsedPx, expandedPx, ::naturalHeight) { position = it; settle = null }) {
      moveDrag(e, sheet) { position = it }
    }
  }
  DisposableEffect(Unit) {
    val move: (Event) -> Unit = { onMove.value(it as PointerEvent) }
    val end: (Event) -> Unit = { onEnd.value(it as PointerEvent) }
    window.addEventListener("pointermove", move)
    window.addEventListener("pointerup", end)
    window.addEventListener("pointercancel", end)
    onDispose {
      window.removeEventListener("pointermove", move)
      window.removeEventListener("pointerup", end)
      window.removeEventListener("pointercancel", end)
    }
  }
  // доводка закончилась: шторка в покое на новой высоте или закрыта
  LaunchedEffect(settle) {
    val s = settle ?: return@LaunchedEffect
    delay(SETTLE_MS)
    if (s.dismiss) close() else {
      expand(s.target > sheet.rest + 1.0)
      position = null
      settle = null
    }
  }

  val p = position
  Div({
    classes("sheet", "soft-16")
    classIf(shown, "in")
    style {
      if (p == null) {
        property("max-height", (if (expanded) expandedPx else collapsedPx).px)
        property("transform", if (shown) "none" else "translateY(calc(100% + 32px))")
      } else {
        // высота — между свёрнутой и пределом; ниже свёрнутой — сдвиг вниз, выше предела — натяжение вверх
        val height = p.coerceIn(sheet.rest, sheet.top)
        val shift = when {
          p < sheet.rest -> sheet.rest - p
          p > sheet.top -> -(p - sheet.top).coerceAtMost(SHEET_STRETCH)
          else -> 0.0
        }
        property("height", height.px)
        property("max-height", "none")
        property("transform", "translateY(${shift}px)")
        val s = settle
        property(
          "transition",
          if (s == null) "none" else "height ${SETTLE_MS}ms ${if (s.bounce) BOUNCE_EASING else SETTLE_EASING}, transform ${SETTLE_MS}ms ${if (s.bounce) BOUNCE_EASING else SETTLE_EASING}",
        )
      }
    }
  }, ref = { sheet.element = it as HTMLElement; ({ sheet.element = null }) }) {
    Div({ classes("handle-area"); drag() }) { Div({ classes("handle") }) }
    content(drag, p != null)
  }
}

private class Settle(val target: Double, val bounce: Boolean, val dismiss: Boolean)

/** Жест шторки: элемент, предел натяжения, высота свёрнутой, начало жеста, след пальца для скорости. */
private class SheetDrag {
  var element: HTMLElement? = null
  var pointer = -1
  // палец нажал, но ещё не сдвинулся на SHEET_SLOP: нажатие может оказаться кнопкой в шапке
  var pending = false
  var downY = 0.0
  var lastY = 0.0
  var top = 0.0
  var rest = 0.0
  var startHeight = 0.0
  var startExpanded = false
  val samples = ArrayList<Pair<Double, Double>>() // время, мс; высота
}

private fun pressDrag(e: PointerEvent, sheet: SheetDrag) {
  // идёт жест — второй палец его не перехватывает; оставшееся от прошлого нажатия без pointerup — забывается
  if (sheet.pointer >= 0 && !sheet.pending) return
  // мышью текст шапки выделяют, а не тянут шторку: её тянут за ручку и за пустое место шапки
  if (e.pointerType == "mouse" && ((e.target as? Element)?.closest(".selectable") != null || e.button.toInt() != 0)) {
    sheet.pointer = -1
    sheet.pending = false
    return
  }
  sheet.pointer = e.pointerId
  sheet.pending = true
  sheet.downY = e.clientY.toDouble()
}

/** Жест начинается, когда палец ушёл от нажатия на [SHEET_SLOP]: короче — это нажатие на кнопку шапки. Возвращает, идёт ли жест. */
private fun startDrag(
  e: PointerEvent, sheet: SheetDrag, expanded: Boolean, collapsedPx: Double, expandedPx: Double, natural: () -> Double, onPosition: (Double) -> Unit,
): Boolean {
  if (e.pointerId != sheet.pointer) return false
  if (!sheet.pending) return true
  val element = sheet.element ?: return false
  if (abs(e.clientY - sheet.downY) < SHEET_SLOP) return false
  sheet.pending = false
  val height = element.offsetHeight.toDouble()
  sheet.startHeight = height
  sheet.startExpanded = expanded
  sheet.rest = minOf(collapsedPx, height)
  // предел натяжения: высота содержимого, если оно влезает в развёрнутую, иначе развёрнутая
  sheet.top = maxOf(minOf(natural(), expandedPx), sheet.rest)
  sheet.lastY = e.clientY.toDouble()
  sheet.samples.clear()
  sheet.samples += e.timeStamp.toDouble() to height
  onPosition(height)
  return true
}

private fun moveDrag(e: PointerEvent, sheet: SheetDrag, onPosition: (Double) -> Unit) {
  if (e.pointerId != sheet.pointer) return
  val dy = e.clientY - sheet.lastY
  sheet.lastY = e.clientY.toDouble()
  val from = sheet.samples.lastOrNull()?.second ?: return
  val over = from - sheet.top
  // выше предела — натяжение: чем дальше, тем меньше шторка идёт за пальцем
  val to = (from - if (dy < 0 && over > 0) dy * max(0.0, 1.0 - over / SHEET_STRETCH) else dy).coerceIn(0.0, sheet.top + SHEET_STRETCH)
  sheet.samples += e.timeStamp.toDouble() to to
  if (sheet.samples.size > 20) sheet.samples.removeAt(0)
  onPosition(to)
}

private fun endDrag(e: PointerEvent, sheet: SheetDrag, position: Double?, onSettle: (target: Double, bounce: Boolean, dismiss: Boolean) -> Unit) {
  if (e.pointerId != sheet.pointer) return
  sheet.pointer = -1
  // не сдвинулся — это было нажатие, его обработает click
  if (sheet.pending) {
    sheet.pending = false
    return
  }
  val from = position ?: return
  // скорость шторки по последним ~100 мс, px/с: вверх — плюс
  val last = sheet.samples.last()
  val first = sheet.samples.firstOrNull { last.first - it.first <= 100.0 } ?: last
  val dt = (last.first - first.first) / 1000.0
  val speed = if (dt > 0.01) (last.second - first.second) / dt else 0.0
  val aim = from + speed * FLING_SECONDS
  val rest = sheet.rest
  val top = if (sheet.startExpanded) minOf(sheet.top, sheet.startHeight) else sheet.top
  if (from <= rest && aim < rest - SHEET_DISMISS_DRAG) {
    // уезжает вниз вместе с тенью и закрывается
    onSettle(rest - sheet.startHeight - 64.0, false, true)
    return
  }
  val back = from > top
  val up = top > rest + 1.0 && (back || aim > (rest + top) / 2)
  onSettle(if (back || up) top else rest, back, false)
}

/** Боковое меню телефона: выезжает слева поверх затемнённой сцены; нажатие на затемнение закрывает. */
@Composable
fun MenuDrawer(open: Boolean, width: Int, onClose: () -> Unit, content: @Composable () -> Unit) {
  val closeLabel = stringResource(Res.string.close_menu)
  Appear(open, MOTION_MS) { shown ->
    Div({
      classes("drawer-scrim", "fade")
      classIf(shown, "in")
      attr("aria-label", closeLabel)
      onClick { onClose() }
    })
  }
  Appear(open, MOTION_MS) { shown ->
    Div({
      classes("drawer")
      classIf(shown, "in")
      style { property("width", width.px) }
    }) { content() }
  }
}
