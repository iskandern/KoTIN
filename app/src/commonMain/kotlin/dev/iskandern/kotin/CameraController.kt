package dev.iskandern.kotin

import androidx.compose.runtime.getValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.unit.IntSize
import io.github.erkko68.filament.compose.scene.CameraState
import io.github.erkko68.filament.compose.scene.Direction
import io.github.erkko68.filament.compose.scene.Position
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import dev.iskandern.kotin.scene.DrawingOutline
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.time.TimeSource
import kotlin.math.tan

/**
 * Камера: поза — точка интереса pivot, расстояние до неё, азимут и угол места; pivot двигается, так что любой участок
 * поверхности можно подвести в центр. Зум, поворот и наклон держат неподвижной точку под жестом. Схемы управления — [CameraMode].
 * Покадровая поза живёт здесь; во ViewModel уходит остановившаяся ([pose], см. [CameraPoseMemory]), обратно — [initialPose] и команды ([execute]).
 */

class SceneCameraController(
  private val state: CameraState,
  private val sceneRadius: Float,
  private val fovDegrees: Double,
  /** Плоский чертёж: сброс поворота индикатором осей возвращает и вид сверху. */
  private val flat: Boolean = false,
  /** Поза, с которой камера продолжает после пересоздания экрана; null — домашний вид сцены. */
  initialPose: CameraPose? = null,
  /** Домашний вид (см. `homePose`); null — поза, с которой создан [state]. */
  homePose: CameraPose? = null,
) {
  /** Схема управления; синхронизируется с [CameraUiState.mode] во ViewModel. */
  var mode by mutableStateOf(CameraMode.MAP)

  // домашний вид сцены; CameraState один на экран и помнит позу прежней сцены, поэтому дом передаётся явно
  private var home = homePose
    ?: CameraPose.lookingAt(state.target.x, state.target.y, state.target.z, state.eye.x, state.eye.y, state.eye.z)
  private val start = initialPose ?: home

  private var pivotX = start.pivotX
  private var pivotY = start.pivotY
  private var pivotZ = start.pivotZ
  private var distance = start.distance
  private var yaw = start.yaw
  private var pitch = start.pitch

  /** Текущая поза, наблюдаемая Compose: по ней поворачивается индикатор осей и сохраняется вид (см. [CameraPoseMemory]). */
  var pose by mutableStateOf(start)
    private set

  /** Азимут и угол места текущей позы: по ним поворачивается индикатор осей. */
  val viewYaw: Float get() = pose.yaw
  val viewPitch: Float get() = pose.pitch

  init {
    apply()
  }

  // Инерция жестов и переходы по командам — одно движение камеры [motion]: новое заменяет прежнее. Движение живёт
  // здесь, а не в жестах или в эффекте экрана, чтобы команда (оси, указатель на чертёж) могла его погасить и знала,
  // что камера ехала: иначе переход обрывался бы на первом же кадре, где инерция сдвинула камеру, а команда поверх
  // незаконченного перехода начинала бы с разгона.
  private var motion: Job? = null
  // множитель трения инерции: зум кнопками не обрывает её, а быстро тормозит
  private var brake = 1f

  /** Камера движется сама: инерция после жеста или переход по команде. */
  val motionActive: Boolean get() = motion?.isActive == true

  /** Запускает движение камеры [block] в [scope] вместо прежнего: инерцию жеста или переход по команде. */
  fun startMotion(scope: CoroutineScope, block: suspend () -> Unit) {
    motion?.cancel()
    brake = 1f
    motion = scope.launch { block() }
  }

  // переход к новому домашнему виду, который ведёт сам контроллер: пока он идёт, камера ещё «дома»
  private var refitting: CameraPose? = null

  /**
   * Размер кадра сменился: домашний вид теперь [newHome]. Пока камеру не трогали (она стоит в домашней позе или едет
   * к ней), плавно вписывается заново; после жеста, зума или другой команды поза остаётся. «Исходный вид» берёт новый.
   */
  fun refitHome(newHome: CameraPose, scope: CoroutineScope) {
    if (newHome == home) return
    val atHome = pose == home || refitting != null
    home = newHome
    if (!atHome) return
    val fromMotion = motionActive
    startMotion(scope) {
      refitting = newHome
      try {
        animateTo(newHome, fromMotion)
      } finally {
        if (refitting === newHome) refitting = null
      }
    }
  }

  /** Гасит движение: новое касание. */
  fun stopMotion() {
    motion?.cancel()
    motion = null
  }

  /** Камера сейчас движется сама: инерция, переход или зум кнопками. */
  private val moving: Boolean get() = motionActive || zoomJob?.isActive == true

  /** Инерция сдвига: скорость [vx], [vy] px/с затухает экспоненциально; корутину отменяет новое касание. */
  suspend fun fling(vx: Float, vy: Float, x: Float, y: Float, viewWidth: Float, viewHeight: Float) {
    var sx = vx
    var sy = vy
    var frames = 0
    var last = withFrameNanos { it }
    while (sqrt(sx * sx + sy * sy) > FLING_STOP_SPEED) {
      val now = withFrameNanos { it }
      val dt = (now - last) / 1e9f
      last = now
      pan(x, y, sx * dt, sy * dt, viewWidth, viewHeight)
      val k = exp(-FLING_FRICTION * brake * dt)
      sx *= k
      sy *= k
      frames++
    }
  }

  /** Инерция вращения: скорость [vx], [vy] px/с (как у перетаскивания) затухает экспоненциально. */
  suspend fun orbitFling(vx: Float, vy: Float) {
    var sx = vx
    var sy = vy
    var last = withFrameNanos { it }
    while (sqrt(sx * sx + sy * sy) > FLING_STOP_SPEED) {
      val now = withFrameNanos { it }
      val dt = (now - last) / 1e9f
      last = now
      orbit(sx * dt, sy * dt)
      val k = exp(-ORBIT_FLING_FRICTION * brake * dt)
      sx *= k
      sy *= k
    }
  }

  /**
   * Плавный переход к позе [target] за [HOME_ANIMATION_MS] с замедлением к концу: pivot и угол места — линейно, азимут —
   * по кратчайшей дуге, расстояние — в логарифмической шкале, чтобы зум шёл равномерно. Жест посреди перехода его
   * прерывает: поза между кадрами уже не та, что поставил переход. [fromMotion] — камера уже двигалась (инерция,
   * зум, другой переход): переход начинается с ходу, без разгона, и только тормозит к концу — без рывка «стоп, поехали».
   */
  suspend fun animateTo(target: CameraPose, fromMotion: Boolean = false) {
    val easing = if (fromMotion) LinearOutSlowInEasing else FastOutSlowInEasing
    val from = pose
    val dYaw = (target.yaw - from.yaw).let { atan2(sin(it), cos(it)) }
    val zoom = ln(target.distance / from.distance)
    val start = withFrameNanos { it }
    var t = 0f
    while (t < 1f) {
      val set = pose
      val now = withFrameNanos { it }
      if (pose != set) return
      t = ((now - start) / 1e6f / HOME_ANIMATION_MS).coerceAtMost(1f)
      val k = easing.transform(t)
      pivotX = from.pivotX + (target.pivotX - from.pivotX) * k
      pivotY = from.pivotY + (target.pivotY - from.pivotY) * k
      pivotZ = from.pivotZ + (target.pivotZ - from.pivotZ) * k
      distance = from.distance * exp(zoom * k)
      yaw = from.yaw + dYaw * k
      pitch = from.pitch + (target.pitch - from.pitch) * k
      apply()
    }
    // точно в целевую позу: без накопленной погрешности и с азимутом в исходном диапазоне
    pivotX = target.pivotX; pivotY = target.pivotY; pivotZ = target.pivotZ
    distance = target.distance; yaw = target.yaw; pitch = target.pitch
    apply()
  }

  // буферы расчётов указателя на чертёж: они идут каждый кадр движения, в одном UI-потоке
  private val pointerSpace = DrawingPointerSpace()

  /** Чертёж с контуром [outline] целиком вне кадра — куда на него указать, см. [offscreenDrawing]. */
  fun offscreenDrawing(outline: DrawingOutline, verticalScale: Float, viewWidth: Float, viewHeight: Float): OffscreenDrawing? =
    pose.offscreenDrawing(fovDegrees, outline, verticalScale, viewWidth, viewHeight, pointerSpace)

  /** Куда на экране смотреть на чертёж [outline] и как он далеко, см. [drawingDirection]. */
  fun drawingDirection(outline: DrawingOutline, verticalScale: Float): OffscreenDrawing =
    pose.drawingDirection(outline, verticalScale, pointerSpace)

  /** Поза с ближайшей частью чертежа в кадре, без поворота и смены зума, см. [towardDrawing]. */
  fun towardDrawing(nearest: FloatArray, outline: DrawingOutline, verticalScale: Float): CameraPose =
    pose.towardDrawing(fovDegrees, nearest, outline, verticalScale)

  /** Луч через экранную точку ([px], [py]) вида [viewWidth] × [viewHeight] — для выбора объекта. */
  fun pickRay(px: Float, py: Float, viewWidth: Float, viewHeight: Float): PickRay = pose.pickRay(fovDegrees, px, py, viewWidth, viewHeight)

  /** Точка сцены ([y] — с вертикальным масштабом) на экране при текущей позе; null — за камерой. См. [CameraPose.project]. */
  fun project(x: Float, y: Float, z: Float, viewWidth: Float, viewHeight: Float): Pair<Float, Float>? =
    pose.project(fovDegrees, x, y, z, viewWidth, viewHeight)

  /**
   * Расстояние вдоль луча до видимого рельефа; null — рельефа там нет. Ставит экран сцены ([TerrainIndex]); без неё
   * жесты держат плоскость через pivot.
   */
  var terrainDistance: ((PickRay) -> Double?)? = null

  /**
   * В режиме орбиты pivot стоит на глубине точки рельефа, взятой жестом: сдвиг и зум идут в плоскости через pivot
   * перпендикулярно взгляду ([anchorOffset]) — угол любой, в том числе пологий и снизу. Иначе — прежняя плоскость земли.
   */
  private var orbitAnchored = false

  /**
   * Опора жеста — точка рельефа под ([px], [py]); глаз остаётся на месте, картинка не двигается. В режиме карты pivot
   * переезжает вдоль центрального луча на её высоту: дальше сдвиг, зум и жест двумя пальцами держат под пальцем саму
   * поверхность, а не плоскость через прежний pivot, пределы расстояния считаются от рельефа, наклон и поворот идут
   * вокруг точки на земле. В орбите pivot переезжает на её глубину (см. [orbitAnchored]). Мимо рельефа (за краем,
   * поверхность скрыта, только линии) — pivot прежний.
   */
  fun anchorAt(px: Float, py: Float, viewWidth: Float, viewHeight: Float) {
    if (mode == CameraMode.ORBIT) orbitAnchored = false
    val ray = pickRay(px, py, viewWidth, viewHeight)
    val t = terrainDistance?.invoke(ray) ?: return
    if (mode == CameraMode.MAP) {
      liftPivot((ray.oy + ray.dy * t).toFloat())
    } else {
      orbitAnchored = deepenPivot((ray.ox + ray.dx * t).toFloat(), (ray.oy + ray.dy * t).toFloat(), (ray.oz + ray.dz * t).toFloat())
    }
  }

  // pivot — вдоль центрального луча на глубину точки ([x], [y], [z]); глаз не двигается. false — точка позади глаза
  private fun deepenPivot(x: Float, y: Float, z: Float): Boolean {
    val ex = cos(pitch) * sin(yaw)
    val ey = sin(pitch)
    val ez = cos(pitch) * cos(yaw)
    val eyeX = pivotX + ex * distance
    val eyeY = pivotY + ey * distance
    val eyeZ = pivotZ + ez * distance
    // взгляд — −e: глубина = (P − глаз)·(−e)
    val d = -((x - eyeX) * ex + (y - eyeY) * ey + (z - eyeZ) * ez)
    if (d <= sceneRadius * MIN_ANCHOR_DISTANCE_TO_RADIUS) return false
    pivotX = eyeX - ex * d
    pivotY = eyeY - ey * d
    pivotZ = eyeZ - ez * d
    distance = d
    apply()
    return true
  }

  // pivot — на пересечение центрального луча взгляда с плоскостью y = [height]; глаз не двигается
  private fun liftPivot(height: Float) {
    val ey = sin(pitch)
    // взгляд почти вдоль горизонта: плоскость далеко или позади, переносить некуда
    if (ey < MIN_RAY_DOWN) return
    val ex = cos(pitch) * sin(yaw)
    val ez = cos(pitch) * cos(yaw)
    val eyeX = pivotX + ex * distance
    val eyeY = pivotY + ey * distance
    val eyeZ = pivotZ + ez * distance
    val d = (eyeY - height) / ey
    if (d <= sceneRadius * MIN_ANCHOR_DISTANCE_TO_RADIUS) return
    pivotX = eyeX - ex * d
    pivotY = height
    pivotZ = eyeZ - ez * d
    distance = d
    apply()
  }

  /**
   * Расстояние [value] в пределах [MIN_DISTANCE_TO_RADIUS]…[MAX_DISTANCE_TO_RADIUS]. Pivot, перенесённый на рельеф, может
   * оказаться за пределом: тогда дальше от него расстояние не уходит, но и назад к пределу не прыгает.
   */
  private fun limitDistance(value: Float): Float =
    value.coerceIn(min(sceneRadius * MIN_DISTANCE_TO_RADIUS, distance), max(sceneRadius * MAX_DISTANCE_TO_RADIUS, distance))

  // плавный зум кнопками: сколько ещё осталось приблизить (логарифм множителя) и анимация, которая это делает
  private var zoomLeft = 0f
  private var zoomJob: Job? = null

  /**
   * Зум к центру кадра на [factor] плавно, за [ZOOM_ANIMATION_MS] с замедлением к концу. Новое нажатие во время
   * анимации добавляет свой множитель к оставшемуся и начинает отсчёт заново — частые нажатия складываются.
   */
  fun animateZoom(factor: Float, scope: CoroutineScope) {
    // инерция гаснет быстро, но не рывком: зум идёт поверх неё
    brake = BRAKE
    anchorAt(0.5f, 0.5f, 1f, 1f)
    zoomLeft += ln(factor)
    zoomJob?.cancel()
    zoomJob = scope.launch {
      val total = zoomLeft
      var done = 0f
      val start = withFrameNanos { it }
      while (true) {
        val t = ((withFrameNanos { it } - start) / 1e6f / ZOOM_ANIMATION_MS).coerceAtMost(1f)
        val target = total * FastOutSlowInEasing.transform(t)
        zoom(exp(target - done), 0.5f, 0.5f, 1f, 1f)
        zoomLeft -= target - done
        done = target
        if (t >= 1f) break
      }
      zoomLeft = 0f
    }
  }

  private fun stopZoom() {
    zoomJob?.cancel()
    zoomLeft = 0f
  }

  /**
   * Выполняет команду из ViewModel как движение камеры в [scope]; зум кнопками — [animateZoom]. Команда заменяет
   * текущее движение (инерцию, переход), и если оно шло, переход начинается с ходу. В конце — возврат наклона из
   * резиновой зоны карты: команда могла оборвать его вместе с инерцией.
   */
  fun execute(command: CameraCommand, scope: CoroutineScope) {
    // центр кадра в нормированных координатах не зависит от размера вида — хватит квадрата 1 × 1
    if (command is CameraCommand.Zoom) {
      anchorAt(0.5f, 0.5f, 1f, 1f)
      zoom(command.factor, 0.5f, 0.5f, 1f, 1f)
      return
    }
    val fromMotion = moving
    stopZoom()
    startMotion(scope) {
      when (command) {
        CameraCommand.Home -> animateTo(home, fromMotion)
        CameraCommand.North -> resetNorth()
        is CameraCommand.ShowTop -> animateTo(topPose(command.x, command.z, command.height), fromMotion)
        // поза снята в момент нажатия: угол мог быть в резиновой зоне карты — переход сразу в рабочий диапазон
        is CameraCommand.FlyTo -> animateTo(
          if (mode == CameraMode.MAP) command.pose.copy(pitch = command.pose.pitch.coerceIn(MIN_PITCH, MAP_MAX_PITCH)) else command.pose,
          fromMotion,
        )
        is CameraCommand.Zoom -> Unit
      }
      settleTilt()
    }
  }

  /** Север вверх (азимут 0), у плоского чертежа — и строго сверху; pivot и расстояние не меняются. */
  fun resetNorth() {
    yaw = 0f
    if (flat) pitch = MAP_MAX_PITCH
    apply()
  }

  /** Поза строго сверху над ([x], [z]) сцены, севером вверх: по вертикали в кадр помещается [height] единиц. */
  fun topPose(x: Float, z: Float, height: Float): CameraPose = CameraPose(
    pivotX = x, pivotY = home.pivotY, pivotZ = z,
    distance = (height / 2f / tan(toRad(fovDegrees / 2))).coerceIn(sceneRadius * MIN_DISTANCE_TO_RADIUS, sceneRadius * MAX_DISTANCE_TO_RADIUS),
    yaw = 0f, pitch = MAP_MAX_PITCH,
  )

  /** Вращение вокруг pivot на [dx], [dy] пикселей перетаскивания. */
  fun orbit(dx: Float, dy: Float) {
    yaw -= dx * ORBIT_SPEED
    addPitch(dy * ORBIT_SPEED)
    apply()
  }

  /**
   * Сдвиг карты по горизонтальной плоскости (высота pivot не меняется): точка земли под пальцем, стоявшим в
   * ([x], [y]) до сдвига на ([dx], [dy]) пикселей, остаётся под ним.
   */
  fun pan(x: Float, y: Float, dx: Float, dy: Float, viewWidth: Float, viewHeight: Float) {
    if (mode == CameraMode.ORBIT && orbitAnchored) {
      // точка плоскости через pivot ⟂ взгляду под прежним положением пальца — под новым
      val tanHalf = tan(toRad(fovDegrees / 2))
      val from = anchorOffset(((x - dx) / viewWidth * 2f - 1f) * (viewWidth / viewHeight) * tanHalf, (1f - (y - dy) / viewHeight * 2f) * tanHalf)
      val to = anchorOffset((x / viewWidth * 2f - 1f) * (viewWidth / viewHeight) * tanHalf, (1f - y / viewHeight * 2f) * tanHalf)
      pivotX += from[0] - to[0]
      pivotY += from[1] - to[1]
      pivotZ += from[2] - to[2]
      apply()
      return
    }
    val from = ground(x - dx, y - dy, viewWidth, viewHeight)
    if (from != null && keepGround(from, x, y, viewWidth, viewHeight)) {
      apply()
      return
    }
    // взгляд почти вдоль горизонта: луч не доходит до земли, двигаем по масштабу экрана
    val k = 2f * distance * tan(toRad(fovDegrees / 2)) / viewHeight
    val r = right()
    pivotX += (-dx * r[0] - dy * sin(yaw)) * k
    pivotZ += (-dx * r[2] - dy * cos(yaw)) * k
    apply()
  }

  /**
   * Жест двумя пальцами: пальцы стояли в [a0], [b0], теперь в [a1], [b1]. Подбирает расстояние, heading и pivot так,
   * чтобы точки земли под обоими пальцами остались под ними (сдвиг, зум и поворот вокруг середины между пальцами —
   * один и тот же жест). Угол наклона не меняется. false — луч не дошёл до земли, камера не тронута.
   */
  fun grabTwo(a0: Offset, b0: Offset, a1: Offset, b1: Offset, viewWidth: Float, viewHeight: Float): Boolean {
    val pa = ground(a0.x, a0.y, viewWidth, viewHeight) ?: return false
    val pb = ground(b0.x, b0.y, viewWidth, viewHeight) ?: return false
    val ha = groundOffsetUnit(a1.x, a1.y, viewWidth, viewHeight, pitch) ?: return false
    val hb = groundOffsetUnit(b1.x, b1.y, viewWidth, viewHeight, pitch) ?: return false
    val pdx = pa[0] - pb[0]
    val pdz = pa[1] - pb[1]
    val hdx = ha[0] - hb[0]
    val hdz = ha[1] - hb[1]
    val pl = sqrt(pdx * pdx + pdz * pdz)
    val hl = sqrt(hdx * hdx + hdz * hdz)
    if (pl < 1e-6f || hl < 1e-6f) return false
    // ground(p) = pivot + distance · R(yaw) · h(p): разность двух точек задаёт расстояние и heading, одна точка — pivot
    distance = limitDistance(pl / hl)
    yaw = atan2(hdz, hdx) - atan2(pdz, pdx)
    keepGround(pa, a1.x, a1.y, viewWidth, viewHeight)
    apply()
    return true
  }

  /** Наклон на [dPitch] вокруг центра экрана: pivot (точка в центре кадра) остаётся на месте, меняется только угол. */
  fun tilt(dPitch: Float) {
    // чем положе вид, тем сильнее небольшой поворот сдвигает картинку, поэтому у горизонта угол меняется медленнее
    addPitch(dPitch * sin(pitch).coerceAtLeast(MIN_TILT_GAIN))
    apply()
  }

  /**
   * Угол места. В режиме карты рабочий диапазон — от [MIN_PITCH] до вида строго сверху ([MAP_MAX_PITCH]); за каждой
   * границей «резиновая» зона шириной [LOW_OVERSHOOT] / [TOP_OVERSHOOT]: наклоняется туже и упирается в край, [settleTilt] возвращает
   * вид на границу.
   */
  private fun addPitch(delta: Float) {
    if (mode != CameraMode.MAP) {
      pitch = (pitch + delta).coerceIn(-MAX_PITCH, MAX_PITCH)
      return
    }
    var d = delta
    if (d < 0f && pitch < MIN_PITCH) d *= max(0f, 1f - (MIN_PITCH - pitch) / LOW_OVERSHOOT)
    if (d > 0f && pitch > MAP_MAX_PITCH) d *= max(0f, 1f - (pitch - MAP_MAX_PITCH) / TOP_OVERSHOOT)
    pitch = (pitch + d).coerceIn(MIN_PITCH - LOW_OVERSHOOT, MAP_MAX_PITCH + TOP_OVERSHOOT)
  }

  /** Инерция наклона: угловая скорость [v] рад/с затухает; на границе диапазона обрывается. Корутину отменяет новое касание. */
  suspend fun tiltFling(v: Float) {
    var speed = v
    var last = withFrameNanos { it }
    while (abs(speed) > TILT_FLING_STOP_SPEED) {
      val now = withFrameNanos { it }
      val dt = (now - last) / 1e9f
      last = now
      val before = pitch
      tilt(speed * dt)
      // дошли до границы рабочего диапазона — инерция заканчивается, дальше сразу возврат (settleTilt)
      if (pitch < MIN_PITCH || pitch > MAP_MAX_PITCH || pitch == before) break
      speed *= exp(-TILT_FLING_FRICTION * brake * dt)
    }
  }

  /**
   * Переключает схему управления. В режиме карты у угла места есть границы, а в орбите он свободнее, поэтому при
   * возврате на карту угол плавно подводится к ближайшей допустимой границе.
   */
  suspend fun switchMode(newMode: CameraMode) {
    mode = newMode
    orbitAnchored = false
    settleTilt()
  }

  /** Плавно возвращает угол на ближайшую границу режима карты, если он ушёл за допустимый диапазон. */
  suspend fun settleTilt() {
    if (mode != CameraMode.MAP) return
    val target = pitch.coerceIn(MIN_PITCH, MAP_MAX_PITCH)
    if (target == pitch) return
    var last = withFrameNanos { it }
    while (abs(target - pitch) > SETTLE_EPSILON) {
      // переключились обратно на орбиту, пока шёл возврат: границы больше нет
      if (mode != CameraMode.MAP) return
      val now = withFrameNanos { it }
      val dt = (now - last) / 1e9f
      last = now
      pitch += (target - pitch) * (1f - exp(-SETTLE_RATE * dt))
      apply()
    }
    pitch = target
    apply()
  }

  // Смещение точки земли под экранной позицией относительно pivot при yaw = 0 в долях distance: (x, z); null — луч идёт вверх или вдоль горизонта
  private fun groundOffsetUnit(px: Float, py: Float, viewWidth: Float, viewHeight: Float, pitchValue: Float): FloatArray? {
    val tanHalf = tan(toRad(fovDegrees / 2))
    val nx = (px / viewWidth * 2f - 1f) * (viewWidth / viewHeight) * tanHalf
    val ny = (1f - py / viewHeight * 2f) * tanHalf
    val ey = sin(pitchValue)
    val ez = cos(pitchValue)
    // yaw = 0: глаз (0, ey, ez), вправо (1, 0, 0), вверх (0, ez, −ey); луч — вперёд (−e) + вправо·nx + вверх·ny
    val dirY = -ey + ez * ny
    val dirZ = -ez - ey * ny
    if (dirY > -MIN_RAY_DOWN) return null
    val t = -ey / dirY
    return floatArrayOf(nx * t, ez + dirZ * t)
  }

  private fun rotate(x: Float, z: Float, yawValue: Float) =
    floatArrayOf(x * cos(yawValue) + z * sin(yawValue), -x * sin(yawValue) + z * cos(yawValue))

  // точка земли (x, z) под экранной позицией при текущей позе камеры
  private fun ground(px: Float, py: Float, viewWidth: Float, viewHeight: Float): FloatArray? {
    val g = groundOffsetUnit(px, py, viewWidth, viewHeight, pitch) ?: return null
    val r = rotate(g[0] * distance, g[1] * distance, yaw)
    return floatArrayOf(pivotX + r[0], pivotZ + r[1])
  }

  // сдвигает pivot так, чтобы под экранной позицией при текущих yaw/pitch/distance оказалась точка земли [p]
  private fun keepGround(p: FloatArray, px: Float, py: Float, viewWidth: Float, viewHeight: Float): Boolean {
    val g = groundOffsetUnit(px, py, viewWidth, viewHeight, pitch) ?: return false
    val r = rotate(g[0] * distance, g[1] * distance, yaw)
    pivotX = p[0] - r[0]
    pivotZ = p[1] - r[1]
    return true
  }

  /**
   * Масштаб расстояния на [factor] (<1 — приблизить), поворот на [dYaw] и наклон на [dPitch] так, чтобы точка под
   * экранной позицией ([px], [py]) осталась на месте: pivot съезжает к ней.
   */
  fun transform(factor: Float, dYaw: Float, dPitch: Float, px: Float, py: Float, viewWidth: Float, viewHeight: Float) {
    val tanHalf = tan(toRad(fovDegrees / 2))
    val nx = (px / viewWidth * 2f - 1f) * (viewWidth / viewHeight) * tanHalf
    val ny = (1f - py / viewHeight * 2f) * tanHalf
    val before = anchorOffset(nx, ny)
    distance = limitDistance(distance * factor)
    yaw += dYaw
    pitch = (pitch + dPitch).coerceIn(-MAX_PITCH, MAX_PITCH)
    val after = anchorOffset(nx, ny)
    pivotX += before[0] - after[0]
    pivotY += before[1] - after[1]
    pivotZ += before[2] - after[2]
    apply()
  }

  fun zoom(factor: Float, px: Float, py: Float, viewWidth: Float, viewHeight: Float) {
    // орбита с опорой: точка рельефа лежит в плоскости через pivot ⟂ взгляду — её и держим
    val p = if (mode == CameraMode.ORBIT && orbitAnchored) null else ground(px, py, viewWidth, viewHeight)
    if (p == null) {
      transform(factor, 0f, 0f, px, py, viewWidth, viewHeight)
      return
    }
    distance = limitDistance(distance * factor)
    keepGround(p, px, py, viewWidth, viewHeight)
    apply()
  }

  // точка на плоскости через pivot перпендикулярно взгляду, видимая в нормированных координатах (nx, ny), — относительно pivot
  private fun anchorOffset(nx: Float, ny: Float): FloatArray {
    val r = right()
    val u = upVec()
    return floatArrayOf(
      (r[0] * nx + u[0] * ny) * distance,
      (r[1] * nx + u[1] * ny) * distance,
      (r[2] * nx + u[2] * ny) * distance,
    )
  }

  private fun right() = floatArrayOf(cos(yaw), 0f, -sin(yaw))

  // вверх по экрану: перпендикуляр к направлению взгляда и вправо
  private fun upVec(): FloatArray {
    val ex = cos(pitch) * sin(yaw)
    val ey = sin(pitch)
    val ez = cos(pitch) * cos(yaw)
    val r = right()
    // up = eyeDir × right (eyeDir — от pivot к камере)
    return floatArrayOf(ey * r[2] - ez * r[1], ez * r[0] - ex * r[2], ex * r[1] - ey * r[0])
  }

  private fun apply() {
    val ex = cos(pitch) * sin(yaw)
    val ey = sin(pitch)
    val ez = cos(pitch) * cos(yaw)
    state.target = Position(pivotX, pivotY, pivotZ)
    state.eye = Position(pivotX + ex * distance, pivotY + ey * distance, pivotZ + ez * distance)
    val u = upVec()
    state.up = Direction(u[0], u[1], u[2])
    pose = CameraPose(pivotX, pivotY, pivotZ, distance, yaw, pitch)
  }

  private companion object {
    const val MIN_RAY_DOWN = 0.02f
    const val ORBIT_SPEED = 0.006f
    const val FLING_FRICTION = 3.5f
    const val FLING_STOP_SPEED = 20f
    const val ORBIT_FLING_FRICTION = 2.5f
    const val MAX_PITCH = (PI / 2 - 0.02).toFloat()
    // диапазон угла места в режиме карты: от MIN_PITCH до вида строго сверху, за каждой границей — резиновый запас
    const val MIN_PITCH = (20.0 * PI / 180).toFloat()
    const val MAP_MAX_PITCH = (PI / 2).toFloat()
    const val TOP_OVERSHOOT = (10.0 * PI / 180).toFloat()
    const val LOW_OVERSHOOT = (4.0 * PI / 180).toFloat()
    const val MIN_TILT_GAIN = 0.3f
    const val SETTLE_RATE = 12f
    const val SETTLE_EPSILON = 0.0005f
    const val TILT_FLING_FRICTION = 7f
    const val TILT_FLING_STOP_SPEED = 0.05f
    const val MIN_DISTANCE_TO_RADIUS = 0.002f
    const val MAX_DISTANCE_TO_RADIUS = 20f
    // ближе к рельефу pivot не переносится: вырожденное расстояние сломало бы зум
    const val MIN_ANCHOR_DISTANCE_TO_RADIUS = 1e-6f
    const val ZOOM_ANIMATION_MS = 250f
    const val HOME_ANIMATION_MS = 450f
    // во сколько раз сильнее трение инерции, когда её тормозит зум кнопками
    const val BRAKE = 6f

    fun toRad(deg: Double): Float = (deg * PI / 180).toFloat()
  }
}

/**
 * Жесты управления [SceneCameraController] в его текущем [SceneCameraController.mode].
 * [onLongPress] — долгое нажатие на месте одним пальцем или основной кнопкой мыши: экранная точка и размер вида.
 * Нажатие, в котором камеру двигали или клали второй палец, долгим не считается. [onTap] — короткое нажатие на месте
 * так же одним пальцем или основной кнопкой мыши.
 */
fun Modifier.sceneGestures(
  controller: SceneCameraController,
  onTap: (Offset, IntSize) -> Unit = { _, _ -> },
  onLongPress: (Offset, IntSize) -> Unit = { _, _ -> },
): Modifier = this
  .pointerInput(controller) {
    coroutineScope {
      // движение камеры держит контроллер: касание гасит и инерцию, и переход по команде
      while (true) {
        val end = awaitPointerEventScope {
          mapGesture(
            controller,
            onTap = onTap,
            onLongPress = onLongPress,
            onDown = { controller.stopMotion() },
            // наклон двумя пальцами закончился (даже если один палец ещё на экране): инерция наклона и сразу возврат из резиновой зоны
            onTiltEnd = { v ->
              controller.startMotion(this@coroutineScope) {
                v?.let { controller.tiltFling(it) }
                controller.settleTilt()
              }
            },
          )
        }
        // после жеста: инерция сдвига, затем возврат из резиновой зоны угла
        if (end != null || !controller.motionActive) {
          controller.startMotion(this@coroutineScope) {
            end?.let {
              if (it.orbit) controller.orbitFling(it.velocity.x, it.velocity.y)
              else controller.fling(it.velocity.x, it.velocity.y, it.at.x, it.at.y, size.width.toFloat(), size.height.toFloat())
            }
            controller.settleTilt()
          }
        }
      }
    }
  }
  .pointerInput(controller) {
    awaitPointerEventScope {
      // опора прошлого шага колеса: тот же курсор и поза, которую оставил этот шаг, — рельеф под курсором тот же
      var anchoredAt: Offset? = null
      var anchoredPose: CameraPose? = null
      var anchoredMode: CameraMode? = null
      while (true) {
        val e = awaitPointerEvent()
        if (e.type != PointerEventType.Scroll) continue
        val c = e.changes.firstOrNull() ?: continue
        if (c.position != anchoredAt || controller.pose != anchoredPose || controller.mode != anchoredMode) {
          controller.anchorAt(c.position.x, c.position.y, size.width.toFloat(), size.height.toFloat())
        }
        // величина деления зависит от платформы (в браузере — десятки), поэтому шаг одного события ограничен
        val factor = exp((c.scrollDelta.y * WHEEL_ZOOM).coerceIn(-MAX_WHEEL_STEP, MAX_WHEEL_STEP))
        controller.zoom(factor, c.position.x, c.position.y, size.width.toFloat(), size.height.toFloat())
        anchoredAt = c.position
        anchoredPose = controller.pose
        anchoredMode = controller.mode
        e.changes.forEach { it.consume() }
      }
    }
  }

private enum class TwoFingerMode { UNDECIDED, TILT, ZOOM_ROTATE }

private class FlingStart(val velocity: Offset, val at: Offset, val orbit: Boolean)

private class Sample(val time: Long, val position: Offset)

/** Скорость пальца (px/с) по последним ~100 мс до отпускания; палец, задержавшийся перед отрывом, скорости не даёт. */
private fun releaseVelocity(samples: List<Sample>, upTime: Long): Offset {
  val last = samples.lastOrNull() ?: return Offset.Zero
  if (upTime - last.time > FLING_STOP_MS) return Offset.Zero
  val first = samples.first { last.time - it.time <= FLING_WINDOW_MS }
  val dt = (last.time - first.time) / 1000f
  if (dt < 0.01f) return Offset.Zero
  return (last.position - first.position) / dt
}

/** Один жест от первого касания до отпускания. Возвращает скорость сдвига (px/с) и точку отпускания, если жест — свайп одним пальцем в режиме карты. */
/** [onTiltEnd] вызывается, когда наклон двумя пальцами закончился, со скоростью наклона (рад/с) или null. */
/** [onLongPress] — палец или кнопка простояли на месте дольше `longPressTimeoutMillis`; остаток жеста камеру уже не двигает. */
/** [onTap] — палец или кнопка отпущены на месте раньше. */
private suspend fun AwaitPointerEventScope.mapGesture(
  controller: SceneCameraController,
  onTap: (Offset, IntSize) -> Unit,
  onLongPress: (Offset, IntSize) -> Unit,
  onDown: () -> Unit,
  onTiltEnd: (Float?) -> Unit,
): FlingStart? {
  var event: PointerEvent
  do {
    event = awaitPointerEvent()
  } while (!event.changes.all { it.changedToDownIgnoreConsumed() })
  onDown()
  val map = controller.mode == CameraMode.MAP
  // мышь: вторичная схема — правая/средняя кнопка или Shift
  val secondary = event.changes[0].type == PointerType.Mouse &&
    (!event.buttons.isPrimaryPressed || event.keyboardModifiers.isShiftPressed)
  val w = size.width.toFloat()
  val h = size.height.toFloat()
  val slop = viewConfiguration.touchSlop
  // опора: вращение (вторичная схема на карте, основная в орбите) — вокруг того, что в центре кадра; сдвиг — под пальцем
  if (map == secondary) controller.anchorAt(w / 2f, h / 2f, w, h) else controller.anchorAt(event.changes[0].position.x, event.changes[0].position.y, w, h)
  val samples = ArrayList<Sample>()
  var lastPos = event.changes[0].position
  var upTime = event.changes[0].uptimeMillis
  var multi = false          // были ли два пальца
  var moved = 0f             // путь одного пальца до появления второго
  var startedPanning = false // перемещение уже шло, когда лёг второй палец
  var twoFinger = TwoFingerMode.UNDECIDED
  var accY = 0f              // путь жеста двумя пальцами до выбора: вертикаль / масштаб / поворот
  var accSpan = 0f
  var accRot = 0f
  val tiltSamples = ArrayList<Sample>() // вертикаль середины между пальцами в режиме наклона
  var tiltEnded = false
  var lastOrbit = false      // последнее движение одним пальцем было вращением
  val pressMark = TimeSource.Monotonic.markNow()
  var longPressed = false    // долгое нажатие сработало: до отпускания камера не двигается

  while (true) {
    // долгое нажатие ждём, пока палец почти не сдвинулся и второй не лёг; вторичная схема мыши (вращение) — не в счёт
    val waitsLongPress = !secondary && !longPressed && !multi && moved <= slop
    val remaining = viewConfiguration.longPressTimeoutMillis - pressMark.elapsedNow().inWholeMilliseconds
    val e = if (waitsLongPress) {
      withTimeoutOrNull(max(remaining, 0L)) { awaitPointerEvent() }
    } else {
      awaitPointerEvent()
    }
    if (e == null) {
      longPressed = true
      onLongPress(lastPos, size)
      continue
    }
    val pressed = e.changes.filter { it.pressed }
    upTime = e.changes[0].uptimeMillis
    if (longPressed) {
      e.changes.forEach { it.consume() }
      if (pressed.isEmpty()) break
      continue
    }
    if (twoFinger == TwoFingerMode.TILT && !tiltEnded && pressed.size < 2) {
      // пальцы разошлись: скорость наклона — по последним ~100 мс движения середины между ними
      tiltEnded = true
      val v = releaseVelocity(tiltSamples, upTime).y * TILT_SPEED * TILT_FLING_GAIN
      onTiltEnd(v.takeIf { abs(it) > TILT_FLING_MIN_SPEED })
    }
    if (pressed.isEmpty()) break
    if (pressed.size >= 2) {
      val two = pressed.sortedBy { it.id.value }
      val a = two[0]
      val b = two[1]
      if (!multi) {
        startedPanning = moved > slop
        // лёг второй палец: опора — рельеф под серединой между пальцами
        val center = (a.position + b.position) / 2f
        controller.anchorAt(center.x, center.y, w, h)
      }
      // палец, только что севший на экран, предыдущей позиции не имеет — этот кадр только запоминается
      if (multi && a.previousPressed && b.previousPressed) {
        val a0 = a.previousPosition
        val b0 = b.previousPosition
        val a1 = a.position
        val b1 = b.position
        val center0 = (a0 + b0) / 2f
        val center1 = (a1 + b1) / 2f
        val span0 = (a0 - b0).getDistance()
        val span1 = (a1 - b1).getDistance()
        val dc = center1 - center0
        if (map) {
          if (!startedPanning && twoFinger == TwoFingerMode.UNDECIDED) {
            // наклон — только если жест начался со сдвига по вертикали; зум или поворот первыми отменяют его на весь жест
            var dAngle = atan2(b1.y - a1.y, b1.x - a1.x) - atan2(b0.y - a0.y, b0.x - a0.x)
            if (dAngle > PI) dAngle -= (2 * PI).toFloat() else if (dAngle < -PI) dAngle += (2 * PI).toFloat()
            accY += abs(dc.y)
            accSpan += abs(span1 - span0)
            accRot += abs(dAngle) * span1 / 2f
            if (maxOf(accY, accSpan, accRot) > slop) {
              twoFinger = if (accY >= accSpan && accY >= accRot) TwoFingerMode.TILT else TwoFingerMode.ZOOM_ROTATE
            }
          }
          if (startedPanning || twoFinger == TwoFingerMode.ZOOM_ROTATE) {
            // точки земли под обоими пальцами остаются под ними: сдвиг, зум и heading — один жест
            if (span0 > 0f && !controller.grabTwo(a0, b0, a1, b1, w, h)) {
              controller.pan(center1.x, center1.y, dc.x, dc.y, w, h)
              controller.zoom(span0 / span1, center1.x, center1.y, w, h)
            }
          } else if (twoFinger == TwoFingerMode.TILT) {
            controller.tilt(dc.y * TILT_SPEED)
            tiltSamples.add(Sample(a.uptimeMillis, Offset(0f, center1.y)))
            if (tiltSamples.size > MAX_SAMPLES) tiltSamples.removeAt(0)
          }
        } else if (span0 > 0f) {
          controller.pan(center1.x, center1.y, dc.x, dc.y, w, h)
          controller.zoom(span0 / span1, center1.x, center1.y, w, h)
        }
      }
      multi = true
      e.changes.forEach { it.consume() }
    } else {
      val c = pressed.firstOrNull() ?: break
      if (c.positionChanged()) {
        val d = c.position - c.previousPosition
        if (!multi) {
          moved += abs(d.x) + abs(d.y)
          samples.add(Sample(c.uptimeMillis, c.position))
          if (samples.size > MAX_SAMPLES) samples.removeAt(0)
          lastPos = c.position
        }
        // карты: палец двигает, оставшийся после двух — вращает; орбита: наоборот
        val orbit = if (map) multi || secondary else !secondary
        lastOrbit = orbit
        if (orbit) controller.orbit(d.x, d.y) else controller.pan(c.position.x, c.position.y, d.x, d.y, w, h)
        c.consume()
      }
    }
  }
  // палец почти не сдвинулся, второго не было, долгим нажатие не стало — короткое нажатие
  if (!secondary && !multi && !longPressed && moved <= slop) onTap(lastPos, size)
  val v = if (!multi && !longPressed) releaseVelocity(samples, upTime) else Offset.Zero
  return if (v.getDistance() > FLING_MIN_SPEED) FlingStart(v, lastPos, lastOrbit) else null
}

private const val WHEEL_ZOOM = 0.05f
private const val MAX_WHEEL_STEP = 0.12f
private const val TILT_SPEED = 0.006f
private const val FLING_MIN_SPEED = 150f
private const val TILT_FLING_MIN_SPEED = 0.15f
private const val TILT_FLING_GAIN = 0.4f
private const val FLING_STOP_MS = 50L
private const val FLING_WINDOW_MS = 100L
private const val MAX_SAMPLES = 32
