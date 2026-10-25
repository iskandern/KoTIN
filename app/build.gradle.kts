import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    android {
        namespace = "dev.iskandern.kotin.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
        androidResources {
            enable = true
        }
    }

    jvm()

    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            binaries.executable()
            commonWebpackConfig {
                outputFileName = "app.js"
            }
        }
    }

    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate {
        common {
            group("jvmCommon") {
                withJvm()
                withCompilations { it.platformType == org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.androidJvm }
            }
        }
    }

    sourceSets {
        commonMain {
            dependencies {
                api(libs.compose.runtime)
                api(libs.compose.foundation)
                api(libs.compose.material3)
                api(libs.compose.ui)
                api(libs.filament)
                api(libs.filament.compose)
                implementation(libs.androidx.lifecycle.viewmodel.compose)
                implementation(libs.compose.components.resources)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
        }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutines.swing)
            // только для генератора материалов (GenerateMaterials.kt)
            implementation(libs.filamat)
        }
        jvmTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

compose.resources {
    packageOfResClass = "dev.iskandern.kotin.resources"
}

// Версия пакетов — APP_VERSION (MAJOR.MINOR.PATCH; macOS требует MAJOR больше нуля)
val appVersion = Regex("""APP_VERSION = "([\d.]+)"""")
    .find(file("src/commonMain/kotlin/dev/iskandern/kotin/AppVersion.kt").readText())!!.groupValues[1]

compose.desktop {
    application {
        mainClass = "dev.iskandern.kotin.MainKt"
        jvmArgs += "--enable-native-access=ALL-UNNAMED"

        // Установщики: ./gradlew :app:packageDistributionForCurrentOS — собираются только на своей ОС
        // (Skiko кладёт нативную библиотеку текущей системы). Выпуск — release.yml.
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "KoTIN"
            packageVersion = appVersion
            description = "Просмотр TIN-поверхностей, DXF и LandXML в 3D"
            vendor = "iskandern"
            // suggestRuntimeModules
            modules("java.instrument", "java.prefs", "jdk.unsupported")

            macOS {
                bundleID = "dev.iskandern.kotin"
                iconFile.set(file("icons/kotin.icns"))
            }
            windows {
                iconFile.set(file("icons/kotin.ico"))
                menu = true
                shortcut = true
                perUserInstall = true  // без прав администратора
                upgradeUuid = "5F938ECC-1AC2-4B63-908B-BF0F54EBF3C4"  // не менять: по нему новая версия заменяет старую
            }
            linux {
                iconFile.set(file("icons/kotin.png"))
                packageName = "kotin"
                shortcut = true
            }
        }
    }
}

// Как в :androidApp: androidx Compose 1.12 и lifecycle 2.11 требуют AGP 9.1 и compileSdk 37, а IDE пока
// поддерживает AGP 9.0 / SDK 36. Без этого :app:build и сборка из IDE падают на проверке AAR-метаданных.
tasks.matching { it.name.startsWith("check") && it.name.endsWith("AarMetadata") }.configureEach {
    enabled = false
}

// Компилирует материалы из Materials.kt в composeResources/files/*.filamat для всех бэкендов,
// включая OpenGL ES 2.0 (feature level 0). Запускать после правки шейдеров: ./gradlew :app:generateMaterials
tasks.register<JavaExec>("generateMaterials") {
    group = "filament"
    val jvmMain = kotlin.jvm().compilations.getByName("main")
    dependsOn(jvmMain.compileTaskProvider)
    classpath = files(jvmMain.output.allOutputs, jvmMain.runtimeDependencyFiles)
    mainClass.set("dev.iskandern.kotin.GenerateMaterialsKt")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    args(layout.projectDirectory.dir("src/commonMain/composeResources/files").asFile.absolutePath)
}

// Web Worker с разбором DXF и LandXML (модуль :drawingworker) кладём рядом с приложением: drawingworker.js и его .wasm
tasks.named<ProcessResources>("wasmJsProcessResources") {
    dependsOn(":drawingworker:wasmJsBrowserDistribution")
    from(rootProject.layout.projectDirectory.dir("drawingworker/build").dir("dist/wasmJs/productionExecutable")) {
        exclude("index.html", "*.map")
    }
}
