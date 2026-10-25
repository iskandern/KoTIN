package dev.iskandern.kotin

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Значки интерфейса — контурные, сетка 24 × 24. Цвет задаёт `tint` у `Icon`,
 * толщина линии — [width] в единицах сетки.
 */
object KotinIcons {
  val Plus = icon("plus") { moveTo(12f, 5f); verticalLineTo(19f); moveTo(5f, 12f); horizontalLineTo(19f) }

  val Minus = icon("minus") { moveTo(5f, 12f); horizontalLineTo(19f) }

  /** Орбита: планета и наклонённое кольцо («Сатурн»). */
  val Orbit = icon("orbit", width = 1.8f) {
    // планета r 5 вокруг (12, 12)
    moveTo(7f, 12f)
    arcTo(5f, 5f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 17f, y1 = 12f)
    arcTo(5f, 5f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 7f, y1 = 12f)
    // кольцо rx 10.5, ry 3.2, наклон −24°
    moveTo(2.3f, 15.7f)
    arcTo(10.5f, 3.2f, -24f, isMoreThanHalf = true, isPositiveArc = false, x1 = 21.7f, y1 = 8.3f)
    arcTo(10.5f, 3.2f, -24f, isMoreThanHalf = true, isPositiveArc = false, x1 = 2.3f, y1 = 15.7f)
  }

  /** Карта: сложенный лист. */
  val Map = icon("map", width = 1.8f) {
    moveTo(3f, 6f); lineTo(9f, 4f); lineTo(15f, 6f); lineTo(21f, 4f); verticalLineTo(18f)
    lineTo(15f, 20f); lineTo(9f, 18f); lineTo(3f, 20f); close()
    moveTo(9f, 4f); verticalLineTo(18f); moveTo(15f, 6f); verticalLineTo(20f)
  }

  val Menu = icon("menu") { moveTo(4f, 6f); horizontalLineTo(20f); moveTo(4f, 12f); horizontalLineTo(20f); moveTo(4f, 18f); horizontalLineTo(20f) }

  /** Папка: открыть другой файл. */
  val Folder = icon("folder") {
    moveTo(3f, 7f)
    arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 5f, y1 = 5f)
    horizontalLineTo(9f); lineTo(11f, 7f); horizontalLineTo(19f)
    arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 21f, y1 = 9f)
    verticalLineTo(17f)
    arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 19f, y1 = 19f)
    horizontalLineTo(5f)
    arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 3f, y1 = 17f)
    close()
  }

  val Close = icon("close") { moveTo(6f, 6f); lineTo(18f, 18f); moveTo(18f, 6f); lineTo(6f, 18f) }

  /** Галочка в отмеченном флажке. */
  val Check = icon("check", width = 3.2f) { moveTo(5f, 12f); lineTo(10f, 17f); lineTo(19f, 7f) }

  /** Раскрыть раздел меню (повёрнутая — раздел свёрнут). */
  val ChevronDown = icon("chevron-down") { moveTo(6f, 9f); lineTo(12f, 15f); lineTo(18f, 9f) }

  /** Развернуть карточку выбранного (вниз — свернуть). */
  val ChevronUp = icon("chevron-up") { moveTo(6f, 15f); lineTo(12f, 9f); lineTo(18f, 15f) }

  /** Раскрыть группу списка (повёрнутая на 90° — группа раскрыта). */
  val ChevronRight = icon("chevron-right") { moveTo(9f, 6f); lineTo(15f, 12f); lineTo(9f, 18f) }
  val ChevronLeft = icon("chevron-left") { moveTo(15f, 6f); lineTo(9f, 12f); lineTo(15f, 18f) }

  /** Стрелка вправо: указатель на чертёж за краем кадра поворачивает её по направлению. */
  val Arrow = icon("arrow", width = 2.4f) { moveTo(5f, 12f); horizontalLineTo(19f); moveTo(13f, 6f); lineTo(19f, 12f); lineTo(13f, 18f) }

  private fun icon(name: String, width: Float = 2f, build: PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).path(
      stroke = SolidColor(Color.Black),
      strokeLineWidth = width,
      strokeLineCap = StrokeCap.Round,
      strokeLineJoin = StrokeJoin.Round,
      pathBuilder = build,
    ).build()
}
