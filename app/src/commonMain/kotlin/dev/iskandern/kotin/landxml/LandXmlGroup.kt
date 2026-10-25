package dev.iskandern.kotin.landxml

/**
 * Группы списка «Элементы» (у LandXML нет слоёв — слои собирает просмотрщик): ключ [UiText] заголовка в списке.
 * Слои в сцене идут в порядке [ORDER], внутри группы — в порядке создания.
 */
object LandXmlGroup {
  const val SURFACES = "@group_surfaces"
  const val SOURCE_DATA = "@group_source_data"
  const val ALIGNMENTS = "@group_alignments"
  const val CROSS_SECTIONS = "@group_cross_sections"
  const val NETWORKS = "@group_networks"
  const val PLANIMETRY = "@group_planimetry"
  const val POINTS = "@group_points"

  val ORDER = listOf(SURFACES, SOURCE_DATA, ALIGNMENTS, CROSS_SECTIONS, NETWORKS, PLANIMETRY, POINTS)
}
