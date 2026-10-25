package dev.iskandern.kotin.landxml

import dev.iskandern.kotin.dxf.ScenePalette

/**
 * Цвета LandXML по виду объекта (0xRRGGBB): своих цветов в файле нет.
 * Фон сцены светлый (#E3E7EB): линии и подписи тёмные или насыщенные, без белых и бледных.
 * Поверхности и 12 цветов линий — общие с DXF ([ScenePalette]); номер в скобках — номер цвета линий.
 * Закреплённые тоны (выбор, трасса, четыре сети) не повторяются ни у кого.
 */
internal object LandXmlPalette {
  const val BOUNDARY = 0x15202B
  /** (7) */
  const val BREAKLINE = 0x9E2A14
  const val CONTOUR = 0x6F7A55
  const val ALIGNMENT = 0x1F5EFF
  /** Профиль земли вдоль трассы и стойки до проектной линии: умбра, темнее и серее канализации, вне цветов линий. */
  const val ALIGNMENT_GROUND = 0x5E4B3A
  /** (11) */
  const val CROSS_SECTION = 0x46561E
  /** (1) */
  const val CROSS_SECTION_DESIGN = 0xE04E1E
  const val POINT = 0x15202B
  /** Колодец ливнёвки и сети без типа. */
  const val STRUCT = 0x3F80CC
  /** Труба ливнёвки и сети без типа. */
  const val PIPE = 0x2B5FA8
  /** (2) */
  const val PLAN_FEATURE = 0x2E8B47
  /** (12) */
  const val PARCEL = 0x8A2E6E
  /** (9) */
  const val SURVEY = 0x6B2A9A

  /** Виды ситуации по `desc` (бордюр, тротуар, остров…): 12 цветов линий по кругу. */
  fun planFeature(index: Int): Int = ScenePalette.line(index)
  const val LABEL = 0x15202B
  const val STATIONING = 0x1440B8

  const val DATA_POINT = 0x56636F

  /** (1) */
  const val MONUMENT = 0xE04E1E
  /** (6) */
  const val ALIGN_PI = 0xC0399A
  /** (4) */
  const val ROAD_SIGN = 0xD23C4B

  /** Шкалы значений по пикетам: у каждого показателя свой тон, от тёмного к светлому; без жёлтого, синего, бирюзового и коричневого. */
  const val SCALE_SPEED = 0
  const val SCALE_SPEED_85 = 1
  const val SCALE_POSTED = 2
  const val SCALE_TRAFFIC = 3

  private val SCALES = arrayOf(
    // SpeedStation, DesignSpeed: тёмно-красный → (4)
    intArrayOf(0x6A1426, 0xD23C4B),
    // DesignSpeed85th: тёмно-фиолетовый → (3)
    intArrayOf(0x3A1868, 0x8A4FD0),
    // PostedSpeed: тёмно-пурпурный → (6)
    intArrayOf(0x5A1846, 0xC0399A),
    // DailyTrafficVolume (ADT): тёмно-зелёный → (2)
    intArrayOf(0x143F22, 0x2E8B47),
  )

  /** Цвет значения на шкале [scale]; [t] от 0 (минимум в файле) до 1 (максимум). */
  fun scale(scale: Int, t: Double): Int {
    val stops = SCALES[scale]
    val x = t.coerceIn(0.0, 1.0) * (stops.size - 1)
    val k = x.toInt().coerceAtMost(stops.size - 2)
    val f = x - k
    fun channel(shift: Int): Int {
      val a = (stops[k] shr shift) and 0xFF
      val b = (stops[k + 1] shr shift) and 0xFF
      return (a + (b - a) * f).toInt()
    }
    return (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
  }

  /** Положение [value] между [min] и [max] файла: 0..1; все значения равны — 1. */
  fun position(value: Double, min: Double, max: Double): Double = if (max > min) (value - min) / (max - min) else 1.0

  /** Граница по `bndType`: внешняя и полость — [BOUNDARY] (полость — пунктиром, [boundaryDashed]), остров — (8). */
  fun boundary(type: String): Int = when (type) {
    "island" -> 0x1F5F33
    else -> BOUNDARY
  }

  /** Граница рисуется пунктиром: полость, чтобы отличалась от внешней того же цвета. */
  fun boundaryDashed(type: String): Boolean = type == "void"

  /** Структурная линия по `brkType`: обычная (и без типа) — [BREAKLINE], стена (10), близость (3), неразрушающая (9). */
  fun breakline(type: String): Int = when (type) {
    "wall" -> 0x8E1B3A
    "proximity" -> 0x8A4FD0
    "nondestructive" -> 0x6B2A9A
    else -> BREAKLINE
  }

  /** Колодец по `pipeNetType`: ливнёвка (и неизвестный тип) — [STRUCT], канализация — коричневый, водопровод — бирюзовый, прочее — серый. */
  fun struct(type: String): Int = when (type) {
    "sanitary" -> 0xA87444
    "water" -> 0x1B9283
    "other" -> 0x78838D
    else -> STRUCT
  }

  /** Труба по `pipeNetType` — темнее колодца той же сети. */
  fun pipe(type: String): Int = when (type) {
    "sanitary" -> 0x7E522A
    "water" -> 0x0F6E64
    "other" -> 0x56606A
    else -> PIPE
  }
}
