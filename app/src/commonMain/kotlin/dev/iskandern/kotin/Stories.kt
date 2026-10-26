package dev.iskandern.kotin

import dev.iskandern.kotin.scene.RenderScene
import kotlinx.coroutines.flow.first
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * История — фиксированное состояние экрана для сверки Compose- и DOM-версий (`?story=<имя>`, docs/ui-sync-plan.md).
 * Состояние строится обычным путём через [ViewerViewModel]: открыть [file], затем [steps]. Что живёт только в
 * интерфейсе (карточка развёрнута, список кандидатов раскрыт, подсказка о выборе скрыта), — флагами, их читают оба
 * экрана при создании этого состояния. [phone] — какие размеры окна снимать: null — оба, true — только телефон.
 */
class Story(
  val name: String,
  val file: String? = STORY_FILE,
  val hintHidden: Boolean = true,
  val expanded: Boolean = false,
  val candidatesOpen: Boolean = false,
  val phone: Boolean? = null,
  val steps: suspend (ViewerViewModel, RenderScene) -> Unit = { _, _ -> },
)

/** Файлы историй — рядом со страницей обеих web-сборок, их создаёт scripts/gen_story_files.py. */
private const val STORY_FILE = "stories/story.xml"

object Stories {
  val all = listOf(
    Story("empty", file = null, hintHidden = false),
    Story("loaded"),
    Story("menu") { viewModel, _ -> viewModel.setMenuOpen(true) },
    Story("card", phone = false) { viewModel, scene -> viewModel.selectNamed(scene, "Ось 1") },
    Story("card-expanded", expanded = true, phone = false) { viewModel, scene -> viewModel.selectNamed(scene, "Ось 1") },
    Story("candidates") { viewModel, scene -> viewModel.selectNamed(scene, "Т1", "Т2", "Т3", "Ось 1") },
    Story("candidates-list", candidatesOpen = true) { viewModel, scene -> viewModel.selectNamed(scene, "Т1", "Т2", "Т3", "Ось 1") },
    Story("sheet", phone = true) { viewModel, scene -> viewModel.selectNamed(scene, "Ось 1") },
    Story("sheet-expanded", expanded = true, phone = true) { viewModel, scene -> viewModel.selectNamed(scene, "Ось 1") },
    Story("hint", hintHidden = false),
    // замер кадров вышел медленным — подписи на ступень легче, и сцена «нарисована»: плашка о подписях
    Story("labels-notice") { viewModel, scene ->
      viewModel.onFramesMeasured(1_000.0)
      viewModel.onSceneShown(scene)
    },
    Story("load-error", file = "stories/broken.xml"),
  )

  fun named(name: String): Story? = all.firstOrNull { it.name == name }

  /** Открытая история; null — обычный запуск. */
  var current: Story? = null

  /** Без сцены Filament (`&scene=0`): ровный фон — история открывается за секунды и одинаково в обеих сборках. */
  var noScene = false

  /** Когда история дошла до своего состояния; null — ещё нет. */
  var readyAt: TimeMark? = null
    private set

  /** Доводит [viewModel] до состояния [story]; [source] — файл истории по адресу рядом со страницей. */
  suspend fun run(story: Story, viewModel: ViewerViewModel, source: (String) -> DrawingSource) {
    if (story.file != null) {
      viewModel.open(source(story.file))
      val loaded = viewModel.state.first { it.loadState !is LoadState.Loading }.loadState
      if (loaded is LoadState.Loaded) story.steps(viewModel, loaded.scene)
    }
    readyAt = TimeSource.Monotonic.markNow()
  }
}

/** Выбор объектов [names] показанной сцены как кандидатов одного нажатия, первый выбран; ждёт, пока выбор готов. */
private suspend fun ViewerViewModel.selectNamed(scene: RenderScene, vararg names: String) {
  val ids = names.mapNotNull { name -> scene.objects.indexOfFirst { it.name == name }.takeIf { it >= 0 } }
  if (ids.size < names.size) println("KoTIN story: objects ${names.toList()} not all found in ${scene.objects.map { it.name }}")
  selectObjects(ids)
  state.first { it.selection != null }
}

/**
 * Рамки размеченных элементов интерфейса в историях (`Modifier.uiTag` app; у DOM-версии — атрибут `data-ui`): имя →
 * рамка в px CSS. Вне историй пуст — разметка ничего не стоит.
 */
object UiLayout {
  private val probes = LinkedHashMap<String, () -> FloatArray?>()
  private val last = HashMap<String, FloatArray>()

  /** px на px CSS. */
  var density = 1f

  /** Когда рамка какого-то элемента менялась последний раз: пока меняется — анимация не кончилась. */
  var changedAt: TimeMark = TimeSource.Monotonic.markNow()
    private set

  /** Элемент [name] на месте; [bounds] — его рамка сейчас (x, y, ширина, высота в px), null — его нет. */
  fun place(name: String, bounds: () -> FloatArray?) {
    probes[name] = bounds
    val now = bounds()
    if (now == null || !now.contentEquals(last[name])) {
      changedAt = TimeSource.Monotonic.markNow()
      if (now != null) last[name] = now else last.remove(name)
    }
  }

  /** Элемент [name] ушёл из интерфейса (если его место не занял другой с тем же именем). */
  fun remove(name: String, bounds: () -> FloatArray?) {
    if (probes[name] !== bounds) return
    probes.remove(name)
    last.remove(name)
    changedAt = TimeSource.Monotonic.markNow()
  }

  /** Рамки всех элементов в px CSS: имя → (x, y, ширина, высота). */
  fun snapshot(): Map<String, FloatArray> = probes.mapNotNull { (name, probe) ->
    probe()?.let { b -> name to FloatArray(4) { b[it] / density } }
  }.toMap()
}
