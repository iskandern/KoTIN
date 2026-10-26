package dev.iskandern.kotin

import kotlin.time.TimeSource

@JsFun("() => new URLSearchParams(window.location.search).get('story')")
private external fun storyParameter(): String?

@JsFun("() => new URLSearchParams(window.location.search).get('scene')")
private external fun sceneParameter(): String?

/**
 * История из адреса страницы (`?story=<имя>`, `&scene=0` — без сцены Filament): ставит [Stories.current] до первой
 * композиции; null — обычный запуск.
 */
fun storyFromUrl(): Story? {
  val name = storyParameter() ?: return null
  val story = Stories.named(name) ?: run {
    println("KoTIN: no story '$name', stories: ${Stories.all.joinToString { it.name }}")
    return null
  }
  Stories.current = story
  Stories.noScene = sceneParameter() == "0"
  return story
}

/** Доводит [viewModel] до состояния истории [story], файл — по адресу рядом со страницей. */
suspend fun runStory(story: Story, viewModel: ViewerViewModel) = Stories.run(story, viewModel, ::urlDrawingSource)

@JsFun("(layout, idle) => { window.kotinLayout = () => JSON.parse(layout()); window.kotinIdle = () => idle(); }")
private external fun exportProbe(layout: () -> String, idle: () -> Boolean)

@JsFun("() => window.innerWidth")
private external fun innerWidth(): Int

@JsFun("() => window.innerHeight")
private external fun innerHeight(): Int

@JsFun("() => window.devicePixelRatio")
private external fun devicePixelRatio(): Double

/** Сколько рамки должны стоять, чтобы анимации считались законченными, мс. */
const val SETTLE_MS = 300L

/**
 * `window.kotinLayout()` — рамки элементов [UiLayout] (`{ имя: {x, y, w, h, visible} }`, px CSS) и `window.kotinIdle()` —
 * история готова и рамки не меняются [SETTLE_MS]: анимации кончились. Для Compose-версии; DOM-версия отдаёт свои.
 */
fun exportComposeLayout() {
  UiLayout.density = devicePixelRatio().toFloat()
  var previous = ""
  var stableSince = TimeSource.Monotonic.markNow()
  val layout = {
    val w = innerWidth()
    val h = innerHeight()
    UiLayout.snapshot().entries.joinToString(",", "{", "}") { (name, b) ->
      val visible = b[2] > 0f && b[3] > 0f && b[0] < w && b[1] < h && b[0] + b[2] > 0f && b[1] + b[3] > 0f
      "\"$name\":{\"x\":${b[0]},\"y\":${b[1]},\"w\":${b[2]},\"h\":${b[3]},\"visible\":$visible}"
    }
  }
  exportProbe(layout) {
    val now = layout()
    if (now != previous) {
      previous = now
      stableSince = TimeSource.Monotonic.markNow()
    }
    storySettled() && stableSince.elapsedNow().inWholeMilliseconds > SETTLE_MS &&
      UiLayout.changedAt.elapsedNow().inWholeMilliseconds > SETTLE_MS
  }
}

/** История дошла до своего состояния не меньше [SETTLE_MS] назад. */
fun storySettled(): Boolean = (Stories.readyAt?.elapsedNow()?.inWholeMilliseconds ?: -1) > SETTLE_MS
