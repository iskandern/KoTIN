package dev.iskandern.kotin

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import dev.iskandern.kotin.resources.Res
import dev.iskandern.kotin.resources.distance_m
import dev.iskandern.kotin.resources.drawing_pointer
import dev.iskandern.kotin.resources.to_drawing
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.min

/**
 * Указатель на чертёж, ушедший из кадра: плашка у края экрана там, где край пересекает луч из центра кадра на чертёж,
 * стрелка в ней — точно по направлению, рядом расстояние до чертежа в плане. Нажатие — [onClick].
 * [reserved] — поля, занятые другими плавающими элементами: плашка в них не заходит. [text] и [clickLabel] — слово
 * на плашке и подпись нажатия: у далёких объектов за кадром они свои, вид тот же.
 *
 * [drawing] читается только при расстановке (место плашки) и отрисовке (поворот стрелки), а текст пересобирается,
 * лишь когда меняется округлённое расстояние: камера двигается каждый кадр, и перекомпоновка с перемером текста на
 * каждом кадре стоила на слабом планшете 2–8 мс.
 */
@Composable
fun DrawingPointer(
  drawing: () -> OffscreenDrawing?, reserved: PaddingValues, onClick: () -> Unit, modifier: Modifier = Modifier,
  text: String = stringResource(Res.string.drawing_pointer),
  clickLabel: String = stringResource(Res.string.to_drawing),
) {
  // свежая лямбда: слои и производное состояние живут дольше первой композиции
  val drawingNow by rememberUpdatedState(drawing)
  // плашка, которая уходит, стоит там, где была: последнее направление запоминают слои, а не derivedStateOf —
  // у него побочных эффектов быть не должно
  val last = remember { LastDrawing() }
  val current = { drawingNow()?.also { last.value = it } ?: last.value }
  val distance by remember { derivedStateOf { (drawingNow() ?: last.value)?.distance?.let(::roundedDistance) } }
  Layout(
    content = {
      Row(
        Modifier
          .floatingCard(POINTER_SHAPE)
          .clip(POINTER_SHAPE)
          .clickable(onClickLabel = clickLabel, role = Role.Button, onClick = onClick)
          .padding(start = 6.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Box(Modifier.size(28.dp).background(KotinColors.Ink, CircleShape), contentAlignment = Alignment.Center) {
          Icon(
            KotinIcons.Arrow, null,
            Modifier.size(16.dp).graphicsLayer {
              rotationZ = current()?.let { (atan2(it.dirY, it.dirX) * 180 / PI).toFloat() } ?: 0f
            },
            tint = KotinColors.Card,
          )
        }
        Text(text, color = KotinColors.Ink, style = MaterialTheme.typography.bodyMedium)
        // ближе метра — точка интереса почти над чертежом, расстояние ни о чём не говорит
        distance?.let {
          Text(
            stringResource(Res.string.distance_m, formatGrouped(it, 0)),
            color = KotinColors.Muted, style = MaterialTheme.typography.bodyMedium, fontFamily = MonoFamily,
          )
        }
      }
    },
    modifier = modifier,
  ) { measurables, constraints ->
    val pointer = measurables.single().measure(Constraints())
    val width = constraints.maxWidth
    val height = constraints.maxHeight
    val left = reserved.calculateLeftPadding(LayoutDirection.Ltr).toPx()
    val right = reserved.calculateRightPadding(LayoutDirection.Ltr).toPx()
    val top = reserved.calculateTopPadding().toPx()
    val bottom = reserved.calculateBottomPadding().toPx()
    // центр плашки ходит по прямоугольнику, внутри которого она целиком помещается между занятыми полями
    val cx = (left + width - right) / 2f
    val cy = (top + height - bottom) / 2f
    val hx = ((width - left - right - pointer.width) / 2f).coerceAtLeast(0f)
    val hy = ((height - top - bottom - pointer.height) / 2f).coerceAtLeast(0f)
    layout(width, height) {
      // направление читается в слое: его смена только сдвигает готовую плашку, без расстановки и перемера
      pointer.placeWithLayer(0, 0) {
        val d = current() ?: return@placeWithLayer
        val t = min(
          if (abs(d.dirX) > 1e-6f) hx / abs(d.dirX) else Float.POSITIVE_INFINITY,
          if (abs(d.dirY) > 1e-6f) hy / abs(d.dirY) else Float.POSITIVE_INFINITY,
        )
        translationX = cx + d.dirX * t - pointer.width / 2f
        translationY = cy + d.dirY * t - pointer.height / 2f
      }
    }
  }
}


private class LastDrawing {
  var value: OffscreenDrawing? = null
}

private val POINTER_SHAPE = RoundedCornerShape(20.dp)
