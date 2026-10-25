package dev.iskandern.kotin.model

import dev.iskandern.kotin.dxf.LINE_WEIGHT_DEFAULT

/**
 * Слой сцены (у DXF — из таблицы LAYER, у LandXML — заводит читатель). [color] < 0 — слой выключен, [flags] бит 1 — заморожен, [trueColor] — код 420 (0xRRGGBB),
 * [lineType] — имя типа линии (код 6).
 */
class LayerInfo(
  val name: String, val color: Int, val flags: Int, val trueColor: Int?,
  val lineType: String = "CONTINUOUS",
  /** Толщина линий слоя (код 370), 1/100 мм; отрицательная — по умолчанию. */
  val lineWeight: Int = LINE_WEIGHT_DEFAULT,
  /** Непрозрачность слоя 0…255 (XDATA `AcCmTransparency`, код 1071); 255 — непрозрачный. */
  val alpha: Int = ALPHA_OPAQUE,
  /** Группа в списке слоёв (заголовок); у DXF пустая — список без групп. Заполняет LandXML. */
  val group: String = "",
) {
  val isVisible: Boolean get() = color >= 0 && flags and FROZEN_FLAG == 0

  private companion object {
    const val FROZEN_FLAG = 1
  }
}
