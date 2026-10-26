package dev.iskandern.kotin

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement

/**
 * Имя элемента для сверки с DOM-версией (там — `data-ui` с тем же именем): в историях его рамка уходит в [UiLayout],
 * вне историй — пустой модификатор.
 */
fun Modifier.uiTag(name: String): Modifier = if (Stories.current == null) this else this then UiTagElement(name)

private class UiTagElement(val name: String) : ModifierNodeElement<UiTagNode>() {
  override fun create() = UiTagNode(name)

  override fun update(node: UiTagNode) {
    if (node.name != name) node.rename(name)
  }

  override fun equals(other: Any?) = other is UiTagElement && other.name == name

  override fun hashCode() = name.hashCode()
}

private class UiTagNode(var name: String) : Modifier.Node(), GlobalPositionAwareModifierNode {
  private var coordinates: LayoutCoordinates? = null

  // рамка без обрезки предками и с масштабом слоёв — как getBoundingClientRect у DOM
  private val bounds: () -> FloatArray? = {
    coordinates?.takeIf { it.isAttached }?.let { c ->
      val r = c.findRootCoordinates().localBoundingBoxOf(c, clipBounds = false)
      floatArrayOf(r.left, r.top, r.width, r.height)
    }
  }

  fun rename(new: String) {
    UiLayout.remove(name, bounds)
    name = new
    if (coordinates != null) UiLayout.place(name, bounds)
  }

  override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
    this.coordinates = coordinates
    UiLayout.place(name, bounds)
  }

  override fun onDetach() {
    UiLayout.remove(name, bounds)
    coordinates = null
  }
}
