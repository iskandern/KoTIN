package dev.iskandern.kotin.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import dev.iskandern.kotin.ObjectSelection
import dev.iskandern.kotin.attributeName
import dev.iskandern.kotin.localized
import dev.iskandern.kotin.model.SceneAttribute
import dev.iskandern.kotin.model.SceneObject
import dev.iskandern.kotin.resources.Res
import dev.iskandern.kotin.resources.clear_selection
import dev.iskandern.kotin.resources.hide_hint
import dev.iskandern.kotin.resources.selection_collapse
import dev.iskandern.kotin.resources.selection_expand
import dev.iskandern.kotin.resources.selection_layer
import dev.iskandern.kotin.resources.selection_list
import dev.iskandern.kotin.resources.selection_nearby
import dev.iskandern.kotin.resources.selection_next
import dev.iskandern.kotin.resources.selection_partial
import dev.iskandern.kotin.resources.selection_prev
import dev.iskandern.kotin.web.dom.AttrsScope
import dev.iskandern.kotin.web.dom.Div
import dev.iskandern.kotin.web.dom.Span
import dev.iskandern.kotin.web.dom.Text
import dev.iskandern.kotin.web.dom.px
import org.jetbrains.compose.resources.stringResource
import org.w3c.dom.HTMLElement

/** Кнопки в шапке карточки: «развернуть» и крестик. */
private const val HEADER_BUTTON = 36

/** Доля ширины, на которую сдвигается содержимое карточки при смене кандидата, %. */
private const val SLIDE_PERCENT = 10

/** Строка списка кандидатов и сколько их видно без прокрутки. */
private const val LIST_ROW = 32
private const val LIST_ROWS = 5.5f

/**
 * Карточка выбранного объекта: цветной квадрат, «Вид · Имя», ссылка «Слой: …» ([onShowLayer] — меню на слое),
 * свойства сеткой в две колонки; крестик снимает выбор. Под нажатием было несколько объектов — внизу переключатель
 * «‹ Рядом ещё объекты · 1 / 3 ›» ([onPick] — к кандидату по номеру); нажатие на середину раскрывает над ним список.
 * Высоту ограничивает [maxHeight] (px, null — без ограничения); свойства прокручиваются, шапка и переключатель видны
 * всегда. Не влезают — в шапке кнопка «развернуть / свернуть» ([expanded], [onExpand]); [onExpand] null — кнопки нет
 * (шторку телефона тянут за шапку: [header] — жест перетаскивания). [animateSize] — плавная смена высоты. [attrs] —
 * атрибуты самой карточки (на десктопе — рамка, ширина, появление).
 */
@Composable
fun SelectionCard(
  selection: ObjectSelection, swatch: (SceneObject) -> String?, swapXY: Boolean, onClose: () -> Unit, onShowLayer: () -> Unit,
  onPick: (Int) -> Unit = {}, expanded: Boolean = false, onExpand: ((Boolean) -> Unit)? = null,
  header: (AttrsScope.() -> Unit)? = null, animateSize: Boolean = true, buttonSize: Int = 40, maxHeight: Int? = null,
  attrs: (AttrsScope.() -> Unit)? = null,
) {
  val count = selection.candidates.size
  // куда листали стрелкой (кандидат и направление): «›» — новое приходит справа, в том числе с последнего на первый
  var arrow by remember(selection.candidates) { mutableStateOf<Pair<Int, Int>?>(null) }
  // смена объекта: шапка и свойства нового въезжают на SLIDE_PERCENT ширины (шапка ещё и проявляется). Первый показ — без сдвига
  val last = remember(selection.candidates) { IntArray(1) { selection.index } }
  val step = if (selection.index == last[0]) 0 else arrow?.takeIf { it.first == selection.index }?.second ?: if (selection.index > last[0]) 1 else -1
  SideEffect { last[0] = selection.index }
  val from = remember(selection.objectId) { step }
  // прокрутка свойств и её переполнение — свои у каждого объекта
  val parts = remember { arrayOfNulls<HTMLElement>(4) } // карточка, тело, прокрутка свойств, сетка
  val overflowState = remember(selection.candidates) { mutableStateOf(false) }
  val overflow by overflowState
  // высота карточки — по содержимому (не выше maxHeight), меняется плавно: низ на месте, растёт вверх
  var height by remember { mutableStateOf<Double?>(null) }
  // measure зовёт и наблюдатель, поставленный один раз в первой композиции, — с тем, что она захватила тогда.
  // Поэтому нынешние maxHeight и overflow она берёт из запомненных состояний: иначе раскрытая карточка
  // тут же сжималась обратно до свёрнутого maxHeight
  val limitNow = rememberUpdatedState(maxHeight)
  val overflowNow = rememberUpdatedState(overflowState)
  fun measure() {
    val card = parts[0] ?: return
    val body = parts[1] ?: return
    val scroll = parts[2]
    val grid = parts[3]
    // естественная высота: тело без ограничения свойств — его высота плюс то, что свойства прячут под прокруткой
    val hidden = if (scroll != null && grid != null) (grid.offsetHeight - scroll.clientHeight).coerceAtLeast(0) else 0
    val border = card.offsetHeight - card.clientHeight
    val natural = (body.scrollHeight + hidden + border).toDouble()
    val limit = limitNow.value?.toDouble()
    height = if (limit != null) minOf(natural, limit) else natural
    overflowNow.value.value = scroll != null && scroll.scrollHeight > scroll.clientHeight + 0.5
  }
  Div({
    classes("selection-card")
    style {
      if (maxHeight != null) property("max-height", maxHeight.px)
      height?.let { property("height", "${it}px") }
      // left — сдвиг вслед за меню (на десктопе карточка стоит справа от открытого меню), как animateDpAsState в app
      if (animateSize) property("transition", "height 200ms var(--los), left 200ms var(--fos), opacity var(--appear-ms, 0ms) var(--los), transform var(--appear-ms, 0ms) var(--los)")
    }
    attrs?.invoke(this)
  }, ref = { element ->
    parts[0] = element as HTMLElement
    val observer = observeTree(element) { measure() }
    ({ disconnect(observer) })
  }) {
    Div({ classes("selection-body") }, ref = { parts[1] = it as HTMLElement; {} }) {
      Div({ classes("selection-head"); header?.invoke(this) }) {
        Div({ classes("selection-title-box") }) {
          // новый объект — новые элементы: въезд CSS-анимацией идёт с первого кадра
          key(selection.objectId) { SelectionTitle(selection, swatch(selection.objectInfo), onShowLayer, from) }
        }
        if (onExpand != null && (expanded || overflow)) {
          CardIconButton(
            if (expanded) Icon.ChevronDown else Icon.ChevronUp,
            stringResource(if (expanded) Res.string.selection_collapse else Res.string.selection_expand),
            { onExpand(!expanded) }, HEADER_BUTTON, "subtle",
          )
        }
        CardIconButton(Icon.Close, stringResource(Res.string.clear_selection), onClose, HEADER_BUTTON, "subtle")
      }
      // свойства забирают остаток высоты: упёрлись в лимит — прокручиваются, переключатель под ними виден
      val attributes = selection.objectInfo.attributes
      if (attributes.isNotEmpty()) {
        Div({ classes("attr-scroll") }, ref = { parts[2] = it as HTMLElement; ({ parts[2] = null }) }) {
          key(selection.objectId) {
            Div({ classes("attr-grid"); slide(from, fade = false) }, ref = { parts[3] = it as HTMLElement; ({ parts[3] = null }) }) {
              for (row in 0 until (attributes.size + 1) / 2) AttributeRow(attributes[2 * row], attributes.getOrNull(2 * row + 1), swapXY)
            }
          }
        }
      }
      if (count > 1) {
        CandidateSwitch(selection, buttonSize, onPick) { step ->
          val target = (selection.index + step + count) % count
          arrow = target to step
          onPick(target)
        }
      }
    }
  }
  SideEffect { measure() }
}

@Composable
private fun key(value: Any, content: @Composable () -> Unit) = androidx.compose.runtime.key(value) { content() }

/**
 * Въезд содержимого при смене объекта: [from] — откуда (1 — справа, −1 — слева, 0 — без сдвига), [fade] — ещё и
 * проявление. Анимация CSS, а не переход по состоянию «въехало»: тому нужны два кадра композиции, и всё это время
 * шапка была пустой, а свойства стояли сдвинутыми
 */
private fun AttrsScope.slide(from: Int, fade: Boolean) {
  if (from == 0) return
  val name = if (from > 0) "slide-from-right" else "slide-from-left"
  style { property("animation", "$name 200ms var(--los)" + if (fade) ", fade-in 200ms var(--los)" else "") }
}

/** Строка сетки свойств: две ячейки пополам ширины, подложки во всю высоту строки (по более высокой ячейке). */
@Composable
private fun AttributeRow(left: SceneAttribute, right: SceneAttribute?, swapXY: Boolean) {
  Div({ classes("attr-row") }) {
    AttributeCell(left, swapXY)
    if (right != null) AttributeCell(right, swapXY) else Div({ classes("attr-cell", "empty") })
  }
}

/** Текст ячейки: название мелко и серым, под ним значение моноширинным. */
@Composable
private fun AttributeCell(attribute: SceneAttribute, swapXY: Boolean) {
  Div({ classes("attr-cell", "selectable") }) {
    Span({ classes("label") }) { Text(attributeName(attribute.name, swapXY)) }
    Span({ classes("value") }) { Text(localized(attribute.value)) }
  }
}

/** Шапка карточки: цветной квадрат, «Вид · Имя», ссылка на слой, пометка о частичной подсветке. */
@Composable
private fun SelectionTitle(selection: ObjectSelection, swatch: String?, onShowLayer: () -> Unit, from: Int) {
  val info = selection.objectInfo
  Div({ classes("selection-title"); slide(from, fade = true) }) {
    if (info.color != SceneObject.NO_COLOR) {
      Div({ classes("swatch"); style { property("background", swatch ?: cssColor(info.color)) } })
    }
    Div({ classes("texts") }) {
      Div({ classes("name", "selectable") }) { Text(info.kind + if (info.name.isNotEmpty()) " · ${localized(info.name)}" else "") }
      if (info.layer.isNotEmpty()) {
        Div {
          Span({
            classes("layer", "ripple")
            attr("role", "button")
            onClick { onShowLayer() }
          }) { Text(stringResource(Res.string.selection_layer, localized(info.layer))) }
        }
      }
      if (selection.geometry.truncated) {
        Div({ classes("partial") }) { Text(stringResource(Res.string.selection_partial)) }
      }
    }
  }
}

/**
 * Переключатель кандидатов: стрелки по кругу ([onStep] — на шаг ±1), посередине «Рядом ещё объекты · 1 / 3»;
 * нажатие на середину раскрывает над строкой список «номер — вид · имя» (выбранный — на жёлтой подложке),
 * строка списка выбирает кандидата ([onPick]). Список не выше [LIST_ROWS] строк, длиннее — прокручивается.
 */
@Composable
private fun CandidateSwitch(selection: ObjectSelection, height: Int, onPick: (Int) -> Unit, onStep: (Int) -> Unit) {
  val count = selection.candidates.size
  var expanded by remember(selection.candidates) { mutableStateOf(false) }
  Div({ classes("candidate-switch") }) {
    Appear(expanded) { shown ->
      Div({ classes("candidate-list-wrap"); classIf(shown, "in") }) {
        Div {
          Div({ classes("candidate-list") }, ref = { element ->
            // открытый список — с выбранным в поле зрения
            (element as HTMLElement).scrollTop = (LIST_ROW * (selection.index - LIST_ROWS.toInt() + 1).coerceAtLeast(0)).toDouble()
            ({})
          }) {
            selection.candidates.forEachIndexed { i, candidate ->
              val on = i == selection.index
              Div({
                classes("candidate-item", "ripple")
                classIf(on, "on")
                attr("role", "button")
                onClick { onPick(i) }
              }) {
                CandidateNumber(i + 1, on)
                Span({ classes("text", "ellipsis") }) {
                  Text(candidate.kind + if (candidate.name.isNotEmpty()) " · ${localized(candidate.name)}" else "")
                }
              }
            }
          }
        }
      }
    }
    Div({ classes("switch-row"); style { property("height", height.px) } }) {
      CardIconButton(Icon.ChevronLeft, stringResource(Res.string.selection_prev), { onStep(-1) }, height, "card")
      CardButton(stringResource(Res.string.selection_list), { expanded = !expanded }, { classes("switch-middle") }) {
        Div({ classes("inner") }) {
          Span({ classes("nearby", "ellipsis") }) { Text(stringResource(Res.string.selection_nearby) + " · ") }
          Span({ classes("count") }) { Text("${selection.index + 1} / $count") }
        }
      }
      CardIconButton(Icon.ChevronRight, stringResource(Res.string.selection_next), { onStep(1) }, height, "card")
    }
  }
}

/**
 * Кнопка карточки: плашка со скруглением 10 — она же область нажатия. Нажатие затемняет плашку и чуть уменьшает её
 * (0,94 → 1); ripple Material не нужен.
 */
@Composable
private fun CardButton(description: String, onClick: () -> Unit, attrs: AttrsScope.() -> Unit, content: @Composable () -> Unit) {
  Div({
    classes("card-button")
    attr("role", "button")
    attr("aria-label", description)
    onClick { onClick() }
    attrs()
  }) { content() }
}

/** Кнопка карточки со значком [size] × [size] на фоне [background] («subtle», «card»). */
@Composable
private fun CardIconButton(icon: Icon, description: String, onClick: () -> Unit, size: Int, background: String, iconSize: Int = 16) {
  CardButton(description, onClick, {
    classes(background)
    style { property("width", size.px); property("height", size.px) }
  }) { IconView(icon, iconSize, "var(--ink)") }
}

/** Номер кандидата в кружке: тёмный, выбранный — жёлтый; те же номера стоят в сцене. Цвета меняются плавно. */
@Composable
fun CandidateNumber(number: Int, selected: Boolean) {
  Div({ classes("candidate-number"); classIf(selected, "on") }) { Text(number.toString()) }
}

/** Тёмная плашка-подсказка «Удерживайте нажатие…» с крестиком: скрытая не возвращается (запоминается). */
@Composable
fun SelectionHint(text: String, onHide: () -> Unit, attrs: AttrsScope.() -> Unit) = HintPlate(text, attrs) {
  Div({ classes("hide"); style { property("width", "36px"); property("height", "36px") } }) {
    IconBox(Icon.Close, stringResource(Res.string.hide_hint), 36, onHide, color = "#fff", iconSize = 14)
  }
}

/** Та же плашка, что [SelectionHint], с кнопкой действия [action] вместо крестика. */
@Composable
fun ActionHint(text: String, action: String, onAction: () -> Unit, attrs: AttrsScope.() -> Unit) = HintPlate(text, attrs) {
  Div({ classes("action-hint", "ripple"); attr("role", "button"); onClick { onAction() } }) { Text(action) }
}

@Composable
private fun HintPlate(text: String, attrs: AttrsScope.() -> Unit, trailing: @Composable () -> Unit) {
  Div({ classes("hint-plate"); attrs() }) {
    // текст занимает всё место до кнопки: кнопка у правого края при любой длине строки
    Div({ classes("text") }) { Text(text) }
    trailing()
  }
}

@JsFun("(element, callback) => { const o = new ResizeObserver(() => callback()); const watch = (e) => { o.observe(e); for (const c of e.children) watch(c); }; watch(element); const m = new MutationObserver(() => { watch(element); callback(); }); m.observe(element, { childList: true, subtree: true, characterData: true }); return { o, m }; }")
private external fun observeTree(element: org.w3c.dom.Element, callback: () -> Unit): JsAny

@JsFun("(observers) => { observers.o.disconnect(); observers.m.disconnect(); }")
private external fun disconnect(observers: JsAny)
