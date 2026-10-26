package dev.iskandern.kotin

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import org.jetbrains.compose.resources.stringResource
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

/** Кнопки карточки: скругление плашки, затемнение и масштаб под нажатием. */
private val BUTTON_SHAPE = RoundedCornerShape(10.dp)
private const val PRESS_SCALE = 0.94f

/** Кнопки в шапке карточки: «развернуть» и крестик. */
private val HEADER_BUTTON = 36.dp

/**
 * Карточка выбранного объекта: цветной квадрат, «Вид · Имя», ссылка «Слой: …» ([onShowLayer] — меню на слое),
 * свойства сеткой в две колонки; крестик снимает выбор. Под нажатием было несколько объектов — внизу переключатель
 * «‹ Рядом ещё объекты · 1 / 3 ›» ([onPick] — к кандидату по номеру): он в подвале, поэтому при листании стрелки
 * не сдвигаются; нажатие на середину раскрывает над ним список с теми же номерами, что стоят в сцене.
 * Высоту ограничивает вызывающий; свойства прокручиваются, шапка и переключатель видны всегда. Не влезают —
 * в шапке кнопка «развернуть / свернуть» ([expanded], [onExpand]); [onExpand] null — кнопки нет (шторку телефона
 * тянут за шапку: [header] — жест перетаскивания). [animateSize] — плавная смена высоты (выключают, пока высоту
 * ведёт палец). [buttonSize] — высота строки переключателя. [swapXY] — оси
 * переставлены: координаты подписываются «X (север)», «Y (восток)».
 * Заголовок и свойства выделяются и копируются; ссылка, переключатель и кнопки — нет.
 */
@Composable
fun SelectionCard(
  selection: ObjectSelection, swatch: (SceneObject) -> Color?, swapXY: Boolean, onClose: () -> Unit, onShowLayer: () -> Unit,
  modifier: Modifier = Modifier, onPick: (Int) -> Unit = {}, expanded: Boolean = false, onExpand: ((Boolean) -> Unit)? = null,
  header: Modifier = Modifier, animateSize: Boolean = true, buttonSize: Dp = 40.dp,
) {
  val count = selection.candidates.size
  // куда листали стрелкой (кандидат и направление): «›» — новое приходит справа, в том числе с последнего на первый
  var arrow by remember(selection.candidates) { mutableStateOf<Pair<Int, Int>?>(null) }
  // смена объекта: шапка и свойства нового въезжают на SLIDE_PART ширины (шапка ещё и проявляется), прежние уходят
  // сразу. Своим сдвигом, а не AnimatedContent: тот держал и пересобирал оба объекта, а полупрозрачный слой во всю
  // карточку на каждом кадре был дорог телефону. Узлы те же, меняются только тексты: у соседей много общих свойств,
  // и неизменные ячейки не перемеряются. Первый показ — без сдвига
  val last = remember(selection.candidates) { IntArray(1) { selection.index } }
  val step = if (selection.index == last[0]) 0 else arrow?.takeIf { it.first == selection.index }?.second ?: if (selection.index > last[0]) 1 else -1
  SideEffect { last[0] = selection.index }
  val enter = remember(selection.objectId) { Animatable(if (step == 0) 1f else 0f) }
  val from = remember(selection.objectId) { step }
  LaunchedEffect(enter) { enter.animateTo(1f, tween(MOTION_MS, easing = LinearOutSlowInEasing)) }
  // прокрутка свойств — своя у каждого объекта
  val scroll = remember(selection.objectId) { ScrollState(0) }
  // низ карточки на месте: она растёт и уменьшается вверх
  val size = if (animateSize) Modifier.animateContentSize(tween(MOTION_MS, easing = LinearOutSlowInEasing), Alignment.BottomStart) else Modifier
  SelectionContainer(modifier.uiTag("selection-card").then(size)) {
    Column(Modifier.uiTag("selection-body").padding(horizontal = 16.dp, vertical = 14.dp)) {
      Row(Modifier.uiTag("selection-head").then(header), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f).clipToBounds()) {
          SelectionTitle(selection, swatch(selection.objectInfo), onShowLayer, Modifier.slideIn(enter, from, fade = true))
        }
        DisableSelection {
          if (onExpand != null) ExpandButton(scroll, selection.candidates, expanded, onExpand)
          CardIconButton(KotinIcons.Close, stringResource(Res.string.clear_selection), onClose, Modifier.uiTag("selection-close").size(HEADER_BUTTON), KotinColors.Subtle)
        }
      }
      // свойства забирают остаток высоты: упёрлись в лимит — прокручиваются, переключатель под ними виден
      val attributes = selection.objectInfo.attributes
      if (attributes.isNotEmpty()) Box(Modifier.weight(1f, fill = false).uiTag("attr-scroll").clipToBounds()) {
        AttributeGrid(attributes, swapXY, scroll, Modifier.slideIn(enter, from, fade = false).padding(top = 12.dp))
      }
      if (count > 1) DisableSelection {
        CandidateSwitch(selection, buttonSize, onPick) { step ->
          val target = (selection.index + step + count) % count
          arrow = target to step
          onPick(target)
        }
      }
    }
  }
}

/**
 * Въезд содержимого при смене объекта: ход [enter] 0..1, [from] — откуда (1 — справа, −1 — слева, 0 — без сдвига),
 * [fade] — ещё и проявление. Читается при рисовании: анимация не пересобирает и не перемеряет содержимое.
 */
private fun Modifier.slideIn(enter: Animatable<Float, *>, from: Int, fade: Boolean): Modifier =
  if (from == 0) this else graphicsLayer {
    val e = enter.value
    translationX = (1f - e) * from * size.width / SLIDE_PART
    if (fade) alpha = e
  }

/**
 * Кнопка «развернуть / свернуть»: видна развёрнутой или когда свойства не влезают в лимит. Влезают ли — по замеру
 * списка свойств [scroll] показанного объекта (прокручивается ли он); у только что созданного списка замера ещё нет
 * (maxValue — Int.MAX_VALUE), и до него кнопка держит прежнее состояние, а не пропадает на кадр. Новый выбор
 * ([candidates]) — заново.
 */
@Composable
private fun ExpandButton(scroll: ScrollState, candidates: List<PickCandidate>, expanded: Boolean, onExpand: (Boolean) -> Unit) {
  var overflow by remember(candidates) { mutableStateOf(false) }
  LaunchedEffect(scroll) {
    snapshotFlow { scroll.maxValue.takeIf { it != Int.MAX_VALUE }?.let { it > 0 } }.filterNotNull().collect { overflow = it }
  }
  if (expanded || overflow) {
    CardIconButton(
      if (expanded) KotinIcons.ChevronDown else KotinIcons.ChevronUp,
      stringResource(if (expanded) Res.string.selection_collapse else Res.string.selection_expand),
      { onExpand(!expanded) }, Modifier.uiTag("selection-expand").size(HEADER_BUTTON), KotinColors.Subtle,
    )
  }
}

/**
 * Свойства сеткой в две колонки: ячейка — название и значение одним текстом на скруглённой подложке. Строки —
 * обычной колонкой с прокруткой [scroll], не ленивым списком: выделение текста в ленивом списке теряло пересобранные
 * ячейки, а размеченная целиком сетка не пересобирается, когда шторка меняет высоту. Даже длинная карточка — десятки
 * текстов. Ячейки строки одной высоты (длинное значение переносится, соседняя подложка растягивается) — своей
 * раскладкой за один проход, без IntrinsicSize (он мерил строку дважды).
 * Первый кадр смены объекта — самый тяжёлый, поэтому в нём строк столько, сколько было у прежнего (не меньше
 * [FIRST_ROWS] — сколько видно в свёрнутой карточке), недостающие добавляются по одной за кадр, когда въезд нового
 * объекта закончился: строка — несколько миллисекунд разметки текста, в кадрах сдвига им не место.
 */
@Composable
private fun AttributeGrid(attributes: List<SceneAttribute>, swapXY: Boolean, scroll: ScrollState, modifier: Modifier) {
  val total = (attributes.size + 1) / 2
  // строк в сетке: узлы переходят от объекта к объекту, меняются только тексты
  var built by remember { mutableIntStateOf(0) }
  val rows = minOf(total, maxOf(built, FIRST_ROWS))
  SideEffect { built = rows }
  LaunchedEffect(attributes) {
    delay(100L)
    while (built < total) {
      withFrameNanos { }
      built++
    }
  }
  Column(modifier.verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(CELL_GAP)) {
    for (row in 0 until rows) AttributeRow(attributes[2 * row], attributes.getOrNull(2 * row + 1), swapXY, Modifier.uiTag("attr-row-$row"))
  }
}

/** Строки сетки свойств в первом кадре смены объекта. */
private const val FIRST_ROWS = 5

/** Текст ячейки: название мелко и серым, под ним значение моноширинным — один абзац, одна раскладка текста. */
@Composable
private fun attributeText(attribute: SceneAttribute, swapXY: Boolean): AnnotatedString {
  val name = attributeName(attribute.name, swapXY)
  val value = localized(attribute.value)
  val mono = MonoFamily
  return remember(name, value, mono) {
    buildAnnotatedString {
      // перевод строки — в мелком шрифте названия: иначе первая строка выше на шрифт текста по умолчанию
      withStyle(SpanStyle(color = KotinColors.Muted, fontSize = 12.sp)) { append(name); append('\n') }
      withStyle(SpanStyle(color = KotinColors.Ink, fontSize = 14.sp, fontFamily = mono)) { append(value) }
    }
  }
}

/** Строка сетки свойств: две ячейки пополам ширины, подложки во всю высоту строки (по более высокой ячейке). */
@Composable
private fun AttributeRow(left: SceneAttribute, right: SceneAttribute?, swapXY: Boolean, modifier: Modifier) {
  Layout(
    {
      // строки названия и значения — одной высоты CELL_LINE; своя у каждой — вдвое дороже раскладка. Основа абзаца —
      // шрифт значения: с основой bodyLarge Skia рисовала строку на 1,5 px выше CELL_LINE
      Text(attributeText(left, swapXY), fontSize = 14.sp, fontFamily = MonoFamily, lineHeight = CELL_LINE)
      if (right != null) Text(attributeText(right, swapXY), fontSize = 14.sp, fontFamily = MonoFamily, lineHeight = CELL_LINE)
    },
    modifier.drawBehind {
      val gap = CELL_GAP.toPx()
      val width = (size.width - gap) / 2
      val radius = CornerRadius(CELL_RADIUS.toPx())
      drawRoundRect(KotinColors.Subtle, Offset.Zero, Size(width, size.height), radius)
      if (right != null) drawRoundRect(KotinColors.Subtle, Offset(width + gap, 0f), Size(width, size.height), radius)
    },
  ) { texts, constraints ->
    val gap = CELL_GAP.roundToPx()
    val padX = CELL_PADDING_X.roundToPx()
    val padY = CELL_PADDING_Y.roundToPx()
    val width = ((constraints.maxWidth - gap) / 2).coerceAtLeast(0)
    val placeables = texts.map { it.measure(Constraints(maxWidth = (width - 2 * padX).coerceAtLeast(0))) }
    // высота — целым числом строк CELL_LINE: абзац из двух шрифтов Skia меряет на доли пикселя выше
    val line = CELL_LINE.roundToPx()
    val lines = ((placeables.maxOfOrNull { it.height } ?: 0) + line / 2) / line
    layout(constraints.maxWidth, lines * line + 2 * padY) {
      placeables.forEachIndexed { i, text -> text.place(i * (width + gap) + padX, padY) }
    }
  }
}

/** Ячейка свойства: скругление подложки, отступ текста, зазор между ячейками. */
private val CELL_RADIUS = 10.dp
private val CELL_PADDING_X = 10.dp
private val CELL_PADDING_Y = 8.dp
private val CELL_GAP = 8.dp
private val CELL_LINE = DesignTokens.CELL_LINE.sp

/** Доля ширины, на которую сдвигается содержимое карточки при смене кандидата. */
private const val SLIDE_PART = 10

/** Шапка карточки: цветной квадрат, «Вид · Имя», ссылка на слой, пометка о частичной подсветке. */
@Composable
private fun SelectionTitle(selection: ObjectSelection, swatch: Color?, onShowLayer: () -> Unit, modifier: Modifier) {
  val info = selection.objectInfo
  Row(modifier.uiTag("selection-title"), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
    if (info.color != SceneObject.NO_COLOR) {
      Box(
        Modifier.padding(top = 4.dp).size(14.dp)
          .background(swatch ?: Color(0xFF000000.toInt() or info.color), RoundedCornerShape(4.dp)),
      )
    }
    Column(Modifier.weight(1f)) {
      Text(
        info.kind + if (info.name.isNotEmpty()) " · ${localized(info.name)}" else "",
        color = KotinColors.Ink, fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.uiTag("selection-name"),
      )
      if (info.layer.isNotEmpty()) DisableSelection {
        // подсветка нажатия — скруглённая и чуть шире текста, сам текст на месте
        Text(
          stringResource(Res.string.selection_layer, localized(info.layer)),
          color = KotinColors.Accent, fontSize = 13.sp, lineHeight = 18.sp,
          modifier = Modifier.offset(x = (-4).dp).uiTag("selection-layer").clip(RoundedCornerShape(6.dp))
            .clickable(role = Role.Button, onClick = onShowLayer).padding(horizontal = 4.dp, vertical = 2.dp),
        )
      }
      if (selection.geometry.truncated) {
        Text(stringResource(Res.string.selection_partial), color = KotinColors.Muted, fontSize = 12.sp)
      }
    }
  }
}

/**
 * Переключатель кандидатов: стрелки по кругу ([onStep] — на шаг ±1), посередине «Рядом ещё объекты · 1 / 3»;
 * нажатие на середину раскрывает над строкой список «номер — вид · имя» (выбранный — на жёлтой подложке),
 * строка списка выбирает кандидата ([onPick]). Список не выше [LIST_ROWS] строк, длиннее — прокручивается; строка
 * со стрелками меряется первой и не сжимается. Стрелки и середина — во всю высоту строки [height].
 */
@Composable
private fun CandidateSwitch(selection: ObjectSelection, height: Dp, onPick: (Int) -> Unit, onStep: (Int) -> Unit) {
  val count = selection.candidates.size
  var expanded by remember(selection.candidates) { mutableStateOf(Stories.current?.candidatesOpen == true) }
  Column(Modifier.padding(top = 10.dp).uiTag("candidate-switch").background(KotinColors.Subtle, RoundedCornerShape(14.dp)).padding(4.dp)) {
    AnimatedVisibility(
      expanded, Modifier.weight(1f, fill = false),
      enter = expandVertically(tween(MOTION_MS, easing = LinearOutSlowInEasing), Alignment.Bottom) + fadeIn(tween(MOTION_MS)),
      exit = shrinkVertically(tween(EXIT_MS), Alignment.Bottom) + fadeOut(tween(EXIT_MS)),
    ) {
      val list = rememberScrollState()
      // открытый список — с выбранным в поле зрения
      val rowPx = with(LocalDensity.current) { LIST_ROW.roundToPx() }
      LaunchedEffect(Unit) { list.scrollTo(rowPx * (selection.index - LIST_ROWS.toInt() + 1).coerceAtLeast(0)) }
      Column(Modifier.uiTag("candidate-list").padding(bottom = 4.dp).heightIn(max = LIST_ROW * LIST_ROWS).verticalScroll(list)) {
        selection.candidates.forEachIndexed { i, candidate ->
          val on = i == selection.index
          Row(
            Modifier.uiTag("candidate-item-$i").fillMaxWidth().height(LIST_ROW).clip(RoundedCornerShape(8.dp)).background(if (on) KotinColors.Selection else Color.Transparent)
              .clickable(role = Role.Button) { onPick(i) }.padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            CandidateNumber(i + 1, on)
            Text(
              candidate.kind + if (candidate.name.isNotEmpty()) " · ${localized(candidate.name)}" else "",
              color = KotinColors.Ink, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
          }
        }
      }
    }
    Row(Modifier.uiTag("switch-row").height(height), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
      CardIconButton(KotinIcons.ChevronLeft, stringResource(Res.string.selection_prev), { onStep(-1) }, Modifier.uiTag("candidate-prev").size(height), KotinColors.Card)
      CardButton(stringResource(Res.string.selection_list), { expanded = !expanded }, Modifier.weight(1f).uiTag("candidate-middle").fillMaxHeight()) {
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
          Text(
            stringResource(Res.string.selection_nearby) + " · ", color = KotinColors.Muted, fontSize = 12.sp, lineHeight = 16.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
          )
          Text("${selection.index + 1} / $count", color = KotinColors.Ink, fontSize = 12.sp, lineHeight = 16.sp, fontFamily = MonoFamily)
        }
      }
      CardIconButton(KotinIcons.ChevronRight, stringResource(Res.string.selection_next), { onStep(1) }, Modifier.uiTag("candidate-next").size(height), KotinColors.Card)
    }
  }
}

/** Строка списка кандидатов и сколько их видно без прокрутки (половинка следующей подсказывает, что список длиннее). */
private val LIST_ROW = 32.dp
private const val LIST_ROWS = 5.5f

/**
 * Кнопка карточки: плашка [BUTTON_SHAPE] с фоном [background] — она же область нажатия. Нажатие затемняет плашку
 * и чуть уменьшает её (0,94 → 1, как появление панелей); ripple Material не нужен — он вылезал за плашку.
 */
@Composable
private fun CardButton(description: String, onClick: () -> Unit, modifier: Modifier, background: Color = Color.Transparent, content: @Composable () -> Unit) {
  val source = remember { MutableInteractionSource() }
  val pressed by source.collectIsPressedAsState()
  // нажатие — быстро, отпускание — за время появления панелей
  val press by animateFloatAsState(if (pressed) 1f else 0f, tween(if (pressed) EXIT_MS / 2 else MOTION_MS, easing = LinearOutSlowInEasing))
  Box(
    modifier
      .graphicsLayer {
        val scale = 1f - (1f - PRESS_SCALE) * press
        scaleX = scale
        scaleY = scale
        shape = BUTTON_SHAPE
        clip = true
      }
      .drawBehind {
        drawRect(background)
        drawRect(KotinColors.Pressed, alpha = press)
      }
      .clickable(source, indication = null, onClickLabel = description, role = Role.Button, onClick = onClick),
    contentAlignment = Alignment.Center,
  ) { content() }
}

/** Кнопка карточки со значком: см. [CardButton]. */
@Composable
private fun CardIconButton(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier, background: Color, iconSize: Dp = 16.dp) {
  CardButton(description, onClick, modifier, background) { Icon(icon, description, Modifier.size(iconSize), tint = KotinColors.Ink) }
}

/**
 * Номер кандидата в кружке: тёмный, выбранный — жёлтый; те же номера стоят в сцене (`buildCandidateMarksMesh`). Цвета
 * меняются плавно и читаются при рисовании: анимация не пересобирает кружки на каждом кадре.
 */
@Composable
fun CandidateNumber(number: Int, selected: Boolean, modifier: Modifier = Modifier) {
  val fill = animateColorAsState(if (selected) KotinColors.Highlight else KotinColors.Ink, tween(MOTION_MS))
  val ink = animateColorAsState(if (selected) KotinColors.Ink else KotinColors.Card, tween(MOTION_MS))
  Box(modifier.size(CANDIDATE_MARK).drawBehind { drawCircle(fill.value) }, contentAlignment = Alignment.Center) {
    BasicText(
      number.toString(), color = { ink.value },
      style = TextStyle(fontSize = 11.sp, lineHeight = 12.sp, fontFamily = MonoFamily, fontWeight = FontWeight.SemiBold),
    )
  }
}

/** Размер кружка с номером кандидата. */
val CANDIDATE_MARK = 22.dp

/** Тёмная плашка-подсказка «Удерживайте нажатие…» с крестиком: скрытая не возвращается (запоминается). */
@Composable
fun SelectionHint(text: String, onHide: () -> Unit, modifier: Modifier = Modifier) = HintPlate("selection-hint", text, modifier) {
  Box(Modifier.uiTag("hint-hide").size(36.dp).clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = DesignTokens.ON_HINT_ALPHA))) {
    IconBox(KotinIcons.Close, stringResource(Res.string.hide_hint), 36.dp, onHide, tint = Color.White, iconSize = 14.dp)
  }
}

/** Та же плашка, что [SelectionHint], с кнопкой действия [action] вместо крестика. */
@Composable
fun ActionHint(text: String, action: String, onAction: () -> Unit, modifier: Modifier = Modifier) = HintPlate("labels-notice", text, modifier) {
  Box(
    Modifier.uiTag("hint-action").heightIn(min = 36.dp).clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = DesignTokens.ON_HINT_ALPHA))
      .clickable(role = Role.Button, onClick = onAction).padding(horizontal = 12.dp),
    contentAlignment = Alignment.Center,
  ) {
    Text(action, color = Color.White, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold)
  }
}

@Composable
private fun HintPlate(tag: String, text: String, modifier: Modifier, trailing: @Composable () -> Unit) {
  val shape = RoundedCornerShape(14.dp)
  Row(
    modifier.uiTag(tag).clip(shape).background(KotinColors.Hint).blockSceneGestures().padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    // текст занимает всё место до кнопки: кнопка у правого края при любой длине строки
    Text(text, color = Color.White, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.weight(1f).uiTag("hint-text"))
    trailing()
  }
}

