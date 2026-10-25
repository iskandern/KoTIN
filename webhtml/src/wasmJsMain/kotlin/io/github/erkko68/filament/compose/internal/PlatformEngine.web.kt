package io.github.erkko68.filament.compose.internal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.iskandern.kotin.web.sceneCanvas
import io.github.erkko68.filament.Engine
import io.github.erkko68.filament.Filament

// движок рисует прямо в canvas сцены на странице: вид один, компоновщик видов filament-compose не нужен
@Composable
internal actual fun rememberPlatformEngine(backend: Engine.Backend): Engine =
    remember(backend) {
        Filament.init()
        Owned(checkNotNull(Engine.create(backend, sharedContext = sceneCanvas)) { "Failed to create a $backend Engine" }, emptyList()) { Engine.destroy(it) }
    }.value
