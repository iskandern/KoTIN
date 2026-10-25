package dev.iskandern.kotin.model

import dev.iskandern.kotin.dxf.DxfEntity
import dev.iskandern.kotin.dxf.DxfFont
import dev.iskandern.kotin.dxf.DxfSavedView
import dev.iskandern.kotin.dxf.DxfSheet
import dev.iskandern.kotin.dxf.DxfTextBackground
import dev.iskandern.kotin.dxf.DxfTextRun
import dev.iskandern.kotin.dxf.MTextFrame
import dev.iskandern.kotin.dxf.POINT_MODE_UNSET

/** Непрозрачность (альфа) по умолчанию: 255 — непрозрачно. */
const val ALPHA_OPAQUE = 255

/** Пакет обычной геометрии. */
const val EXTRUSION_NONE = 0
/** Пакет толщины (код 39) линий, кривых и SOLID/TRACE: копия контура, рёбра, боковые и верхние грани. */
const val EXTRUSION_ENTITY = 1
/** Пакет толщины точек: отрезок вдоль нормали у каждой POINT. */
const val EXTRUSION_POINT = 2

/**
 * Отрезки одного слоя и цвета. [coords] — пары точек (x1 y1 z1 x2 y2 z2) в координатах файла (x — восток, y — север, z — высота).
 * [flat] — весь пакет лежит в плоскости z = 0 (2D-чертёж), его можно поднять на уровень поверхности.
 * У пакета толщины ([extrusion] ≠ [EXTRUSION_NONE]) [flat] — основание лежит на z = 0: пакет поднимается вместе
 * с чертежом, к отметке прибавляется уровень.
 */
class LineBatch(
  val layer: String, val color: Int, val flat: Boolean, val coords: DoubleArray,
  /** Толщина линий пакета, 1/100 мм; 0 — по умолчанию (тонкая). */
  val lineWeight: Int = 0,
  val extrusion: Int = EXTRUSION_NONE,
  /** XLINE и RAY: отрезки длиннее сцены, в габариты и стартовый вид камеры не входят. */
  val unbounded: Boolean = false,
  /** Номер объекта ([SceneGeometry.objects]) на каждый отрезок; null — объектов нет. */
  val objectIds: IntArray? = null,
  /** Пунктир при показе (структурные линии LandXML): штрихи режет [buildRenderScene], геометрия — сплошная. */
  val dashed: Boolean = false,
) {
  /** Число отрезков; читается только в тестах. */
  val segmentCount: Int get() = coords.size / 6
}

/**
 * Закрашенные треугольники одного слоя и цвета. [coords] — тройки точек (9 чисел на треугольник) в координатах файла (x — восток, y — север, z — высота).
 * [flat] и [extrusion] — как у [LineBatch].
 */
class FillBatch(
  val layer: String, val color: Int, val flat: Boolean, val coords: DoubleArray,
  /** Градиент: цвет 0xRRGGBB на каждую вершину ([color] — цвет первой); null — весь пакет цвета [color]. */
  val colors: IntArray? = null,
  val extrusion: Int = EXTRUSION_NONE,
  /**
   * Порядок прорисовки на каждый треугольник: номер заливки по ходу обхода чертежа (модель — в порядке
   * SORTENTSTABLE), больший рисуется поверх; null — все 0.
   */
  val orders: IntArray? = null,
  /** Непрозрачность 0…255 (код 440 / прозрачность слоя); 255 — непрозрачная заливка. */
  val alpha: Int = ALPHA_OPAQUE,
  /** Номер объекта на каждый треугольник; null — объектов нет. */
  val objectIds: IntArray? = null,
) {
  val triangleCount: Int get() = coords.size / 9
}

/** Подпись: начало базовой линии, высота букв, поворот в плоскости XY (градусы), цвет 0xRRGGBB. */
class SceneLabel(
  val layer: String, val color: Int,
  val x: Double, val y: Double, val z: Double,
  val height: Double, val rotationDeg: Double,
  val text: String,
  /** Выравнивание относительно (x, y, z), см. [DxfEntity.Text.alignH]. */
  val alignH: Int = 0, val alignV: Int = 0,
  /** Ширина и наклон букв, длина строки у «по ширине»/«вписанный» (мировая, 0 — нет), см. [DxfEntity.Text]. */
  val widthFactor: Double = 1.0, val obliqueDeg: Double = 0.0, val fitLength: Double = 0.0,
  /** Рамка MTEXT в мировых единицах: [text] — абзацы целиком, строки раскладываются по шрифту в сцене. */
  val frame: MTextFrame? = null,
  /** Фрагменты и плашка MTEXT, см. [DxfEntity.Text.runs]; ширина плашки — мировая. */
  val runs: List<DxfTextRun>? = null, val background: DxfTextBackground? = null,
  /** Замена шрифта стиля: гарнитура и поправка ширины букв (к [widthFactor] не применена). */
  val font: DxfFont = DxfFont.DEFAULT,
  /** Объект, которому принадлежит подпись ([NO_OBJECT] — никакому). */
  val objectId: Int = NO_OBJECT,
  /** Строка всегда горизонтальна на экране, при любом [rotationDeg] и наклоне камеры (подписи LandXML); у DXF — нет. */
  val screenHorizontal: Boolean = false,
  /** Подъём строки над точкой и сдвиг вдоль строки, dp — у подписей с высотой в dp ([height] < 0); у мировых не применяются. */
  val liftDp: Double = 0.0,
  val shiftDp: Double = 0.0,
  /** Засечка от точки вверх до строки (пикеты), цвет 0xRRGGBB; [NO_TICK] — нет. */
  val tickRgb: Int = NO_TICK,
)

/** Засечки под подписью нет ([SceneLabel.tickRgb]). */
const val NO_TICK = -1

/** Длина засечки под подписью, dp: строка стоит на подъёме [SceneLabel.liftDp] над ней. */
const val TICK_DP = 14f

/** Точка: [rotationDeg] — поворот значка в плоскости XY (градусы), как у [SceneLabel]. */
class PointMark(val layer: String, val color: Int, val x: Double, val y: Double, val z: Double, val rotationDeg: Double = 0.0,
  val objectId: Int = NO_OBJECT)

/** Геометрия сцены для просмотра (из любого формата): сетки поверхностей, отрезки, подписи и точки. */
class SceneGeometry(
  val meshes: List<SceneMesh>,
  val lines: List<LineBatch>,
  val fills: List<FillBatch>,
  val labels: List<SceneLabel>,
  val points: List<PointMark>,
  /** Слои: таблица LAYER + встреченные в сущностях, в порядке появления. */
  val layers: List<LayerInfo>,
  /** Раскрытие блоков остановлено на лимите примитивов. */
  val truncated: Boolean,
  /** `$PDMODE` и `$PDSIZE` чертежа: вид и размер значка точек. */
  val pointMode: Int = POINT_MODE_UNSET,
  val pointSize: Double = 0.0,
  /** `$LWDISPLAY` чертежа: открывать файл с весами линий. */
  val lineWeightDisplay: Boolean = true,
  /**
   * Предлагать «Толщину линий». У LandXML веса задаёт не файл, а мы сами (оси трасс, границы поверхностей) —
   * это оформление, выключать его незачем.
   */
  val lineWeightsOffered: Boolean = true,
  /** Предлагать «Чертёж в плоскость». */
  val flattenOffered: Boolean = true,
  /** Решать «в плоскость» самой сценой при открытии ([drawingLooksPlanar]); у LandXML отметки настоящие — по умолчанию выключено. */
  val flattenAuto: Boolean = true,
  /** Грани тел (3DSOLID, REGION, BODY), которые не показаны: цилиндры, конусы, торы, сферы, сплайны. */
  val hiddenBodyFaces: Int = 0,
  /** Тела без читаемых данных SAT (R2013+ хранит их в SAB) — не показаны. */
  val unreadBodies: Int = 0,
  /** Сохранённый вид чертежа (VPORT `*Active`), если задан. */
  val savedView: DxfSavedView? = null,
  /** Листы с содержимым по порядку вкладок; пусто — показывать нечего, кроме модели. */
  val sheets: List<DxfSheet> = emptyList(),
  /** Выбираемые объекты; на них по номеру ссылаются `objectIds` примитивов. */
  val objects: List<SceneObject> = emptyList(),
  /** Системы координат из файла (LandXML `CoordinateSystem`) — для показа как есть; пусто — не заданы. */
  val coordinateSystems: List<CoordinateSystemInfo> = emptyList(),
)
