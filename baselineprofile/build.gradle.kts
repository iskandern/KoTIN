// Генерация baseline profile для :androidApp: сценарий «открыть DXF» на подключённом устройстве.
// ./gradlew :androidApp:generateBaselineProfile (ANDROID_SERIAL — какое устройство; нужен Android 13+ или root на 9+).
plugins {
    alias(libs.plugins.androidTest)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "dev.iskandern.kotin.baselineprofile"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = 28
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    targetProjectPath = ":androidApp"
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

baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.test.junit)
    implementation(libs.androidx.uiautomator)
    implementation(libs.androidx.benchmark.macro)
}

// См. androidApp: androidx 1.12 из Compose Multiplatform требует AGP 9.1 / compileSdk 37.
tasks.matching { it.name.startsWith("check") && it.name.endsWith("AarMetadata") }.configureEach {
    enabled = false
}

// Из тестируемого приложения нужен только APK, а AGP разрешает и его зависимости — и спотыкается
// о JVM-варианты filament-ffm (KMP-библиотеки без атрибутов Android).
configurations.matching { it.name.endsWith("TestedApks") }.configureEach {
    isTransitive = false
}
