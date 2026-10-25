package dev.iskandern.kotin.web.dom

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composition
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.w3c.dom.Element
import org.w3c.dom.Node
import kotlin.coroutines.CoroutineContext

// Compose runtime над DOM — то же, что делает Compose HTML (renderComposable), у которого нет сборки под wasm:
// узлы композиции — элементы страницы, кадры — requestAnimationFrame, изменения снимков — микрозадачей.

/** Узел композиции: DOM-узел; у элемента — ещё и то, что на него поставили атрибутами ([DomElement]). */
open class DomNode(val node: Node)

class DomApplier(root: DomNode) : AbstractApplier<DomNode>(root) {
  override fun insertTopDown(index: Int, instance: DomNode) {}

  override fun insertBottomUp(index: Int, instance: DomNode) {
    val parent = current.node
    parent.insertBefore(instance.node, parent.childNodes.item(index))
  }

  override fun remove(index: Int, count: Int) {
    val parent = current.node
    repeat(count) { parent.removeChild(parent.childNodes.item(index)!!) }
  }

  override fun move(from: Int, to: Int, count: Int) {
    val parent = current.node
    if (from > to) {
      var next = to
      for (i in from until from + count) {
        val node = parent.childNodes.item(i)!!
        parent.removeChild(node)
        parent.insertBefore(node, parent.childNodes.item(next))
        next++
      }
    } else {
      repeat(count) {
        val node = parent.childNodes.item(from)!!
        parent.removeChild(node)
        parent.insertBefore(node, parent.childNodes.item(to - 1))
      }
    }
  }

  override fun onClear() {
    val parent = root.node
    while (parent.firstChild != null) parent.removeChild(parent.firstChild!!)
  }
}

@JsFun("(callback) => queueMicrotask(callback)")
private external fun queueMicrotask(callback: () -> Unit)

@JsFun("(callback) => requestAnimationFrame(callback)")
private external fun requestAnimationFrame(callback: (Double) -> Unit): Int

/** Диспетчер микрозадач: корутины композиции продолжаются в том же такте событий, без setTimeout. */
private object MicrotaskDispatcher : CoroutineDispatcher() {
  override fun dispatch(context: CoroutineContext, block: Runnable) = queueMicrotask { block.run() }
}

/** Кадр — requestAnimationFrame; время кадра — его отметка (мс от загрузки страницы) в наносекундах. */
private object AnimationFrameClock : MonotonicFrameClock {
  override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R = suspendCancellableCoroutine { continuation ->
    requestAnimationFrame { time ->
      val result = runCatching { onFrame((time * 1_000_000).toLong()) }
      continuation.resumeWith(result)
    }
  }
}

private var applyScheduled = false

// запись в снимок вне композиции (обработчик события, корутина) — уведомление о ней микрозадачей, одно на пачку
private val snapshotObserver by lazy {
  Snapshot.registerGlobalWriteObserver {
    if (!applyScheduled) {
      applyScheduled = true
      queueMicrotask {
        applyScheduled = false
        Snapshot.sendApplyNotifications()
      }
    }
  }
}

/** Показывает [content] в элементе [root]; его прежние дети убираются. */
fun renderComposable(root: Element, content: @androidx.compose.runtime.Composable () -> Unit): Composition {
  snapshotObserver
  val context = MicrotaskDispatcher + AnimationFrameClock
  val recomposer = Recomposer(context)
  CoroutineScope(context).launch(start = CoroutineStart.UNDISPATCHED) { recomposer.runRecomposeAndApplyChanges() }
  while (root.firstChild != null) root.removeChild(root.firstChild!!)
  val composition = Composition(DomApplier(DomNode(root)), recomposer)
  composition.setContent(content)
  return composition
}
