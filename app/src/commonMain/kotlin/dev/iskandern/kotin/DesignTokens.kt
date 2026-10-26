package dev.iskandern.kotin

/**
 * Токены интерфейса — одни на обе версии: Compose (app) берёт их в [KotinColors] и константы размеров, DOM-версия
 * (webhtml) — в переменные CSS `:root`. Без Compose UI: цвета — ARGB, размеры — dp (= px CSS), время — мс.
 */
object DesignTokens {
  // цвета: светлая тема, карточки поверх светлой сцены
  const val SCENE = 0xFFE3E7EB
  const val CARD = 0xFFFFFFFF
  const val SUBTLE = 0xFFF3F5F7
  const val OUTLINE = 0xFFD3D9DF
  const val INK = 0xFF15202B
  const val MUTED = 0xFF56636F
  const val ACCENT = 0xFF1F5EFF
  const val ACCENT_SOFT = 0xFFE8EEFF
  const val ACCENT_INK = 0xFF1440B8
  const val WARNING = 0xFF8A5A00
  const val ERROR = 0xFFC4312A
  const val SELECTION = 0x47FFD600L
  const val HIGHLIGHT = 0xFFF5C400
  const val CHECK_OUTLINE = 0xFF8A96A1
  const val HANDLE = 0xFFC7CED5
  const val AXIS_X = 0xFFD03A3A
  const val AXIS_Y = 0xFF23894C
  const val AXIS_Z = 0xFF2F6FE0
  const val WHITE = 0xFFFFFFFF
  const val BLACK = 0xFF000000

  // затемнения цветом INK: сцена под боковым меню, нажатая кнопка карточки, тёмная плашка-подсказка
  const val SCRIM_ALPHA = 0.32f
  const val PRESSED_ALPHA = 0.10f
  const val HINT_ALPHA = 0.86f

  /** Кнопка на тёмной плашке-подсказке: белый с этой непрозрачностью. */
  const val ON_HINT_ALPHA = 0.14f

  /** Высота строки в ячейках свойств карточки выбранного (название и значение — по строке). */
  const val CELL_LINE = 20

  /** Отступ плавающих элементов от краёв экрана и между ними. */
  const val EDGE = 16

  /** Индикатор осей. */
  const val AXIS_SIZE = 72

  /** Ширина карточки выбранного объекта. */
  const val SELECTION_WIDTH = 340

  /** Высота свёрнутой карточки выбранного — доля высоты окна. */
  const val SELECTION_LIMIT = 0.45f

  /** Уже — телефон: меню боковое, шторка выбора, кнопки камеры колонкой. */
  const val PHONE_MAX_WIDTH = 600

  /** Уже — планшет. */
  const val TABLET_MAX_WIDTH = 1200

  /** Размеры раскладки: ширина меню, высота ≡ и карточки файла, кнопки камеры, строки меню. */
  class Sizes(val panelWidth: Int, val barHeight: Int, val cameraButton: Int, val rowHeight: Int)

  val DESKTOP = Sizes(panelWidth = 360, barHeight = 44, cameraButton = 40, rowHeight = 40)
  val TABLET = Sizes(panelWidth = 340, barHeight = 48, cameraButton = 48, rowHeight = 44)
  val PHONE = Sizes(panelWidth = 332, barHeight = 48, cameraButton = 52, rowHeight = 48)

  /** Длительность появления и исчезновения панелей. */
  const val MOTION_MS = 200

  /** Длительность ухода панели. */
  const val EXIT_MS = 150

  /** Масштаб, с которого панель вырастает при появлении. */
  const val APPEAR_SCALE = 0.94f

  /** Кривые — кубические Безье (x1, y1, x2, y2): LinearOutSlowIn и FastOutSlowIn Compose. */
  val LINEAR_OUT_SLOW_IN = floatArrayOf(0f, 0f, 0.2f, 1f)
  val FAST_OUT_SLOW_IN = floatArrayOf(0.4f, 0f, 0.2f, 1f)

  // мягкая тень панелей: суммарная непрозрачность INK у самой карточки, число слоёв и смещение вниз (доля высоты)
  const val SHADOW_ALPHA = 0.16f
  const val SHADOW_STEPS = 8
  const val SHADOW_OFFSET = 0.3f

  /** Высота тени плавающих карточек (≡, карточка файла, кнопки камеры). */
  const val FLOAT_ELEVATION = 3
}
