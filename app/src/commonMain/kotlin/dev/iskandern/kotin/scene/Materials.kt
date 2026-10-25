package dev.iskandern.kotin.scene

/**
 * Свои материалы вместо стандартных filament-compose: те собраны только под OpenGL ES 3.0+,
 * и на устройствах с ES 2.0 (feature level 0) Filament падает при их загрузке.
 * Наши — unlit (единственный вариант для feature level 0), освещение поверхности считается на CPU
 * и приходит цветом вершин. Собираются в .filamat задачей `./gradlew :app:generateMaterials`.
 */
const val SURFACE_MATERIAL_PATH = "files/surface.filamat"
const val LINE_MATERIAL_PATH = "files/line.filamat"
const val RIBBON_MATERIAL_PATH = "files/ribbon.filamat"
const val RIBBON_CAPSULE_MATERIAL_PATH = "files/ribbon_capsule.filamat"
const val TEXT_MATERIAL_PATH = "files/text.filamat"
const val FILL_MATERIAL_PATH = "files/fill.filamat"
const val TRANSPARENT_FILL_MATERIAL_PATH = "files/fill_transparent.filamat"

/**
 * Поверхности и заливки: цвет вершины или, при подсветке (`tint.a` = 1), тон `tint.rgb`, умноженный на сумму каналов
 * вершины: в ней светотень, и выбранная поверхность освещена так, будто она этого цвета ([applyTone]). Подсветка
 * меняет только значение параметра — вершины и буферы GPU не трогаются, лишней геометрии нет.
 */
const val SURFACE_FRAGMENT = """
void material(inout MaterialInputs material) {
    prepareMaterial(material);
    vec4 c = getColor();
    vec3 toned = materialParams.tint.rgb * (c.r + c.g + c.b);
    material.baseColor = vec4(mix(c.rgb, toned, materialParams.tint.a), c.a);
}
"""

/** Полупрозрачные заливки (код 440): альфа — в цвете вершин, смешивание TRANSPARENT ждёт предумноженный цвет. */
const val TRANSPARENT_FILL_FRAGMENT = """
void material(inout MaterialInputs material) {
    prepareMaterial(material);
    vec4 c = getColor();
    vec3 rgb = mix(c.rgb, materialParams.tint.rgb, materialParams.tint.a);
    material.baseColor = vec4(rgb * c.a, c.a);
}
"""

const val LINE_FRAGMENT = """
void material(inout MaterialInputs material) {
    prepareMaterial(material);
    material.baseColor = vec4(materialParams.color, 1.0);
}
"""

/**
 * Разведение совпадающих слоёв по глубине (аналог polygonOffset; сравнение глубины в материалах Filament менять
 * нельзя). Вершина тянется к камере вдоль луча взгляда на долю `depthBias.x × глубина + depthBias.y` расстояния —
 * точка на экране та же. `x` — шаги буфера глубины (SceneView, `depthBiasPerUnit`, от ближней плоскости), `y` — запас
 * на наклон грани (аналог множителя наклона в polygonOffset). `extra` — добавка к доле (ступень порядка заливки).
 */
const val DEPTH_BIAS_FUNCTION = """
vec4 biasDepth(vec4 worldPosition, float extra) {
    float depth = (getClipFromWorldMatrix() * worldPosition).w;
    float k = clamp(materialParams.depthBias.x * depth + materialParams.depthBias.y + extra, 0.0, 0.5);
    return vec4(mix(worldPosition.xyz, getWorldCameraPosition(), k), worldPosition.w);
}

vec4 biasDepth(vec4 worldPosition) {
    return biasDepth(worldPosition, 0.0);
}
"""

/** Вершинный шейдер поверхностей, заливок и тонких линий: только сдвиг глубины ([DEPTH_BIAS_FUNCTION]). */
const val DEPTH_BIAS_VERTEX = DEPTH_BIAS_FUNCTION + """
void materialVertex(inout MaterialVertexInputs material) {
    material.worldPosition = biasDepth(material.worldPosition);
}
"""

/**
 * Вершинный шейдер заливок: к общему сдвигу глубины добавляется ступень порядка прорисовки (CUSTOM0.x).
 * Ступень — шаг буфера глубины (`stepBias.x × глубина`, доля), но не больше `stepBias.y`: вдали шаг буфера растёт
 * с квадратом расстояния, и без потолка дальние заливки уходили бы к камере сквозь рельеф. Совпадающие по высоте
 * заливки идут в порядке DXF без сдвига отметок.
 */
const val FILL_VERTEX = DEPTH_BIAS_FUNCTION + """
void materialVertex(inout MaterialVertexInputs material) {
    float depth = (getClipFromWorldMatrix() * material.worldPosition).w;
    float step = min(materialParams.stepBias.x * depth, materialParams.stepBias.y);
    material.worldPosition = biasDepth(material.worldPosition, getCustom0().x * step);
}
"""

/**
 * Вершинный шейдер толстых линий. У вершины ленты в CUSTOM0 лежит вектор к другому концу отрезка (xyz)
 * и сторона ленты (w): ±1 на конце A, ±2 на конце B. Вершина сдвигается в мировых координатах поперёк отрезка и
 * вида, на половину ширины в пикселях (масштаб пикселя в мире — по глубине вершины и проекции), и на ту же величину
 * вдоль отрезка наружу — торцы закрывают стыки ломаной. Затем — сдвиг глубины, как у тонких линий.
 *
 * [capsule] — вариант обводки выбранного ([RIBBON_CAPSULE_VERTEX], [RIBBON_CAPSULE_FRAGMENT]): в varying `cap` кладутся
 * координаты вершины в ленте, по ним фрагмент скругляет торцы. Линии чертежа и обводки поверхностей идут без него
 * ([RIBBON_VERTEX], [LINE_FRAGMENT]): отбрасывание фрагментов отключает ранний тест глубины у всех лент чертежа, а
 * стыки при 1,5 dp и так не видны.
 */
private fun ribbonVertex(capsule: Boolean) = DEPTH_BIAS_FUNCTION + """
void materialVertex(inout MaterialVertexInputs material) {
    vec4 custom = getCustom0();
    vec3 delta = custom.xyz;
    float side = sign(custom.w);
    float length2 = dot(delta, delta);
${if (capsule) CAPSULE_VERTEX_INIT else ""}
    if (length2 > 0.0) {
        float len = sqrt(length2);
        vec3 dir = delta / len;
        vec3 p = material.worldPosition.xyz;
        vec3 toCamera = getWorldCameraPosition() - p;
        vec3 across = cross(dir, toCamera);
        float acrossLength = length(across);
        if (acrossLength > 0.0) {
            across /= acrossLength;
            // торец — вдоль проекции направления на плоскость экрана, как и across: все углы квада в плоскости
            // поперёк луча взгляда, глубина квада — глубина самого отрезка, а не его наклонной плоскости (ленты разной
            // ширины и линия под ними иначе расходятся по глубине и дерутся за пиксели)
            vec3 ray = toCamera * inversesqrt(dot(toCamera, toCamera));
            vec3 along = dir - ray * dot(dir, ray);
            float alongLength = length(along);
            along = alongLength > 0.0001 ? along / alongLength : dir;
            float depth = (getClipFromWorldMatrix() * material.worldPosition).w;
            float worldPerPixel = 2.0 * depth / (getResolution().y * getClipFromViewMatrix()[1][1]);
            float halfWidth = materialParams.width * 0.5 * worldPerPixel;
            material.worldPosition.xyz += across * (side * halfWidth) - along * halfWidth;
${if (capsule) CAPSULE_VERTEX_CAP else ""}
${if (capsule) CAPSULE_VERTEX_GROUND else ""}
        }
    }
    material.worldPosition = biasDepth(material.worldPosition);
}
"""

// капсула: конец B — по модулю стороны; координаты вершины в ленте (м): от A вдоль отрезка, от B навстречу,
// поперёк — физическая сторона
private const val CAPSULE_VERTEX_INIT = """
    bool atB = abs(custom.w) > 1.5;
    material.cap = vec4(0.0, 0.0, 0.0, 1.0);"""
private const val CAPSULE_VERTEX_CAP = """
            float fromA = atB ? len + halfWidth : -halfWidth;
            float physical = atB ? -side : side;
            material.cap = vec4(fromA, len - fromA, physical * halfWidth, halfWidth);"""

private const val CAPSULE_GROUND_MAX = 0.05
// глубина капсулы — плоскости чертежа под ней, а не осевой линии: угол квада сдвигается вдоль луча взгляда на
// горизонтальную плоскость через вершину отрезка (точка на экране та же). Билборд по всей ширине лежит на глубине
// осевой, а пересекающая линия идёт по земле: в наклонном виде на ближней половине ленты земля ближе осевой на
// «смещение × ctg угла места», и линия побеждала обводку штрихами. Сдвиг ограничен долей CAPSULE_GROUND_MAX
// расстояния: у горизонта плоскость уходит в бесконечность, там лента остаётся билбордом
private const val CAPSULE_VERTEX_GROUND = """
            vec3 cam = getWorldCameraPosition();
            vec3 corner = material.worldPosition.xyz;
            float dy = corner.y - cam.y;
            if (abs(dy) > 1e-6) {
                float t = (p.y - cam.y) / dy;
                t = 1.0 + clamp(t - 1.0, -${CAPSULE_GROUND_MAX}, ${CAPSULE_GROUND_MAX});
                material.worldPosition.xyz = cam + (corner - cam) * t;
            }"""

/** Лента линий чертежа и обводок поверхностей: квадратные торцы, непрозрачная ([LINE_FRAGMENT]). */
val RIBBON_VERTEX = ribbonVertex(capsule = false)

/** Лента обводки выбранного: торцы круглые ([RIBBON_CAPSULE_FRAGMENT]). */
val RIBBON_CAPSULE_VERTEX = ribbonVertex(capsule = true)

/**
 * Фрагмент ленты обводки: торцы круглые. В `cap` (из [RIBBON_CAPSULE_VERTEX], интерполируется): расстояние вдоль
 * отрезка от A и от B, поперечное смещение и полуширина, м. За концом отрезка фрагмент остаётся, пока он не дальше
 * полуширины от конца: соседние отрезки ломаной стыкуются дисками без клиньев при любом угле. Смешивание MASKED — за
 * капсулой пусто. Только у обводки: линии чертежа — [LINE_FRAGMENT] без отбрасывания фрагментов.
 */
const val RIBBON_CAPSULE_FRAGMENT = """
void material(inout MaterialInputs material) {
    prepareMaterial(material);
    vec4 cap = variable_cap;
    float beyond = max(0.0, max(-cap.x, -cap.y));
    float d = length(vec2(beyond, cap.z));
    material.baseColor = vec4(materialParams.color, d <= cap.w ? 1.0 : 0.0);
}
"""

/**
 * Фрагмент подписей: альфа атласа — SDF символа, в альфе цвета вершины — полуширина сглаживания контура
 * (её кладёт вершинный шейдер по масштабу). Цвет предумножен на покрытие: смешивание TRANSPARENT.
 *
 * `haloPx` > 0 — проход ореола: символ целиком и поле вокруг цвета `haloColor` (второй порог SDF). Порог считает
 * вершинный шейдер ([TEXT_VERTEX]) и кладёт в `c.r` — цвет вершины ореолу не нужен; здесь порог только не выходит из
 * поля. Ореолы рисуются раньше текста ([TextQuads]): квад символа шире самого символа, и ореол следующей буквы иначе
 * лёг бы на предыдущую.
 */
val TEXT_FRAGMENT = """
void material(inout MaterialInputs material) {
    prepareMaterial(material);
    vec4 c = getColor();
    float d = texture(materialParams_atlas, getUV0()).a;
    if (materialParams.haloPx > 0.0) {
        float edge = max(c.r, c.a + 0.02);
        float h = smoothstep(edge - c.a, edge + c.a, d);
        material.baseColor = vec4(materialParams.haloColor * h, h);
    } else {
        float a = smoothstep(0.5 - c.a, 0.5 + c.a, d);
        material.baseColor = vec4(c.rgb * a, a);
    }
}
"""

/**
 * Вершинный шейдер подписей и значков точек. Вершина лежит в точке вставки (POSITION); CUSTOM0 — смещение
 * угла символа от пера в px атласа (xy), высота текста (z: > 0 — метры, < 0 — dp на экране), число для
 * прореживания (w: < 0 — не прореживать); CUSTOM1.xy — направление строки в горизонтальной плоскости сцены.
 *
 * Подпись — плашка, обращённая к камере: направление строки проецируется на экран, перевёрнутая строка
 * разворачивается на 180°. Размер на экране — по удалённости, но не меньше читаемого; не помещающаяся
 * рядом с более важными подпись схлопывается в точку (размеры не меняются: подписи одной высоты в файле одинаковы). Плашка подтянута к камере, чтобы нижняя часть не уходила в рельеф.
 *
 * В альфе цвета — полуширина сглаживания контура в единицах SDF; у значков (w < 0) своё поле [MARKER_SDF_RADIUS],
 * у букв — [ATLAS_SDF_RADIUS]. В проходе ореола (`haloPx` > 0) в `color.r` — порог SDF края ореола. Ореол подписи не
 * шире восьмой части кегля — у мелких подписей он не забивает буквы. Ореол значка — `haloPx`, пока на dp приходится
 * не больше [MARKER_HALO_PX_PER_DP] px атласа; у мелких значков все ореолы ужимаются в одной пропорции, и кант
 * выбранной точки остаётся видно.
 */
val TEXT_VERTEX = """
void materialVertex(inout MaterialVertexInputs material) {
    vec4 c0 = getCustom0();
    vec4 c1 = getCustom1();
    vec3 anchor = material.worldPosition.xyz;
    mat4 view = getViewFromWorldMatrix();
    vec3 right = vec3(view[0][0], view[1][0], view[2][0]);
    vec3 up = vec3(view[0][1], view[1][1], view[2][1]);
    vec3 back = vec3(view[0][2], view[1][2], view[2][2]);
    float depth = -dot(anchor - getWorldCameraPosition(), back);
    float pxPerMeter = getResolution().y * getClipFromViewMatrix()[1][1] * 0.5 / max(depth, 0.01);
    float height = c0.z;
    float fit = c0.w;
    float sizePx = height > 0.0 ? height * pxPerMeter : -height * materialParams.density;
    // кегль на экране: настоящий по DXF, не больше предела; в режиме отсева — не меньше читаемого, в режиме «все» —
    // не меньше минимального значка (подписи мелкие, но на месте)
    bool thin = materialParams.thin > 0.5 && fit >= 0.0;
    float drawPx = thin
        ? clamp(sizePx, materialParams.readablePx, materialParams.maxPx)
        : clamp(sizePx, materialParams.minMarkerPx, materialParams.maxPx);
    // fit·pxPerMeter — наибольший кегль в пикселях, при котором подпись не налезает на более важные;
    // не помещается — скрыта, а не уменьшена
    bool visible = depth > 0.01 && (!thin || fit * pxPerMeter >= drawPx);
    if (!visible) {
        material.worldPosition.xyz = anchor;
        material.color = vec4(0.0);
        return;
    }
    float worldHeight = drawPx / pxPerMeter;
    float scale = worldHeight / ${ATLAS_FONT_PX}.0;
    vec3 along = vec3(c1.x, 0.0, c1.y);
    vec2 a = vec2(dot(along, right), dot(along, up));
    float aLength = length(a);
    vec2 dir = aLength > 0.0001 ? a / aLength : vec2(1.0, 0.0);
    // строка не переворачивается вверх ногами; значок точки (fit < 0) поворачивается как есть
    if (dir.x < 0.0 && fit >= 0.0) {
        dir = -dir;
    }
    vec2 offset = (c0.x * dir + c0.y * vec2(-dir.y, dir.x)) * scale;
    // подтяжка к камере — вдоль луча взгляда, как в biasDepth: экранное положение значка не меняется. Сдвиг вдоль
    // оси камеры (back) уводил бы значок по экрану от вершины, тем сильнее, чем дальше он от центра кадра
    vec3 corner = anchor + right * offset.x + up * offset.y;
    float pull = clamp(worldHeight * 1.5 / depth, 0.0, 0.5);
    material.worldPosition.xyz = mix(corner, getWorldCameraPosition(), pull);
    float atlasPxPerScreenPx = 1.0 / (scale * pxPerMeter);
    bool marker = fit < 0.0;
    float sdfRadius = marker ? ${MARKER_SDF_RADIUS}.0 : ${ATLAS_SDF_RADIUS}.0;
    // сглаживание — в px атласа, одинаковое у букв и значков
    float smoothing = clamp(atlasPxPerScreenPx * 0.35, ${0.03 * ATLAS_SDF_RADIUS}, ${0.5 * ATLAS_SDF_RADIUS}) / sdfRadius;
    if (materialParams.haloPx > 0.0) {
        float haloAtlasPx = marker
            ? materialParams.haloPx * min(atlasPxPerScreenPx, ${MARKER_HALO_PX_PER_DP} / materialParams.density)
            : min(materialParams.haloPx * atlasPxPerScreenPx, ${ATLAS_FONT_PX / 8.0});
        material.color = vec4(0.5 - haloAtlasPx / (2.0 * sdfRadius), 0.0, 0.0, smoothing);
        return;
    }
    // подкраска параметром материала tint (rgb, доля в a): подсветка слоя меняет значение, а не сетку — буферы GPU не трогаются
    vec3 rgb = mix(material.color.rgb, materialParams.tint.rgb, materialParams.tint.a);
    material.color = vec4(rgb, smoothing);
}
"""
