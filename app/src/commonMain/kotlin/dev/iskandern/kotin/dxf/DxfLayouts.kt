package dev.iskandern.kotin.dxf

import dev.iskandern.kotin.model.MAX_SCENE_SEGMENTS
import dev.iskandern.kotin.model.SceneGeometry
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Лист (объект LAYOUT) с содержимым своего пространства: сущности (ENTITIES с кодом 67 = 1 у активного листа,
 * блок `*Paper_Space…` у остальных) и видовые экраны.
 * Бумага (AcDbPlotSettings): размер [paperWidth] × [paperHeight] в мм (44/45), поля слева, снизу, справа, сверху
 * (40–43, мм), смещение печати (46/47), поворот печати (73: 0…3 по 90°), единицы (72: 0 — дюймы, 1 — мм),
 * масштаб печати [scalePaper] : [scaleDrawing] (142/143). Лист (AcDbLayout): имя (1), порядок вкладки (71),
 * границы листа 10/20 – 11/21, запись блока своего пространства (330).
 */
class DxfLayout(
  val name: String,
  val tabOrder: Int,
  val paperWidth: Double = 0.0,
  val paperHeight: Double = 0.0,
  val margins: DoubleArray = DoubleArray(4),
  val plotOriginX: Double = 0.0,
  val plotOriginY: Double = 0.0,
  val plotRotation: Int = 0,
  val inches: Boolean = false,
  val scalePaper: Double = 1.0,
  val scaleDrawing: Double = 1.0,
  /** Границы листа: min x, min y, max x, max y; null — не заданы. */
  val limits: DoubleArray? = null,
  /** handle записи блока пространства листа; 0 — неизвестен. */
  val blockRecord: Long = 0L,
) {
  var entities: List<DxfEntity> = emptyList()
    internal set
  var viewports: List<DxfViewport> = emptyList()
    internal set

  /**
   * Окно всего листа: номер 1, а если номеров в файле нет (у всех 0, а у экспорта через ODA −1) — первое окно листа,
   * как его пишут AutoCAD и ODA. null — окон нет.
   */
  val paperViewport: DxfViewport?
    get() = viewports.firstOrNull { it.id == PAPER_VIEWPORT_ID } ?: viewports.firstOrNull()?.takeIf { it.id <= 0 }

  /** На листе есть что показать: сущности или видовые экраны, кроме окна всего листа. */
  val hasContent: Boolean get() = entities.isNotEmpty() || viewports.any { it !== paperViewport }

  /**
   * Прямоугольник бумаги в единицах листа: min x, min y, max x, max y; null — размер не задан.
   * Границы листа берутся, если совпадают с размером бумаги; иначе начало листа — угол области печати
   * (бумага сдвинута на поля и смещение печати).
   */
  fun paperBounds(): DoubleArray? {
    val unit = (if (inches) 1.0 / MM_PER_INCH else 1.0) *
      (if (scalePaper > 0 && scaleDrawing > 0) scaleDrawing / scalePaper else 1.0)
    val turned = plotRotation % 2 == 1
    val w = (if (turned) paperHeight else paperWidth) * unit
    val h = (if (turned) paperWidth else paperHeight) * unit
    val lim = limits?.takeIf { it[2] > it[0] && it[3] > it[1] }
    if (w > 0 && h > 0) {
      if (lim != null && abs(lim[2] - lim[0] - w) <= w * SIZE_TOLERANCE && abs(lim[3] - lim[1] - h) <= h * SIZE_TOLERANCE) return lim
      // у повёрнутой печати поля сдвигаются вместе с бумагой: слева — нижнее, снизу — левое
      val left = (if (turned) margins[1] else margins[0]) + plotOriginX
      val bottom = (if (turned) margins[0] else margins[1]) + plotOriginY
      val x0 = 0.0 - left * unit
      val y0 = 0.0 - bottom * unit
      return doubleArrayOf(x0, y0, x0 + w, y0 + h)
    }
    return lim
  }

  /**
   * Миллиметров в единице листа: дюймы (72 = 0) или мм. Масштаб печати 142/143 сюда не входит: масштаб окна —
   * свойство листа в его единицах, как его показывает AutoCAD, а не напечатанного листа («вписать» даёт дробный
   * масштаб печати, и подпись 1:96 стала бы «1:44.76»).
   */
  val paperUnitMm: Double
    get() = if (inches) MM_PER_INCH else 1.0

  companion object {
    /** Номер видового экрана, который показывает весь лист: его рамка не рисуется. */
    const val PAPER_VIEWPORT_ID = 1
    internal const val MM_PER_INCH = 25.4
    private const val SIZE_TOLERANCE = 0.01
  }
}

/**
 * Видовой экран листа (VIEWPORT): центр окна на листе 10/20/30, ширина и высота 40/41, номер 69; вид модели —
 * центр 12/22, цель 17/27/37, направление 16/26/36, высота вида 45, поворот 51 (градусы); замороженные
 * в окне слои 331 (handle записей LAYER в верхнем регистре); состояние 68 — место окна на экране активного листа
 * (у окон остальных листов AutoCAD пишет 0, это не «выключено»); флаги 90 ([FLAG_OFF] — окно выключено, MVIEW OFF).
 * Модель рисуется в окне только при виде сверху ([showsModel]), см. [sheetWithViewports].
 */
class DxfViewport(
  val centerX: Double, val centerY: Double, val centerZ: Double,
  val width: Double, val height: Double,
  val id: Int,
  val viewCenterX: Double, val viewCenterY: Double,
  val targetX: Double, val targetY: Double, val targetZ: Double,
  val directionX: Double, val directionY: Double, val directionZ: Double,
  val viewHeight: Double,
  val twistDeg: Double,
  val frozenLayers: List<String>,
  val status: Int = 1,
  val flags: Int = 0,
) {
  val common = DxfCommon()

  /** Имена слоёв, замороженных в окне ([frozenLayers] через таблицу LAYER). */
  var frozenLayerNames: Set<String> = emptySet()
    internal set

  /** Окно выключено (бит [FLAG_OFF] кода 90): рамка остаётся, модели в нём нет. */
  val off: Boolean get() = flags and FLAG_OFF != 0

  /** Окно включено, размеры заданы и вид — сверху: модель в нём рисуется. */
  val showsModel: Boolean
    get() {
      if (off || !(width > 0) || !(height > 0) || !(viewHeight > 0)) return false
      val len = sqrt(directionX * directionX + directionY * directionY + directionZ * directionZ)
      return directionZ > 0 && abs(directionX) < len / 64 && abs(directionY) < len / 64
    }

  /** Единиц модели на единицу листа (высота вида / высота окна); 0 — не задан. */
  val scale: Double get() = if (height > 0 && viewHeight > 0) viewHeight / height else 0.0

  companion object {
    /** Бит кода 90: окно выключено. */
    const val FLAG_OFF = 0x20000
  }
}

/**
 * Лист, раскрытый в геометрию: показывается вместо модели по переключателю в панели. [geometry] — бумага, рамки
 * и сущности листа; модель в окнах [windows] добавляет [withModel] — при выборе листа, а не при открытии файла.
 */
class DxfSheet(val name: String, val geometry: SceneGeometry, val windows: List<SheetWindow> = emptyList()) {
  private var cachedModel: SceneGeometry? = null
  private var cached: SceneGeometry? = null

  /** Лист с моделью [model] в окнах; повторный вызов с той же моделью — без пересчёта. */
  fun withModel(model: SceneGeometry, maxSegments: Int = MAX_SCENE_SEGMENTS): SceneGeometry {
    if (windows.isEmpty()) return geometry
    cached?.let { if (cachedModel === model) return it }
    return sheetWithViewports(geometry, model, windows, maxSegments).also {
      cachedModel = model
      cached = it
    }
  }
}

/**
 * Масштаб окна: единиц модели на единицу бумаги, высота вида переведена из `$INSUNITS` [modelUnits] в миллиметры,
 * высота окна — из единиц листа; единицы модели не заданы — как [DxfViewport.scale].
 */
fun viewportScale(viewport: DxfViewport, layout: DxfLayout, modelUnits: Int): Double {
  val mm = insUnitsMm(modelUnits)
  return if (mm > 0) viewport.scale * mm / layout.paperUnitMm else viewport.scale
}

/** Миллиметров в единице `$INSUNITS`; 0 — единицы не заданы или неизвестны. */
fun insUnitsMm(units: Int): Double = when (units) {
  1 -> DxfLayout.MM_PER_INCH
  2 -> 304.8
  3 -> 1_609_344.0
  4 -> 1.0
  5 -> 10.0
  6 -> 1000.0
  7 -> 1_000_000.0
  8 -> 2.54e-5
  9 -> 0.0254
  10 -> 914.4
  11 -> 1e-7
  12 -> 1e-6
  13 -> 1e-3
  14 -> 100.0
  15 -> 10_000.0
  16 -> 100_000.0
  // футы США
  21 -> 1_200_000.0 / 3937.0
  else -> 0.0
}

/** Подпись масштаба: 1:500 при уменьшении, 2:1 при увеличении; пустая — масштаб не задан. */
fun formatViewportScale(scale: Double): String {
  if (!(scale > 0) || scale.isInfinite()) return ""
  return if (scale >= 1) "1:${formatScaleNumber(scale)}" else "${formatScaleNumber(1 / scale)}:1"
}

// целое — без дробной части, иначе до двух знаков без хвостовых нулей
private fun formatScaleNumber(value: Double): String {
  val rounded = value.roundToLong()
  if (abs(value - rounded) <= max(1e-6, value * 1e-4)) return rounded.toString()
  val hundredths = (value * 100).roundToLong()
  val fraction = (hundredths % 100).toString().padStart(2, '0').trimEnd('0')
  return if (fraction.isEmpty()) "${hundredths / 100}" else "${hundredths / 100}.$fraction"
}

/**
 * Документ для раскрытия листа: сущности листа, рамка бумаги, рамки видовых экранов (кроме окна всего листа)
 * с подписью масштаба. Блоки, слои, стили — общие с моделью; граней модели и сохранённого вида нет.
 * Модель в окна переносит [sheetWithViewports].
 */
internal fun DxfDocument.sheetDocument(layout: DxfLayout): DxfDocument {
  val entities = ArrayList<DxfEntity>(layout.entities.size + layout.viewports.size * 2 + 1)
  layout.paperBounds()?.let { b ->
    entities.add(rectangle(b[0], b[1], b[2], b[3]).also { it.common.color = PAPER_COLOR })
  }
  entities.addAll(layout.entities)
  val paperViewport = layout.paperViewport
  for (viewport in layout.viewports) {
    if (viewport === paperViewport || !(viewport.width > 0) || !(viewport.height > 0)) continue
    val x0 = viewport.centerX - viewport.width / 2
    val y0 = viewport.centerY - viewport.height / 2
    entities.add(
      rectangle(x0, y0, x0 + viewport.width, y0 + viewport.height).also { it.common.copyFrom(viewport.common) },
    )
    val label = formatViewportScale(viewportScale(viewport, layout, insUnits))
    if (label.isEmpty()) continue
    // подпись в левом нижнем углу окна, высота — доля меньшей стороны
    val size = min(viewport.width, viewport.height)
    val textHeight = size * SCALE_LABEL_HEIGHT
    val pad = size * SCALE_LABEL_PAD
    entities.add(
      DxfEntity.Text(x0 + pad, y0 + pad, viewport.centerZ, textHeight, 0.0, label, inOcs = false)
        .also { it.common.copyFrom(viewport.common) },
    )
  }
  return DxfDocument(
    layers, entities, blocks, faceMeshes = emptyMap(), layerOrder = emptyList(), pointMode, pointSize, lineTypes,
    lineTypeScale, textStyles, objects, savedView = null, textStylesByHandle, wipeoutFrame, lineWeightDisplay,
    insUnits = insUnits,
  )
}

private fun rectangle(x0: Double, y0: Double, x1: Double, y1: Double) = DxfEntity.Polyline(
  doubleArrayOf(x0, y0, 0.0, x1, y0, 0.0, x1, y1, 0.0, x0, y1, 0.0), DoubleArray(4), closed = true, inOcs = false,
)

// рамка бумаги — серый ACI 9, видна на тёмном фоне и не спорит с чертежом
private const val PAPER_COLOR = 9
private const val SCALE_LABEL_HEIGHT = 0.05
private const val SCALE_LABEL_PAD = 0.03
