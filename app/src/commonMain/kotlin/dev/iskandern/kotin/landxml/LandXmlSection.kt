package dev.iskandern.kotin.landxml

/**
 * Раздел верхнего уровня LandXML (`Surfaces`, `CgPoints`, `Alignments`, …): читает свой элемент целиком.
 * Каждый раздел — свой класс в своём файле, в реестре [LandXmlReader.SECTIONS] — по строке.
 */
internal abstract class LandXmlSection(
  /** Локальное имя элемента — дочернего элемента корня. */
  val element: String,
  /** Имена вложенных элементов раздела: на время [read] токенизатор сверяет [XmlTokenizer.nameId] с ними. */
  val names: XmlNames,
) {
  /**
   * Вызывается на [XmlTokenizer.START] элемента раздела; возвращается на его [XmlTokenizer.END]
   * (обычно `while (xml.nextChild(depth)) …`). Разделов одного имени в файле может быть несколько.
   */
  abstract fun read(xml: XmlTokenizer, scene: LandXmlSceneBuilder)
}
