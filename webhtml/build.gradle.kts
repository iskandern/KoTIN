import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeCompiler)
}

// Web без Compose UI и Skia: интерфейс — DOM через Compose runtime (как Compose HTML, у которого нет сборки под
// wasm), сцена — тот же Filament. Логика, чтение файлов и сцена — исходники :app, кроме экранов на Compose UI.
val appSrc = "../app/src"
val composeResources = "$appSrc/commonMain/composeResources"

// Экраны и всё, что держится на Compose UI (Modifier, material3, шрифты Compose, Compose Resources)
val appUiFiles = listOf(
    "AxisIndicator", "Brand", "CameraButtons", "CameraGestures", "CoordinateSystemView", "DrawingPointer", "FileCard",
    "Fonts", "KotinIcons", "PhoneLayout", "SceneView", "SelectionCard", "Theme", "UiTag", "ViewerMenu", "ViewerScreen",
).map { "dev/iskandern/kotin/$it.kt" } + "dev/iskandern/kotin/scene/GlyphAtlasRaster.kt"
val appWebUiFiles = listOf("Main", "Fonts.wasmJs").map { "dev/iskandern/kotin/$it.kt" }

kotlin {
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            binaries.executable()
            commonWebpackConfig {
                outputFileName = "kotin.js"
            }
        }
    }

    sourceSets {
        // При синхронизации IDE исходники app не подключаем — см. drawingworker
        val ideSync = System.getProperty("idea.sync.active") == "true"
        commonMain {
            if (!ideSync) {
                kotlin.srcDir("$appSrc/commonMain/kotlin")
                kotlin.exclude(appUiFiles)
            }
            kotlin.srcDir(layout.buildDirectory.dir("generated/strings"))
            dependencies {
                implementation(libs.compose.runtime)
                implementation(libs.compose.ui.geometry)
                implementation(libs.compose.ui.unit)
                implementation(libs.filament)
                implementation(libs.utils)
                implementation(libs.androidx.lifecycle.viewmodel)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        wasmJsMain {
            if (!ideSync) {
                kotlin.srcDir("$appSrc/wasmJsMain/kotlin")
                kotlin.exclude(appWebUiFiles)
            }
            dependencies {
                implementation(libs.kotlinx.browser)
            }
        }
    }
}

/**
 * Строки из composeResources app (ru — values, en — values-en) — в код: `Res.string.*` и `Res.plurals.*`, как у
 * Compose Resources, но без загрузки по сети при старте.
 */
val generateStrings by tasks.registering {
    val sources = listOf(file("$composeResources/values/strings.xml"), file("$composeResources/values-en/strings.xml"))
    val out = layout.buildDirectory.dir("generated/strings")
    inputs.files(sources)
    outputs.dir(out)
    doLast {
        fun read(file: File): Pair<Map<String, String>, Map<String, Map<String, String>>> {
            val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
            val strings = LinkedHashMap<String, String>()
            val plurals = LinkedHashMap<String, Map<String, String>>()
            val nodes = root.childNodes
            for (i in 0 until nodes.length) {
                val node = nodes.item(i) as? Element ?: continue
                when (node.tagName) {
                    "string" -> strings[node.getAttribute("name")] = node.textContent
                    "plurals" -> {
                        val items = node.getElementsByTagName("item")
                        plurals[node.getAttribute("name")] = (0 until items.length).associate {
                            val item = items.item(it) as Element
                            item.getAttribute("quantity") to item.textContent
                        }
                    }
                }
            }
            return strings to plurals
        }
        // экранирование Android-ресурсов (\n, \', \") — как у Compose Resources
        fun unescape(s: String) = s.replace("\\n", "\n").replace("\\t", "\t").replace("\\'", "'").replace("\\\"", "\"").replace("\\@", "@")
        fun literal(s: String) = "\"" + unescape(s).flatMap { c ->
            when (c) {
                '\\' -> "\\\\".toList(); '"' -> "\\\"".toList(); '\n' -> "\\n".toList(); '\t' -> "\\t".toList(); '$' -> "\\$".toList()
                else -> listOf(c)
            }
        }.joinToString("") + "\""
        val (ruStrings, ruPlurals) = read(sources[0])
        val (enStrings, enPlurals) = read(sources[1])
        val keys = ruStrings.keys.toList()
        val pluralKeys = ruPlurals.keys.toList()
        val quantities = listOf("zero", "one", "two", "few", "many", "other")
        val text = buildString {
            appendLine("// Создан задачей :webhtml:generateStrings из composeResources app — не править")
            appendLine("package dev.iskandern.kotin.resources")
            appendLine()
            appendLine("import org.jetbrains.compose.resources.PluralStringResource")
            appendLine("import org.jetbrains.compose.resources.StringResource")
            appendLine()
            appendLine("object Res {")
            appendLine("  object string")
            appendLine("  object plurals")
            appendLine()
            appendLine("  /** Файл из composeResources app (`files/…`, `font/…`), рядом со страницей. */")
            appendLine("  suspend fun readBytes(path: String): ByteArray = org.jetbrains.compose.resources.readResourceBytes(path)")
            appendLine("}")
            appendLine()
            keys.forEachIndexed { i, key -> appendLine("val Res.string.$key: StringResource get() = StringResource(\"$key\", $i)") }
            pluralKeys.forEachIndexed { i, key -> appendLine("val Res.plurals.$key: PluralStringResource get() = PluralStringResource(\"$key\", $i)") }
            appendLine()
            appendLine("val Res.allStringResources: Map<String, StringResource> by lazy { mapOf(${keys.joinToString { "\"$it\" to Res.string.$it" }}) }")
            appendLine("val Res.allPluralStringResources: Map<String, PluralStringResource> by lazy { mapOf(${pluralKeys.joinToString { "\"$it\" to Res.plurals.$it" }}) }")
            appendLine()
            for ((name, strings, plurals) in listOf(Triple("RU", ruStrings, ruPlurals), Triple("EN", enStrings, enPlurals))) {
                // нет перевода — строка языка по умолчанию (ru), как у Compose Resources
                appendLine("internal val ${name}_STRINGS: Array<String> = arrayOf(")
                for (key in keys) appendLine("  ${literal(strings[key] ?: ruStrings.getValue(key))},")
                appendLine(")")
                appendLine("internal val ${name}_PLURALS: Array<Array<String?>> = arrayOf(")
                for (key in pluralKeys) {
                    val items = plurals[key] ?: ruPlurals.getValue(key)
                    appendLine("  arrayOf(${quantities.joinToString { q -> items[q]?.let(::literal) ?: "null" }}),")
                }
                appendLine(")")
            }
        }
        val dir = out.get().asFile.resolve("dev/iskandern/kotin/resources")
        dir.mkdirs()
        dir.resolve("Res.kt").writeText(text)
    }
}

tasks.matching { it.name.startsWith("compile") && it.name.contains("Kotlin") }.configureEach { dependsOn(generateStrings) }

// Рядом со страницей: Web Worker разбора (:drawingworker), шрифты и материалы из composeResources app — по тем же
// путям, что у Compose-версии
tasks.named<ProcessResources>("wasmJsProcessResources") {
    dependsOn(":drawingworker:wasmJsBrowserDistribution")
    from(rootProject.layout.projectDirectory.dir("drawingworker/build").dir("dist/wasmJs/productionExecutable")) {
        exclude("index.html", "*.map")
    }
    from(composeResources) {
        include("font/**", "files/**")
        into("composeResources/dev.iskandern.kotin.resources")
    }
    from("$appSrc/wasmJsMain/resources") {
        include("*.png", "*.ico", "*.svg", "site.webmanifest", "filament-kmp.js", "filament-kmp.wasm", "stories/**")
    }
}
