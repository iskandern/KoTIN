package dev.iskandern.kotin.dxf

import dev.iskandern.kotin.model.MeshBuilder

/**
 * Стиль текста из таблицы STYLE: фиксированная высота (0 — не задана), ширина букв (x-factor), наклон в градусах,
 * замена шрифта (код 3 и гарнитура TTF из XDATA `ACAD`).
 */
class DxfTextStyle(
  val name: String, val height: Double, val widthFactor: Double, val obliqueDeg: Double,
  val font: DxfFont = DxfFont.DEFAULT,
) {
  companion object {
    val STANDARD = DxfTextStyle("STANDARD", 0.0, 1.0, 0.0)
  }
}

/**
 * Тип линии из таблицы LTYPE. [pattern] — длины элементов подряд: > 0 штрих, < 0 пробел, 0 точка.
 * Пустой [pattern] — сплошная линия (CONTINUOUS).
 * [embedded] — по элементу: текст в конце элемента, [DxfLineTypeText.SHAPE] — фигура SHX (не рисуется), null — нет.
 */
class DxfLineType(val name: String, val pattern: DoubleArray, val embedded: Array<DxfLineTypeText?>? = null) {
  val isSolid: Boolean get() = pattern.isEmpty()

  companion object {
    val CONTINUOUS = DxfLineType("CONTINUOUS", DoubleArray(0))
  }
}

/**
 * Текст в элементе типа линии: строка (код 9), handle стиля (340), высота в единицах паттерна (46),
 * поворот в радианах (50) — вдоль линии или от оси X при [absolute], смещения вдоль и поперёк линии (44, 45).
 */
class DxfLineTypeText(
  val text: String, val styleHandle: String, val height: Double, val rotation: Double, val absolute: Boolean,
  val dx: Double, val dy: Double,
) {
  companion object {
    val SHAPE = DxfLineTypeText("", "", 0.0, 0.0, false, 0.0, 0.0)
  }
}

/** Коды толщины линии (код 370): отрицательные — не число, а ссылка. */
const val LINE_WEIGHT_BY_LAYER = -1
const val LINE_WEIGHT_BY_BLOCK = -2
const val LINE_WEIGHT_DEFAULT = -3

/**
 * Прозрачность в кодировке DXF (код 440, у слоя — 1071): `0x020000AA` — альфа AA, `0x01000000` — BYBLOCK.
 * Возвращает альфу 0…255 или null, если значение — не альфа (BYBLOCK, BYLAYER, мусор).
 */
fun dxfTransparencyAlpha(value: Int): Int? = if (value ushr 24 == 2) value and 0xFF else null

/** Общие поля сущности: слой, цвет ACI (256 — BYLAYER, 0 — BYBLOCK), true color (код 420), тип линии, нормаль OCS. */
class DxfCommon {
  var layer: String = "0"
  var color: Int = COLOR_BY_LAYER
  var trueColor: Int? = null
  var paperSpace: Boolean = false
  // handle (код 5); 0 — нет
  var handle = 0L
  // тип линии (код 6): null — BYLAYER; масштаб типа линии (код 48)
  var lineType: String? = null
  var lineTypeScale = 1.0
  // толщина линии (код 370), 1/100 мм, либо LINE_WEIGHT_*
  var lineWeight = LINE_WEIGHT_BY_LAYER
  // толщина (код 39): выдавливание вдоль нормали у LINE, ARC, CIRCLE, полилиний, POINT, SOLID/TRACE
  var thickness = 0.0
  // прозрачность (код 440) в кодировке DXF, см. dxfTransparencyAlpha; TRANSPARENCY_BY_LAYER — кода нет
  var transparency = TRANSPARENCY_BY_LAYER
  // нормаль системы координат объекта (коды 210/220/230)
  var nx = 0.0
  var ny = 0.0
  var nz = 1.0

  fun copyFrom(other: DxfCommon) {
    layer = other.layer
    color = other.color
    trueColor = other.trueColor
    lineType = other.lineType
    lineTypeScale = other.lineTypeScale
    lineWeight = other.lineWeight
    thickness = other.thickness
    transparency = other.transparency
    paperSpace = other.paperSpace
    handle = other.handle
    nx = other.nx
    ny = other.ny
    nz = other.nz
  }

  companion object {
    const val COLOR_BY_BLOCK = 0
    const val COLOR_BY_LAYER = 256
    const val TRANSPARENCY_BY_LAYER = -1
    const val TRANSPARENCY_BY_BLOCK = 0x01000000
  }
}

/**
 * Сущности чертежа, которые умеет показывать вьюер. Координаты — как в DXF: у 2D-объектов (LWPOLYLINE, CIRCLE,
 * ARC, TEXT, SOLID, плоская POLYLINE, INSERT) — в системе объекта (OCS), у остальных — в мировой.
 */
sealed class DxfEntity {
  val common = DxfCommon()

  /** Часть той же сущности DXF, что и предыдущая (строка MTEXT, стрелка LEADER): в сцене — один объект с ней. */
  var joinsPrevious = false

  /** Тип объекта сцены у частей составной сущности (LEADER, MULTILEADER…); null — по классу. */
  var sourceKind: String? = null

  /**
   * 3DFACE внутри блока (в ENTITIES грани сразу идут в сетку, объект не создаётся).
   * [corners] — 4 угла подряд, [invisible] — код 70, скрытые рёбра (см. [MeshBuilder.addFace]).
   */
  class Face(val corners: DoubleArray, val invisible: Int) : DxfEntity()

  class Line(val coords: DoubleArray) : DxfEntity()

  /** XLINE (прямая, бесконечна в обе стороны) или RAY ([isRay], луч от точки): точка и направление в мировых координатах. */
  class XLine(
    val x: Double, val y: Double, val z: Double,
    val dx: Double, val dy: Double, val dz: Double,
    val isRay: Boolean,
  ) : DxfEntity()

  /**
   * LWPOLYLINE или POLYLINE. [xyz] — вершины подряд, [bulges] — выпуклость сегмента после вершины (дуга).
   * [inOcs] — координаты в OCS (LWPOLYLINE и плоская POLYLINE), иначе мировые (3D-полилиния).
   * [widths] — ширина сегмента после вершины в начале и в конце (пары, коды 40/41 или 43); null — все нулевые.
   * [continuousPattern] — флаг 128 (PLINEGEN): паттерн типа линии идёт через вершины; без него — заново в каждой.
   */
  class Polyline(
    val xyz: DoubleArray, val bulges: DoubleArray, val closed: Boolean, val inOcs: Boolean,
    val widths: DoubleArray? = null,
    val continuousPattern: Boolean = true,
  ) : DxfEntity() {
    val vertexCount: Int get() = xyz.size / 3
  }

  /** Сетка из граней (POLYLINE с флагом 64 или MESH): вершины в мировых координатах, грани — индексы с 0, углов не меньше 3. */
  class Polyface(val xyz: DoubleArray, val faces: List<IntArray>) : DxfEntity()

  /** CIRCLE (start = 0, end = 360) или ARC, углы в градусах против часовой в OCS. */
  class Arc(
    val cx: Double, val cy: Double, val cz: Double,
    val radius: Double,
    val startDeg: Double, val endDeg: Double,
    val isCircle: Boolean,
  ) : DxfEntity()

  /** ELLIPSE в мировых координатах: центр, конец большой полуоси (относительно центра), отношение осей, параметры. */
  class Ellipse(
    val cx: Double, val cy: Double, val cz: Double,
    val mx: Double, val my: Double, val mz: Double,
    val ratio: Double,
    val startParam: Double, val endParam: Double,
  ) : DxfEntity()

  class Spline(
    val degree: Int,
    val knots: DoubleArray,
    val control: DoubleArray,
    val fit: DoubleArray,
    val closed: Boolean,
    /** Веса контрольных точек (код 41); null — нерациональный сплайн. */
    val weights: DoubleArray? = null,
    /** Касательные в начале и конце (12/22/32, 13/23/33) для построения по опорным точкам; null — не заданы. */
    val startTangent: DoubleArray? = null,
    val endTangent: DoubleArray? = null,
  ) : DxfEntity()

  /** SOLID / TRACE: четыре угла в OCS, рисуется контуром. */
  class Solid(val corners: DoubleArray) : DxfEntity()

  /** WIPEOUT: контур маски в мировых координатах (тройки x y z, без повтора первой точки). */
  class Wipeout(val xyz: DoubleArray) : DxfEntity()

  /**
   * IMAGE: контур в мировых координатах ([xyz], как у WIPEOUT), handle IMAGEDEF ([defHandle], код 340) и место подписи:
   * центр, угол оси U к оси X и размер габарита контура вдоль U и V. Картинка не показывается.
   */
  class Image(
    val xyz: DoubleArray, val defHandle: String?,
    val cx: Double, val cy: Double, val cz: Double,
    val angleDeg: Double, val width: Double, val height: Double,
  ) : DxfEntity()

  /**
   * HATCH: контуры границы в OCS на высоте [elevation]. Заливка — градиент ([gradient]), штриховка ([pattern]) или,
   * если нет ни того ни другого, сплошная (код 70 = 1 или паттерн SOLID).
   * MPOLYGON читается сюда же: [outline] — контуры рисуются линией, [fillColor] — свой цвет заливки (ACI, null — как у сущности).
   */
  class Hatch(
    val loops: List<HatchLoop>,
    val elevation: Double,
    val pattern: HatchPattern? = null,
    val gradient: HatchGradient? = null,
    val outline: Boolean = false,
    val fillColor: Int? = null,
    /** Стиль заливки островов (код 75): 0 — по чётности, 1 — глубины 0 и 1, 2 — только внешние контуры. */
    val style: Int = 0,
  ) : DxfEntity()

  /**
   * MLINE: вершины ([xyz], мировые), направление сегмента после вершины ([directions], 12) и скоса ([miters], 13).
   * [offsets] — смещение каждого элемента вдоль скоса в каждой вершине (первый параметр 41, уже с масштабом
   * и выравниванием), по [elementCount] на вершину; NaN — не задано, берётся смещение стиля × [scale].
   * Стиль — по handle [styleHandle] (340) из OBJECTS, иначе по имени [styleName] (2).
   */
  class MLine(
    val xyz: DoubleArray, val directions: DoubleArray, val miters: DoubleArray,
    val offsets: DoubleArray, val elementCount: Int,
    val scale: Double, val closed: Boolean,
    val suppressStartCaps: Boolean, val suppressEndCaps: Boolean,
    val styleHandle: String?, val styleName: String,
  ) : DxfEntity() {
    val vertexCount: Int get() = xyz.size / 3
  }

  /**
   * 3DSOLID, REGION, BODY: грани тела ACIS в мировых координатах. [acis] null — данных нет или они не разобрались:
   * тело не рисуется, только считается. В R2013+ данные — SAB из секции ACDSDATA ([sab]): он разбирается при первом
   * обращении к [acis], то есть только у тел, которые рисуются.
   */
  class Body(acis: AcisBody?) : DxfEntity() {
    var sab: ByteArray? = null

    var acis: AcisBody? = acis
      get() {
        val data = sab ?: return field
        sab = null
        field = try {
          AcisSat.parseSab(data)
        } catch (_: RuntimeException) {
          null
        }
        return field
      }
  }

  /** [angleDeg] — код 50: угол оси X значка к оси X чертежа, градусы. */
  class Point(val x: Double, val y: Double, val z: Double, val angleDeg: Double = 0.0) : DxfEntity()

  /**
   * TEXT / ATTRIB ([inOcs] = true) или строка MTEXT ([inOcs] = false, мировые координаты).
   * [x], [y], [z] — начало базовой линии (или точка выравнивания), [angleDeg] — поворот, [height] — высота букв.
   */
  class Text(
    val x: Double, val y: Double, val z: Double,
    val height: Double,
    val angleDeg: Double,
    val value: String,
    val inOcs: Boolean,
    /**
     * Выравнивание TEXT/ATTRIB: по горизонтали 0 слева, 1 по центру, 2 справа, 4 по середине; по вертикали 0 базовая
     * линия, 1 снизу, 2 по середине, 3 сверху, [TEXT_ALIGN_UNDER_DESCENT] — перевёрнутый «снизу» (код 71 = 4).
     * [x], [y], [z] тогда — точка выравнивания. 3 «по ширине» и 5 «вписанный»
     * идут от первой точки на длину [fitLength]: первый подгоняет высоту букв, второй — ширину.
     */
    val alignH: Int = 0,
    val alignV: Int = 0,
    /** Ширина букв относительно обычной (код 41 или стиль). */
    val widthFactor: Double = 1.0,
    /** Наклон букв, градусы (код 51 или стиль). */
    val obliqueDeg: Double = 0.0,
    /** Длина строки между точками у «по ширине»/«вписанный», 0 — не задана. */
    val fitLength: Double = 0.0,
    /** MTEXT с рамкой: [value] — абзацы целиком, строки раскладываются по шрифту при построении сцены. */
    val frame: MTextFrame? = null,
    /** Фрагменты MTEXT с цветом, высотой и линиями (концы — индексы в [value]); null — весь текст как есть. */
    val runs: List<DxfTextRun>? = null,
    /** Плашка под MTEXT (коды 90, 63/421, 45); null — без плашки. */
    val background: DxfTextBackground? = null,
    /** Замена шрифта стиля (код 7). */
    val font: DxfFont = DxfFont.DEFAULT,
  ) : DxfEntity()

  /**
   * Вхождение блока (INSERT, MINSERT). DIMENSION и ACAD_TABLE тоже рисуются анонимным блоком —
   * для них [insertionInWorld] = true и перенос/поворот/масштаб единичные.
   */
  class Insert(
    // у блока-содержимого MULTILEADER имя известно только после OBJECTS (стиль): ставится после чтения файла
    var blockName: String,
    val x: Double, val y: Double, val z: Double,
    val sx: Double, val sy: Double, val sz: Double,
    val angleDeg: Double,
    val columns: Int, val rows: Int,
    val columnSpacing: Double, val rowSpacing: Double,
    val attributes: List<Text>,
  ) : DxfEntity()
}

/**
 * Рамка MTEXT: абзацы переносятся по словам не шире [width], строки идут с шагом [pitch] (мировые единицы).
 * [attachment] — привязка (код 71, 1…9). Колонки ([columnWidth] > 0): ширина и промежуток, высота каждой колонки —
 * [columnHeights] по порядку, если их нет — [height] (≤ 0 — без ограничения); текст переходит в следующую колонку,
 * когда высота исчерпана, или по разрыву [MTEXT_COLUMN_BREAK].
 */
class MTextFrame(
  val width: Double,
  val pitch: Double,
  val attachment: Int,
  val columnWidth: Double = 0.0,
  val gutter: Double = 0.0,
  val height: Double = 0.0,
  val columnHeights: DoubleArray = DoubleArray(0),
) {
  /** Та же рамка в масштабе вхождения: [along] — вдоль строки, [up] — поперёк. */
  fun scaled(along: Double, up: Double) = MTextFrame(
    width * along, pitch * up, attachment, columnWidth * along, gutter * along, height * up,
    DoubleArray(columnHeights.size) { columnHeights[it] * up },
  )
}

/**
 * Фрагмент текста: символы до [end] (не включая) от конца предыдущего. [color] — 0xRRGGBB или [TEXT_COLOR_ENTITY],
 * [scale] — высота букв относительно высоты подписи, [decoration] — биты [TEXT_UNDERLINE] и т. п.
 */
class DxfTextRun(val end: Int, val color: Int, val scale: Double, val decoration: Int)

/** Цвет фрагмента — цвет самой сущности. */
const val TEXT_COLOR_ENTITY = -1

/** Плашка под текстом цветом окна (фона вьюера). */
const val TEXT_BACKGROUND_WINDOW = -2

const val TEXT_UNDERLINE = 1
const val TEXT_OVERLINE = 2
const val TEXT_STRIKE = 4

/**
 * Плашка под строками MTEXT: [color] — 0xRRGGBB, [TEXT_COLOR_ENTITY] или [TEXT_BACKGROUND_WINDOW]; [margin] — поле
 * вокруг текста в долях высоты строки ((код 45) − 1); [width] — ширина рамки (колонки) MTEXT, 0 — по ширине строки.
 * [edge] > 0 — кант цвета [edgeColor] шириной в долях высоты строки снаружи плашки (подсветка выбранной подписи).
 */
class DxfTextBackground(val color: Int, val margin: Double, val width: Double, val edgeColor: Int = 0, val edge: Double = 0.0)

/** Объект из секции OBJECTS; читаются только нужные вьюеру, ключ в [DxfDocument.objects] — handle (код 5). */
sealed class DxfObject

/**
 * MLINESTYLE: элементы (смещение 49, цвет ACI 62, тип линии 6), [flags] (70: 1 заливка, 2 скосы в изломах,
 * 16/256 торец линией в начале/конце, 32/512 внутренние дуги, 64/1024 внешняя дуга) и цвет заливки [fillColor] (ACI).
 */
class DxfMLineStyle(
  val name: String, val flags: Int, val fillColor: Int,
  val offsets: DoubleArray, val colors: IntArray, val lineTypes: List<String>,
) : DxfObject() {
  val elementCount: Int get() = offsets.size

  companion object {
    const val FILL = 1
    const val MITERS = 2
    const val START_LINE = 16
    const val START_INNER_ARCS = 32
    const val START_ROUND = 64
    const val END_LINE = 256
    const val END_INNER_ARCS = 512
    const val END_ROUND = 1024

    /** Стиль по умолчанию, если в файле его нет: два элемента ±0,5 по слою. */
    val STANDARD = DxfMLineStyle(
      "STANDARD", 0, DxfCommon.COLOR_BY_LAYER, doubleArrayOf(0.5, -0.5),
      intArrayOf(DxfCommon.COLOR_BY_LAYER, DxfCommon.COLOR_BY_LAYER), listOf("BYLAYER", "BYLAYER"),
    )
  }
}

/**
 * MLEADERSTYLE: значения MULTILEADER для того, что сущность не переопределяет (флаги 90 сущности).
 * Тип содержимого 170 (1 — блок, 2 — MTEXT), цвет линии 91, тип линии 340 (handle LTYPE), толщина 92,
 * блок 343 (handle BLOCK_RECORD) и его цвет 94. Цвета — как в MULTILEADER: старший байт — вид цвета. null — не задано.
 */
class DxfMLeaderStyle(
  val contentType: Int?, val lineColor: Int?, val lineTypeHandle: String?, val lineWeight: Int?,
  val blockHandle: String?, val blockColor: Int?,
) : DxfObject()

/** IMAGEDEF: путь к файлу картинки (код 1), как записан автором. */
class DxfImageDef(val path: String) : DxfObject()

class DxfBlock(val name: String, val bx: Double, val by: Double, val bz: Double, val entities: List<DxfEntity>)
