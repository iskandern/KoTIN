package dev.iskandern.kotin.web.dom

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposeNode
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import kotlinx.browser.document
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.Text
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.MouseEvent
import org.w3c.dom.pointerevents.PointerEvent

// Элементы страницы в композиции, по образцу Compose HTML: Div(attrs = { classes(…); style { … }; onClick { … } }) { … }.
// Атрибуты собираются заново при каждой композиции элемента и накладываются разницей с прежними.

const val SVG_NS = "http://www.w3.org/2000/svg"

/** Элемент и то, что на него поставили атрибутами в прошлый раз. */
class DomElement(val element: Element) : DomNode(element) {
  internal var classes: String? = null
  internal var styles: Map<String, String> = emptyMap()
  internal var attributes: Map<String, String> = emptyMap()
  // обработчик события — один на тип: слушатель в DOM ставится один раз и зовёт последний
  internal val handlers = HashMap<String, (Event) -> Unit>()
  internal val listening = HashSet<String>()

  internal fun apply(attrs: AttrsScope) {
    val className = attrs.classes.joinToString(" ")
    if (className != classes) {
      if (className.isEmpty()) element.removeAttribute("class") else element.setAttribute("class", className)
      classes = className
    }
    if (attrs.styles != styles) {
      val style = styleOf(element)
      for (name in styles.keys) if (name !in attrs.styles) style?.removeProperty(name)
      for ((name, value) in attrs.styles) if (styles[name] != value) style?.setProperty(name, value)
      styles = attrs.styles
    }
    if (attrs.attributes != attributes) {
      for (name in attributes.keys) if (name !in attrs.attributes) element.removeAttribute(name)
      for ((name, value) in attrs.attributes) if (attributes[name] != value) element.setAttribute(name, value)
      attributes = attrs.attributes
    }
    for (type in handlers.keys.toList()) if (type !in attrs.handlers) handlers.remove(type)
    for ((type, handler) in attrs.handlers) {
      handlers[type] = handler
      if (listening.add(type)) {
        val options = attrs.passive[type]
        if (options == false) addListener(element, type, false) { event -> handlers[type]?.invoke(event) }
        else element.addEventListener(type, { event: Event -> handlers[type]?.invoke(event) })
      }
    }
  }
}

@JsFun("(element) => element.style || null")
private external fun styleOf(element: Element): org.w3c.dom.css.CSSStyleDeclaration?


@JsFun("(element, type, passive, callback) => element.addEventListener(type, callback, { passive })")
private external fun addListener(element: Element, type: String, passive: Boolean, callback: (Event) -> Unit)

/** Атрибуты элемента: классы, стиль, атрибуты, обработчики событий. */
class AttrsScope internal constructor() {
  internal val classes = ArrayList<String>(4)
  internal val styles = LinkedHashMap<String, String>()
  internal val attributes = LinkedHashMap<String, String>()
  internal val handlers = HashMap<String, (Event) -> Unit>()
  internal val passive = HashMap<String, Boolean>()

  fun classes(vararg names: String) {
    for (name in names) if (name.isNotEmpty()) classes += name
  }

  /** Класс [name], если [on]. */
  fun classIf(on: Boolean, name: String) {
    if (on) classes += name
  }

  fun style(block: StyleScope.() -> Unit) = StyleScope(styles).block()

  fun attr(name: String, value: String) {
    attributes[name] = value
  }

  fun attr(name: String, value: Boolean) {
    if (value) attributes[name] = ""
  }

  fun on(type: String, handler: (Event) -> Unit) {
    handlers[type] = handler
  }

  /** Обработчик, который может отменить действие браузера (preventDefault): не пассивный. */
  fun onActive(type: String, handler: (Event) -> Unit) {
    handlers[type] = handler
    passive[type] = false
  }

  fun onClick(handler: (MouseEvent) -> Unit) = on("click") { handler(it as MouseEvent) }
  fun onPointerDown(handler: (PointerEvent) -> Unit) = on("pointerdown") { handler(it as PointerEvent) }
  fun onKeyDown(handler: (KeyboardEvent) -> Unit) = on("keydown") { handler(it as KeyboardEvent) }
}

class StyleScope internal constructor(private val styles: MutableMap<String, String>) {
  fun property(name: String, value: String) {
    styles[name] = value
  }

  /** `--имя` для стилей страницы. */
  fun variable(name: String, value: String) {
    styles["--$name"] = value
  }
}

/** Пиксели CSS (= dp Compose: плотность — devicePixelRatio). */
val Number.px: String get() = "${this}px"

/**
 * Элемент [tag] (в пространстве имён [namespace], для SVG — [SVG_NS]) с атрибутами [attrs] и детьми [content]. [ref] —
 * после появления элемента в DOM; возвращает, что сделать при его уходе.
 */
@Composable
fun Tag(
  tag: String,
  attrs: (AttrsScope.() -> Unit)? = null,
  namespace: String? = null,
  ref: ((Element) -> (() -> Unit))? = null,
  content: (@Composable () -> Unit)? = null,
) {
  val node = remember { DomElement(if (namespace == null) document.createElement(tag) else document.createElementNS(namespace, tag)) }
  // атрибуты собираются при композиции: чтение состояния в них перекомпоновывает элемент; накладываются разницей
  val scope = AttrsScope().also { attrs?.invoke(it) }
  ComposeNode<DomElement, DomApplier>(
    factory = { node },
    update = { set(scope) { node.apply(it) } },
  ) { content?.invoke() }
  if (ref != null) {
    DisposableEffect(node) {
      val dispose = ref(node.element)
      onDispose { dispose() }
    }
  }
}

/** Готовый элемент [element] (созданный вне композиции: canvas сцены) как узел композиции. */
@Composable
fun Existing(element: Element, attrs: (AttrsScope.() -> Unit)? = null) {
  val node = remember(element) { DomElement(element) }
  val scope = AttrsScope().also { attrs?.invoke(it) }
  ComposeNode<DomElement, DomApplier>(
    factory = { node },
    update = { set(scope) { node.apply(it) } },
  )
}

@Composable
fun Text(value: String) {
  ComposeNode<DomNode, DomApplier>(
    factory = { DomNode(document.createTextNode("")) },
    update = { set(value) { (node as Text).data = it } },
  )
}

@Composable
fun Div(attrs: (AttrsScope.() -> Unit)? = null, ref: ((Element) -> (() -> Unit))? = null, content: (@Composable () -> Unit)? = null) =
  Tag("div", attrs, ref = ref, content = content)

@Composable
fun Span(attrs: (AttrsScope.() -> Unit)? = null, content: (@Composable () -> Unit)? = null) = Tag("span", attrs, content = content)

@Composable
fun Button(attrs: (AttrsScope.() -> Unit)? = null, content: (@Composable () -> Unit)? = null) =
  Tag("button", { attr("type", "button"); attrs?.invoke(this) }, content = content)

@Composable
fun Img(src: String, attrs: (AttrsScope.() -> Unit)? = null) = Tag("img", { attr("src", src); attr("alt", ""); attrs?.invoke(this) })

@Composable
fun Svg(attrs: (AttrsScope.() -> Unit)? = null, content: (@Composable () -> Unit)? = null) = Tag("svg", attrs, SVG_NS, content = content)

@Composable
fun SvgPath(d: String, attrs: (AttrsScope.() -> Unit)? = null) = Tag("path", { attr("d", d); attrs?.invoke(this) }, SVG_NS)

@Composable
fun SvgTag(
  tag: String, attrs: (AttrsScope.() -> Unit)? = null, ref: ((Element) -> (() -> Unit))? = null, content: (@Composable () -> Unit)? = null,
) = Tag(tag, attrs, SVG_NS, ref, content)
