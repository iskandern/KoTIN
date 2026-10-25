package dev.iskandern.kotin.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Профиль: старт с открытием DXF (он же — startup profile) и жесты после него: чертёж уходит за край, указатель на него,
 * орбита, оси. ./gradlew :androidApp:generateBaselineProfile → androidApp/src/release/generated/baselineProfiles.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
  @get:Rule val rule = BaselineProfileRule()

  @Test
  fun openDxf() = rule.collect(PACKAGE, includeInStartupProfile = true) { openDxf() }

  @Test
  fun gestures() = rule.collect(PACKAGE, includeInStartupProfile = false) {
    openDxf()
    gestures()
  }
}
