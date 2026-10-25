package dev.iskandern.kotin

import dev.iskandern.kotin.landxml.StationLabels
import dev.iskandern.kotin.scene.MIN_LINE_WIDTH_PX
import dev.iskandern.kotin.scene.RenderScene

/**
 * Какие переключатели показа имеют смысл для сцены: нет подписей — нет и галочки «Подписи», нет поверхностей — нет
 * «Поверхности» и «Каркаса». Лишняя галочка ничего не делает и только мешает искать нужные.
 */
class AvailableControls(scene: RenderScene) {
  /** Рельеф (TIN, сетки): «Поверхность» и «Каркас». */
  val surfaces = scene.surfaces.any { !it.structure }

  /** Колодцы и трубы LandXML — рукотворные сетки: своя галочка «Трубы», к «Поверхности» не относятся. */
  val structures = scene.surfaces.any { it.structure }

  /** Отрезки чертежа и заливки: их показывает «Чертёж». Толщина точек (отрезки вдоль нормали) сюда не входит. */
  val drawing = scene.lines.any { !it.pointThickness } || scene.fills.isNotEmpty()
  val labels = scene.labels.isNotEmpty()

  /** Толщина точек показывается вместе с самими точками. */
  val points = scene.points.isNotEmpty() || scene.lines.any { it.pointThickness }
  val pointThickness = scene.lines.any { it.pointThickness }

  /**
   * Толщина линий: хоть у одного пакета линия толще минимальной — и толщина задана файлом. У LandXML она наша
   * (оси трасс, границы поверхностей), переключатель там только портит оформление.
   */
  val lineWeights = scene.lineWeightsOffered && scene.lines.any { it.widthPx > MIN_LINE_WIDTH_PX }

  /** Масштаб по высоте: у плоской сцены (план, лист) высота ноль, и чипы 1/2/5/10× ничего не меняют. */
  val verticalScale = scene.halfY > maxOf(scene.halfX, scene.halfZ) * 2 * HEIGHT_NOISE

  /**
   * Подписи и точки прячутся за поверхностью — только если есть и то, и другое, а у поверхности есть рельеф: плоская
   * (разброс высот ≲ 1 % размера в плане) ничего не закрывает.
   */
  val occlusion = surfaces && (labels || points) && hasRelief(scene)

  /** Пикетаж трасс LandXML: отдельная галочка вместо слоя в списке; в DXF слоя нет. */
  val stationing = scene.layerInfos.any { it.name == StationLabels.LAYER }

  /** Промежуточные пикеты между концами трасс: галочка под «Пикетажем». */
  val midStations = scene.layerInfos.any { it.name == StationLabels.MID_LAYER }

  /** Указатели на далёкие объекты: только у сцены, где они есть. */
  val outliers = scene.home?.outliers.orEmpty().isNotEmpty()

  /** «Чертёж в плоскость» нужен, только если он что-то меняет: есть толщина или отметки, отличные от уровня поверхности. */
  val flattenDrawing = scene.flattenChangesScene
}

private const val RELIEF_RATIO = 0.01f

/** Полувысота сцены в пределах этой доли наибольшего размера в плане — шум вычислений, сцена плоская. */
private const val HEIGHT_NOISE = 1e-5f

private fun hasRelief(scene: RenderScene): Boolean {
  var low = Float.POSITIVE_INFINITY
  var high = Float.NEGATIVE_INFINITY
  for (surface in scene.surfaces) {
    if (surface.structure) continue
    val p = surface.positions
    for (i in 1 until p.size step 3) {
      if (p[i] < low) low = p[i]
      if (p[i] > high) high = p[i]
    }
  }
  return high - low > maxOf(scene.halfX, scene.halfZ) * 2 * RELIEF_RATIO
}
