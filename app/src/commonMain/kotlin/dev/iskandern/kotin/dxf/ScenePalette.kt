package dev.iskandern.kotin.dxf

/**
 * Общая палитра сцены (0xRRGGBB, цвет на экране): поверхности LandXML и DXF TIN и 12 цветов линий для ролей, у
 * которых в файле своего цвета нет. Закреплённые тоны (выбор, трасса, четыре сети) в таблицу поверхностей не входят.
 */
object ScenePalette {
  /** Поверхности: зелёный (рельеф), сиреневый, розовый; затем они же светлее и темнее; после 9-й — по кругу. */
  private val SURFACES = intArrayOf(0x9FB68B, 0xBC9FC6, 0xD3A0A0, 0xC5D6B4, 0xD8C5DD, 0xE6C6C4, 0x7C9668, 0x9A7DAA, 0xB17F80)

  /** Цвета линий: все с контрастом не меньше 3 : 1 к фону, [DxfColors.onBackground] их не меняет. */
  val LINES = intArrayOf(
    0xE04E1E, 0x2E8B47, 0x8A4FD0, 0xD23C4B, 0x5E7A12, 0xC0399A,
    0x9E2A14, 0x1F5F33, 0x6B2A9A, 0x8E1B3A, 0x46561E, 0x8A2E6E,
  )

  /** Цвет [index]-й поверхности: номер — только среди поверхностей, сети в счёт не идут. */
  fun surface(index: Int): Int = SURFACES[index % SURFACES.size]

  /** [index]-й цвет линий по кругу: 13-й повторяет 1-й. */
  fun line(index: Int): Int = LINES[index % LINES.size]
}
