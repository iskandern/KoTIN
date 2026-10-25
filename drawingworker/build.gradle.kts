plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

// Web Worker: разбор файла (DXF, LandXML) вне главного потока. Собирается из тех же исходников
// пакетов чтения и модели сцены, что и приложение, без Compose и Filament.
kotlin {
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            binaries.executable()
            commonWebpackConfig {
                outputFileName = "drawingworker.js"
            }
        }
    }

    sourceSets {
        val appSrc = "../app/src"
        val packages = listOf("dxf", "landxml", "model", "io").map { "dev/iskandern/kotin/$it" }
        // При синхронизации IDE общие исходники не подключаем: из srcDir вне модуля IDE делает вложенный
        // content root (пакет kotin целиком), и файлы вне dxf перестают быть исходниками app.
        val ideSync = System.getProperty("idea.sync.active") == "true"
        commonMain {
            if (!ideSync) packages.forEach { kotlin.srcDir("$appSrc/commonMain/kotlin/$it") }
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        wasmJsMain {
            if (!ideSync) packages.forEach { kotlin.srcDir("$appSrc/wasmJsMain/kotlin/$it") }
        }
    }
}
