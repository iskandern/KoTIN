package dev.iskandern.kotin.landxml

import dev.iskandern.kotin.model.GrowingDoubleArray
import dev.iskandern.kotin.model.GrowingIntArray

/**
 * Оси LandXML: в тексте точка — «север восток [отметка]», в сцене X — восток (второе число), Y — север (первое),
 * Z — отметка. Перестановка X/Y в интерфейсе (`swapXY`) — отдельно, поверх этой.
 *
 * Порядок обхода граней: экспортёры пишут `F` против часовой стрелки в плане «восток — север», то есть уже в осях
 * сцены, поэтому вершины берутся как есть. Проверено на корпусе (Civil 3D 2014–2024, 3D-Office, ~4000 граней):
 * с перестановкой вершин 2 и 3 нормали всех граней смотрят вниз, без неё — вверх.
 */
internal object LandXmlCoord {

  /**
   * Точка из текста текущего элемента (`P`, `Start`, `Center`, `CgPoint`…) в [out] с [at]: x, y, z.
   * Возвращает число прочитанных чисел: 0 — текста нет, 2 — без отметки (z = 0), 3 — с отметкой.
   */
  fun readPoint(xml: XmlTokenizer, out: DoubleArray, at: Int = 0): Int {
    if (!xml.nextNumber()) return 0
    val north = xml.double()
    if (!xml.nextNumber()) throw XmlFormatException("@xml_point_one_number", xml.bytesRead)
    out[at] = xml.double()
    out[at + 1] = north
    if (!xml.nextNumber()) {
      out[at + 2] = 0.0
      return 2
    }
    out[at + 2] = xml.double()
    return 3
  }

  /**
   * Список точек (`PntList2D` — [dimensions] = 2, `PntList3D` — 3) в [out] тройками x, y, z; у 2D z = 0.
   * Возвращает число точек; лишние числа в конце (неполная точка) отбрасываются.
   */
  fun readList(xml: XmlTokenizer, dimensions: Int, out: GrowingDoubleArray): Int {
    var count = 0
    while (xml.nextNumber()) {
      val north = xml.double()
      if (!xml.nextNumber()) break
      val east = xml.double()
      var z = 0.0
      if (dimensions == 3) {
        if (!xml.nextNumber()) break
        z = xml.double()
      }
      out.add(east)
      out.add(north)
      out.add(z)
      count++
    }
    return count
  }

  /** Треугольник файла (вершины [a], [b], [c]) в индексы сцены — обход как в файле, нормаль вверх. */
  fun addTriangle(triangles: GrowingIntArray, a: Int, b: Int, c: Int) {
    triangles.add(a)
    triangles.add(b)
    triangles.add(c)
  }
}
