package dev.iskandern.kotin.model

/** Примитив не принадлежит объекту: выбрать его нельзя. */
const val NO_OBJECT = -1

/**
 * Объект сцены, который можно выбрать: сущность DXF, поверхность или труба LandXML и т. п. Номер — индекс в
 * [SceneGeometry.objects]; примитивы (отрезки, треугольники, подписи, точки) ссылаются на него по номеру.
 *
 * Объектов может быть миллионы, поэтому поля компактные: [kind] — общая строка вида («LINE», «Pipe»), [name] — подпись
 * в списке (имя блока, текст, имя поверхности; может быть пустым), [layer] — слой, [attributes] — пары «название — значение»
 * для панели свойств (у DXF пусты — хватает вида, слоя и цвета).
 */
class SceneObject(
  val kind: String,
  val name: String,
  val layer: String,
  /** Цвет 0xRRGGBB; [NO_COLOR] — не задан. */
  val color: Int = NO_COLOR,
  val attributes: List<SceneAttribute> = emptyList(),
) {
  /**
   * Длина в плане, м, — у кривых DXF: копится при сборке по исходной ломаной, до разбиения на штрихи типа линии.
   * NaN — не считалась (у объектов LandXML длину считает выбор, см. `withProperties`).
   */
  var planLength: Double = Double.NaN
    internal set

  companion object {
    const val NO_COLOR = -1
  }
}

/** Свойство объекта для панели: название и значение уже в виде текста. */
class SceneAttribute(val name: String, val value: String)

/**
 * Номера объектов у последовательности примитивов в виде серий «с индекса — объект». Примитивы добавляются подряд,
 * и объект меняется редко, поэтому вместо массива на каждый примитив хранится только смена объекта.
 */
internal class ObjectRuns {
  private val starts = GrowingIntArray()
  private val ids = GrowingIntArray()
  private var last = NO_OBJECT

  /** Примитивы с индекса [index] принадлежат объекту [id] (до следующей отметки). */
  fun mark(index: Int, id: Int) {
    if (id == last) return
    starts.add(index)
    ids.add(id)
    last = id
  }

  /** Номер объекта на каждый из [total] примитивов; null — ни одна отметка не ставилась. */
  fun expand(total: Int): IntArray? {
    if (ids.size == 0) return null
    val result = IntArray(total) { NO_OBJECT }
    for (run in 0 until ids.size) {
      val from = starts[run]
      val to = if (run + 1 < ids.size) starts[run + 1] else total
      for (i in from until minOf(to, total)) result[i] = ids[run]
    }
    return result
  }
}
