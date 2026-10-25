import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "dev.iskandern.kotin.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "dev.iskandern.kotin"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = Regex("""APP_VERSION = "([\d.]+)"""")
            .find(rootProject.file("app/src/commonMain/kotlin/dev/iskandern/kotin/AppVersion.kt").readText())!!.groupValues[1]
    }
    // Ключ релиза в репо нет: берём keystore из local.properties или переменных окружения
    // (RELEASE_STORE_FILE, RELEASE_STORE_PASSWORD, RELEASE_KEY_ALIAS, RELEASE_KEY_PASSWORD),
    // иначе подписываем debug-ключом — годится для внутренних замеров.
    val localProps = Properties().apply {
        rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }
    fun releaseProp(name: String): String? = localProps.getProperty(name) ?: System.getenv(name)
    val releaseStoreFile = releaseProp("RELEASE_STORE_FILE")
    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile)
                storePassword = releaseProp("RELEASE_STORE_PASSWORD")
                keyAlias = releaseProp("RELEASE_KEY_ALIAS")
                keyPassword = releaseProp("RELEASE_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    // lintVital для release собирает lint-AAR из :app, а тот требует вывод отключённой проверки
    // AAR-метаданных (см. конец файла) — без этого assembleRelease падает. Lint гоняем отдельно.
    lint {
        checkReleaseBuilds = false
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(project(":app"))
    implementation(libs.androidx.activity.compose)
    // ставит baseline profile из APK (на Android 9 — с фоновым dexopt при простое устройства)
    implementation(libs.androidx.profileinstaller)
    baselineProfile(project(":baselineprofile"))
}

// Как в прототипе Filament: androidx Compose 1.12 (транзитивно из Compose Multiplatform 1.12 / filament-compose 0.5.0)
// требует AGP 9.1 и compileSdk 37, а IDE пока поддерживает AGP 9.0 / SDK 36. Отключаем проверку AAR-метаданных.
tasks.matching { it.name.startsWith("check") && it.name.endsWith("AarMetadata") }.configureEach {
    enabled = false
}
