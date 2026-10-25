package dev.iskandern.kotin.baselineprofile

import android.content.Intent
import android.net.Uri
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until

const val PACKAGE = "dev.iskandern.kotin"

/**
 * Файл сценария: подписи, поверхность, слои — задевает чтение DXF, построение сцены и подписи. На устройство кладётся
 * заранее: adb push app/src/jvmTest/resources/dxf/labels-behind-surface.dxf /sdcard/Download/
 */
const val DXF = "/sdcard/Download/labels-behind-surface.dxf"

/** Строки журнала: первый кадр сцены и ошибка чтения (log_shown, log_failed в strings.xml). */
private const val SHOWN = "KoTIN: показан"
private const val FAILED = "KoTIN: не удалось открыть"

/** Холодный старт по VIEW-intent на [DXF] и ожидание первого кадра сцены. */
fun MacrobenchmarkScope.openDxf() {
  val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
  // file:// читается только с этим разрешением (до Android 13); тест ставит APK без него
  device.executeShellCommand("pm grant $PACKAGE android.permission.READ_EXTERNAL_STORAGE")
  device.executeShellCommand("logcat -c")
  startActivityAndWait(
    Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("file://$DXF"), "*/*").setPackage(PACKAGE),
  )
  waitShown(device)
}

private fun waitShown(device: UiDevice, timeoutMs: Long = 60_000) {
  val end = System.currentTimeMillis() + timeoutMs
  while (System.currentTimeMillis() < end) {
    val log = device.executeShellCommand("logcat -d -s System.out:I")
    if (SHOWN in log) return
    check(FAILED !in log) { log.lines().first { FAILED in it } }
    Thread.sleep(100)
  }
  error("за $timeoutMs мс нет «$SHOWN» — файл $DXF на устройстве?")
}

/**
 * Жесты после открытия: быстрый мах в карте уводит чертёж за край — появляется указатель на него (покадровая проверка
 * «вне кадра», плашка у края), тап по указателю возвращает чертёж, затем инерция орбиты и тап по осям во время неё.
 * Без профиля этот код на свежем процессе шёл интерпретатором: первый мах за край — около 40 кадров дольше 25 мс.
 */
fun MacrobenchmarkScope.gestures() {
  val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
  val w = device.displayWidth
  val h = device.displayHeight
  // мах влево (5 шагов — быстро, с инерцией), пока указатель не появится; обычно хватает одного
  repeat(3) {
    if (device.hasObject(By.text(POINTER))) return@repeat
    device.swipe(w * 3 / 4, h / 2, w / 4, h / 2, 5)
    device.wait(Until.hasObject(By.text(POINTER)), 3_000)
  }
  val pointer = checkNotNull(device.findObject(By.text(POINTER))) { "указатель «$POINTER» не появился: чертёж не ушёл за край" }
  pointer.click()
  Thread.sleep(1_000)
  device.findObject(By.text(ORBIT))?.click()
  Thread.sleep(500)
  device.swipe(w / 4, h / 2, w * 3 / 4, h / 2, 5)
  Thread.sleep(1_500)
  // оси — круг 72 dp в правом верхнем углу с отступом 16 dp под строкой состояния; тап по ним во время инерции
  val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
  device.swipe(w / 4, h / 2, w * 3 / 4, h / 2 + 50, 5)
  device.click(w - (52 * density).toInt(), (76 * density).toInt())
  Thread.sleep(1_500)
}

/** Текст плашки указателя и кнопки орбиты (drawing_pointer, camera_orbit в strings.xml; устройство на русском). */
private const val POINTER = "Чертёж"
private const val ORBIT = "Орбита"
