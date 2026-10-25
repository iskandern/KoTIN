package dev.iskandern.kotin.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Холодный старт с открытием DXF: без профиля, с профилем из APK и потолок (speed).
 * ./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
  @get:Rule val rule = MacrobenchmarkRule()

  @Test fun none() = start(CompilationMode.None())

  @Test fun baselineProfile() = start(CompilationMode.Partial(BaselineProfileMode.Require))

  @Test fun full() = start(CompilationMode.Full())

  private fun start(mode: CompilationMode) = rule.measureRepeated(
    packageName = PACKAGE,
    metrics = listOf(StartupTimingMetric()),
    compilationMode = mode,
    startupMode = StartupMode.COLD,
    iterations = 5,
  ) { openDxf() }
}
