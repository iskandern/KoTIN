Часть исходников filament-compose 0.8.0 (io.github.erkko68.filament, Apache License 2.0) — только то, что работает
на Compose runtime без Compose UI: объявление сцены (`rememberFilamentScene`, материалы, камера, типы). Правки:
из `Types.kt` убран `LinearColor.fromComposeColor` (Compose UI), web-движок (`PlatformEngine.web.kt`) рисует в canvas
страницы, а не в скрытый; `FilamentView` свой — `dev.iskandern.kotin.web.FilamentCanvas`.
