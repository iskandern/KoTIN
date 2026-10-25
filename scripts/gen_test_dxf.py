#!/usr/bin/env python3
"""
Генератор тестовых DXF для задач KoTIN-N: по одному (или несколько) минимальному файлу на задачу.
Файлы — в app/src/jvmTest/resources/dxf.

  python3 -m venv .venv && .venv/bin/pip install ezdxf
  .venv/bin/python scripts/gen_test_dxf.py

Файлы детерминированы (фиксированные даты и GUID), пересоздание не даёт лишних изменений в git.
"""
import math
import os
import sys
from pathlib import Path

# порядок объектов в файле зависит от хэшей строк — фиксируем для воспроизводимости
if os.environ.get("PYTHONHASHSEED") != "0":
    os.execve(sys.executable, [sys.executable] + sys.argv, {**os.environ, "PYTHONHASHSEED": "0"})

import ezdxf
from ezdxf.lldxf.types import DXFTag
from ezdxf.enums import MTextEntityAlignment, TextEntityAlignment
from ezdxf.math import Vec2, Vec3
from ezdxf.render import forms

ezdxf.options.write_fixed_meta_data_for_testing = True  # даты и GUID фиксированы

OUT = Path(__file__).resolve().parent.parent / "app/src/jvmTest/resources/dxf"
OUT.mkdir(parents=True, exist_ok=True)


def new(version="R2018"):
    doc = ezdxf.new(version, setup=True)
    return doc


def save(doc, name, **kw):
    doc.saveas(OUT / name, **kw)
    print("  ", name)


def terrain(msp, x0, y0, nx, ny, step, z, layer="Surface", color=8):
    """Плоская/заданная функцией сетка из 3DFACE; z(x, y) -> отметка."""
    for i in range(nx):
        for j in range(ny):
            xs = (x0 + i * step, x0 + (i + 1) * step)
            ys = (y0 + j * step, y0 + (j + 1) * step)
            p = [(xs[0], ys[0]), (xs[1], ys[0]), (xs[1], ys[1]), (xs[0], ys[1])]
            v = [(x, y, z(x, y)) for x, y in p]
            msp.add_3dface([v[0], v[1], v[2]], dxfattribs={"layer": layer, "color": color})
            msp.add_3dface([v[0], v[2], v[3]], dxfattribs={"layer": layer, "color": color})


def cross(msp, x, y, size=1.0, layer="0"):
    msp.add_line((x - size, y), (x + size, y), dxfattribs={"layer": layer})
    msp.add_line((x, y - size), (x, y + size), dxfattribs={"layer": layer})


# --- KoTIN-1: SOLID/TRACE ----------------------------------------------------------------------
def task01():
    doc = new()
    msp = doc.modelspace()
    terrain(msp, -2, -2, 14, 8, 2, lambda x, y: 0.0)  # плоскость z=0, заливка на той же отметке
    # SOLID: порядок углов 1-2-4-3 ("бабочка" получится, если рисовать по порядку 1-2-3-4)
    msp.add_solid([(0, 0), (4, 0), (0, 4), (4, 4)], dxfattribs={"color": 1})
    # 3 угла (4-й совпадает с 3-м) — один треугольник
    msp.add_solid([(6, 0), (10, 0), (8, 4), (8, 4)], dxfattribs={"color": 3})
    # TRACE
    msp.add_trace([(12, 0), (16, 0), (12, 4), (16, 4)], dxfattribs={"color": 5})
    # true color и вынос по Z
    msp.add_solid([(0, 6), (3, 6), (0, 9), (3, 9)], dxfattribs={"true_color": 0xFF8000, "elevation": 0})
    msp.add_solid([(5, 6, 3), (8, 6, 3), (5, 9, 3), (8, 9, 3)], dxfattribs={"color": 6})
    # внутри блока с поворотом и масштабом
    blk = doc.blocks.new("ARROW")
    blk.add_solid([(0, 0), (2, 0), (0, 1), (2, 1)], dxfattribs={"color": 2})
    blk.add_solid([(2, -0.5), (3, 0.5), (2, 1.5), (2, 1.5)], dxfattribs={"color": 2})
    msp.add_blockref("ARROW", (10, 7), dxfattribs={"rotation": 30, "xscale": 1.5, "yscale": 1.5})
    msp.add_blockref("ARROW", (14, 8), dxfattribs={"rotation": 120, "xscale": -1, "yscale": 1})
    save(doc, "solid-trace.dxf")


# --- KoTIN-2: адаптивная гладкость дуг ---------------------------------------------------------
def task02():
    doc = new()
    msp = doc.modelspace()
    for i, r in enumerate((0.01, 0.1, 1, 10, 100, 1000)):
        msp.add_circle((i * 2500 - 6000, 0), r, dxfattribs={"color": 1 + i})
    msp.add_arc((0, 3000), 1500, 20, 200, dxfattribs={"color": 5})
    msp.add_ellipse((3000, 3000), major_axis=(1000, 0, 0), ratio=0.3, dxfattribs={"color": 6})
    msp.add_lwpolyline([(-6000, -3000, 0, 0, 0.5), (-5000, -3000, 0, 0, -1), (-4000, -3000, 0, 0, 0)],
                            format="xyseb", dxfattribs={"color": 3})
    save(doc, "arcs.dxf")


# --- KoTIN-3: рациональные сплайны -------------------------------------------------------------
def task03():
    doc = new()
    msp = doc.modelspace()
    k = math.sqrt(2) / 2
    # четверть окружности радиуса 10: вес √2/2 у средней точки
    sp = msp.add_spline(dxfattribs={"color": 1})
    sp.dxf.degree = 2
    sp.knots = [0, 0, 0, 1, 1, 1]
    sp.control_points = [(10, 0, 0), (10, 10, 0), (0, 10, 0)]
    sp.weights = [1, k, 1]
    # эталонная четверть окружности дугой поверх — кривая должна лечь на неё
    msp.add_arc((0, 0), 10, 0, 90, dxfattribs={"color": 3})
    # целая окружность из 9 контрольных точек (NURBS), радиус 5
    r = 5
    pts = [(r, 0), (r, r), (0, r), (-r, r), (-r, 0), (-r, -r), (0, -r), (r, -r), (r, 0)]
    sp2 = msp.add_spline(dxfattribs={"color": 5})
    sp2.dxf.degree = 2
    sp2.knots = [0, 0, 0, 0.25, 0.25, 0.5, 0.5, 0.75, 0.75, 1, 1, 1]
    sp2.control_points = [(x + 30, y, 0) for x, y in pts]
    sp2.weights = [1, k, 1, k, 1, k, 1, k, 1]
    msp.add_circle((30, 0), 5, dxfattribs={"color": 3})
    # обычный сплайн без весов — регрессия (форма не должна измениться)
    msp.add_spline([(0, -20), (5, -12), (10, -20), (15, -12), (20, -20)], degree=3, dxfattribs={"color": 6})
    # сплайн с весами = 1 (то же, что без весов)
    sp3 = msp.add_spline(dxfattribs={"color": 2})
    sp3.dxf.degree = 3
    sp3.knots = [0, 0, 0, 0, 1, 1, 1, 1]
    sp3.control_points = [(30, -20, 0), (33, -10, 0), (37, -30, 0), (40, -20, 0)]
    sp3.weights = [1, 1, 1, 1]
    save(doc, "rational-spline.dxf")


# --- KoTIN-4: LEADER ---------------------------------------------------------------------------
def task04():
    doc = new()
    msp = doc.modelspace()
    terrain(msp, -2, -2, 12, 8, 2, lambda x, y: 0.5 * math.sin(x / 3) + 0.3 * y / 4)
    # 3 вершины -> 2 отрезка, со стрелкой
    msp.add_leader([(0, 0), (4, 3), (8, 3)], dxfattribs={"color": 1})
    # без стрелки (71 = 0)
    ld = msp.add_leader([(0, 8), (4, 6), (8, 6)], dxfattribs={"color": 3})
    ld.dxf.has_arrowhead = 0
    # сплайновый LEADER (72 = 1): плавная кривая через вершины
    sp = msp.add_leader([(12, 0), (14, 3), (17, 2), (20, 5)], dxfattribs={"color": 5})
    sp.dxf.path_type = 1
    # на высоте и 2 вершины
    msp.add_leader([(12, 8, 3), (18, 9, 3)], dxfattribs={"color": 6})
    # в блоке с поворотом
    blk = doc.blocks.new("CALLOUT")
    blk.add_leader([(0, 0), (2, 1), (4, 1)], dxfattribs={"color": 2})
    msp.add_blockref("CALLOUT", (0, 12), dxfattribs={"rotation": 45})
    save(doc, "leader.dxf")


# --- KoTIN-5: PDMODE/PDSIZE --------------------------------------------------------------------
def task05():
    for mode, size, tag in ((0, 0, "0"), (2, 0, "2"), (3, 0, "3"), (4, 0, "4"), (34, 0, "34"), (66, 0, "66"),
                            (3, 2.5, "3-size-abs"), (3, -8, "3-size-pct")):
        doc = new("R2000")
        msp = doc.modelspace()
        doc.header["$PDMODE"] = mode
        doc.header["$PDSIZE"] = size
        terrain(msp, 0, 0, 5, 5, 2, lambda x, y: 0.4 * (x + y) / 4)
        for i in range(5):
            for j in range(5):
                msp.add_point((1 + i * 2, 1 + j * 2, 0.4 * (2 + i * 2 + j * 2) / 4), dxfattribs={"color": 1 + (i + j) % 7})
        save(doc, f"pdmode-{tag}.dxf")


# --- KoTIN-6: поворот подписей -----------------------------------------------------------------
def task06():
    doc = new()
    msp = doc.modelspace()
    cross(msp, 0, 0, 2)
    for ang in (0, 30, 60, 90, 135, 180, 225, 270, 315):
        msp.add_text(f"{ang}", height=0.8, rotation=ang, dxfattribs={"color": 1 + ang // 45 % 7}).set_placement((0, 0))
    # подписи вдоль наклонной линии (как подписи осей/пикетов)
    msp.add_line((10, -10), (40, 10))
    a = math.degrees(math.atan2(20, 30))
    for i in range(6):
        t = i / 5
        msp.add_text(f"ПК{i}+00", height=0.8, rotation=a).set_placement((10 + 30 * t, -10 + 20 * t))
    # перевёрнутая (читается вверх ногами при 180°) и MTEXT с поворотом
    msp.add_text("Вверх ногами 170", height=1, rotation=170).set_placement((10, 20))
    msp.add_mtext("MTEXT 45°", dxfattribs={"char_height": 1, "rotation": 45, "insert": (30, 20)})
    # плотная кучка подписей — алгоритм пропуска наложений (повёрнутые прямоугольники)
    for i in range(12):
        msp.add_text(f"т{i}", height=0.8, rotation=15 * i).set_placement((-20 + i * 0.5, -15 + (i % 3) * 0.3))
    save(doc, "rotated-labels.dxf")


# --- KoTIN-7: MESH -----------------------------------------------------------------------------
def task07():
    doc = new()
    msp = doc.modelspace()
    cube_v = [(0, 0, 0), (1, 0, 0), (1, 1, 0), (0, 1, 0), (0, 0, 1), (1, 0, 1), (1, 1, 1), (0, 1, 1)]
    cube_f = [(0, 3, 2, 1), (4, 5, 6, 7), (0, 1, 5, 4), (1, 2, 6, 5), (2, 3, 7, 6), (3, 0, 4, 7)]

    def mesh(origin, verts, faces, color, layer="0", **kw):
        m = msp.add_mesh(dxfattribs={"color": color, "layer": layer})
        with m.edit_data() as data:
            data.vertices = [(origin[0] + x * 4, origin[1] + y * 4, origin[2] + z * 4) for x, y, z in verts]
            data.faces = faces
        return m

    mesh((0, 0, 0), cube_v, cube_f, 1)  # куб: 8 вершин, 6 четырёхугольников -> 12 треугольников
    # шестигранная призма: две шестиугольные грани (> 4 углов — веер) + 6 четырёхугольников
    n = 6
    ring = [(math.cos(2 * math.pi * i / n) * 0.5 + 0.5, math.sin(2 * math.pi * i / n) * 0.5 + 0.5) for i in range(n)]
    pv = [(x, y, 0) for x, y in ring] + [(x, y, 1) for x, y in ring]
    pf = [tuple(reversed(range(n))), tuple(range(n, 2 * n))] + [(i, (i + 1) % n, n + (i + 1) % n, n + i) for i in range(n)]
    mesh((8, 0, 0), pv, pf, 3)
    # пирамида: четырёхугольное основание + 4 треугольника
    mesh((16, 0, 0), [(0, 0, 0), (1, 0, 0), (1, 1, 0), (0, 1, 0), (0.5, 0.5, 1)],
         [(0, 3, 2, 1), (0, 1, 4), (1, 2, 4), (2, 3, 4), (3, 0, 4)], 5)
    # куб с уровнем подразделения и складками: сглаживается Catmull–Clark, рёбра низа остаются острыми
    m = mesh((0, 8, 0), cube_v, cube_f, 6)
    m.dxf.subdivision_levels = 2
    with m.edit_data() as data:
        data.edges = [(0, 1), (1, 2), (2, 3), (3, 0)]
        data.edge_crease_values = [3.0, 3.0, 3.0, 3.0]
    # в блоке с поворотом и масштабом
    blk = doc.blocks.new("CUBE")
    bm = blk.add_mesh(dxfattribs={"color": 2})
    with bm.edit_data() as data:
        data.vertices = cube_v
        data.faces = cube_f
    msp.add_blockref("CUBE", (10, 8, 0), dxfattribs={"rotation": 45, "xscale": 3, "yscale": 3, "zscale": 3})
    save(doc, "mesh.dxf")


# --- KoTIN-8: HATCH сплошная -------------------------------------------------------------------
def task08():
    doc = new()
    msp = doc.modelspace()

    # 1. квадрат с отверстием (полилиния)
    h = msp.add_hatch(color=1)
    h.paths.add_polyline_path([(0, 0), (10, 0), (10, 10), (0, 10)], is_closed=True)
    h.paths.add_polyline_path([(3, 3), (7, 3), (7, 7), (3, 7)], is_closed=True, flags=ezdxf.const.BOUNDARY_PATH_DEFAULT)

    # 2. вложенность: остров внутри отверстия (внешний/дырка/остров)
    h = msp.add_hatch(color=3)
    h.paths.add_polyline_path([(14, 0), (30, 0), (30, 16), (14, 16)], is_closed=True)
    h.paths.add_polyline_path([(16, 2), (28, 2), (28, 14), (16, 14)], is_closed=True)
    h.paths.add_polyline_path([(20, 6), (24, 6), (24, 10), (20, 10)], is_closed=True)

    # 3. полукруг с bulge
    h = msp.add_hatch(color=5)
    h.paths.add_polyline_path([(34, 0, 1), (44, 0, 0)], is_closed=True)

    # 4. петля из 4 LINE (edge-путь)
    h = msp.add_hatch(color=6)
    p = h.paths.add_edge_path()
    for a, b in (((48, 0), (58, 0)), ((58, 0), (58, 10)), ((58, 10), (48, 10)), ((48, 10), (48, 0))):
        p.add_line(a, b)

    # 5. edge-путь из LINE + ARC (стадион) и круг целиком
    h = msp.add_hatch(color=2)
    p = h.paths.add_edge_path()
    p.add_line((0, 20), (10, 20))
    p.add_arc((10, 23), 3, -90, 90)
    p.add_line((10, 26), (0, 26))
    p.add_arc((0, 23), 3, 90, 270)
    h = msp.add_hatch(color=4)
    p = h.paths.add_edge_path()
    p.add_arc((20, 23), 4, 0, 360)

    # 6. эллипс-кромка и сплайн-кромка
    h = msp.add_hatch(color=30)
    p = h.paths.add_edge_path()
    p.add_ellipse((34, 23), major_axis=(5, 0), ratio=0.5, start_angle=0, end_angle=360)
    h = msp.add_hatch(color=40)
    p = h.paths.add_edge_path()
    p.add_spline([(46, 20), (48, 26), (52, 24), (56, 26)], degree=3)
    p.add_line((56, 26), (56, 20))
    p.add_line((56, 20), (46, 20))

    # 7. незамкнутая граница — молча пропускается (не должно падать)
    h = msp.add_hatch(color=7)
    p = h.paths.add_edge_path()
    p.add_line((0, 32), (10, 32))
    p.add_line((10, 32), (10, 40))
    p.add_line((10, 40), (2, 40))  # не доходит до (0, 32)

    # 8. цвет по слою и в блоке с поворотом/масштабом
    doc.layers.add("Zones", color=94)
    h = msp.add_hatch(color=256, dxfattribs={"layer": "Zones"})
    h.paths.add_polyline_path([(14, 32), (24, 32), (24, 40), (14, 40)], is_closed=True)
    blk = doc.blocks.new("ZONE")
    hb = blk.add_hatch(color=1)
    hb.paths.add_polyline_path([(0, 0), (4, 0), (4, 2), (0, 2)], is_closed=True)
    hb.paths.add_polyline_path([(1, 0.5), (3, 0.5), (3, 1.5), (1, 1.5)], is_closed=True)
    msp.add_blockref("ZONE", (30, 36), dxfattribs={"rotation": 30, "xscale": 2, "yscale": 2})

    # 9. «сотни хэтчей»: сетка 20x20 маленьких — замер времени чтения
    for i in range(20):
        for j in range(20):
            hh = msp.add_hatch(color=1 + (i + j) % 7)
            x, y = 70 + i * 1.2, j * 1.2
            hh.paths.add_polyline_path([(x, y), (x + 1, y), (x + 1, y + 1), (x, y + 1)], is_closed=True)
    save(doc, "hatch-solid.dxf")


# --- KoTIN-9: MULTILEADER ----------------------------------------------------------------------
def task09():
    doc = new()
    msp = doc.modelspace()
    from ezdxf.render.mleader import ConnectionSide
    ml = msp.add_multileader_mtext("Standard")
    ml.set_content("Выноска слева", color=1, char_height=1.0)
    ml.add_leader_line(ConnectionSide.left, [Vec2(0, 0)])
    ml.build(insert=Vec2(-8, 6))
    ml = msp.add_multileader_mtext("Standard")
    ml.set_content("Две линии", color=3, char_height=1.0)
    ml.add_leader_line(ConnectionSide.right, [Vec2(10, 0)])
    ml.add_leader_line(ConnectionSide.right, [Vec2(10, -4), Vec2(13, -2)])  # с изломом
    ml.build(insert=Vec2(18, 6))
    msp.add_circle((0, 0), 0.3)
    msp.add_circle((10, 0), 0.3)
    save(doc, "multileader.dxf")


# --- KoTIN-32: MULTILEADER с блоком и стиль MLEADERSTYLE -----------------------------------------
def task32():
    doc = new()
    msp = doc.modelspace()
    from ezdxf.render.mleader import BlockAlignment, ConnectionSide
    # блок-кружок R1 с центром в базовой точке, цвет BYBLOCK — берёт цвет блока выноски
    blk = doc.blocks.new("MARK")
    blk.add_circle((0, 0), 1.0, dxfattribs={"color": 0})
    blk.add_line((-0.5, 0), (0.5, 0), dxfattribs={"color": 0})
    # 1) выноска с кружком: синий блок в (10, 6), линия к (0, 0)
    ml = msp.add_multileader_block("Standard")
    ml.set_content("MARK", color=5, alignment=BlockAlignment.center_extents)
    ml.add_leader_line(ConnectionSide.left, [Vec2(0, 0)])
    ml.build(insert=Vec2(10, 6))
    # 2) блок вдвое больше и повёрнут на 30°: кружок R2 в (30, 6)
    ml = msp.add_multileader_block("Standard")
    ml.set_content("MARK", color=3, scale=2.0, alignment=BlockAlignment.center_extents)
    ml.add_leader_line(ConnectionSide.left, [Vec2(20, 0)])
    ml.build(insert=Vec2(30, 6), rotation=30)
    # 3) стиль RED: красная линия выноски; у сущности своя зелёная, но флаги 90 её не переопределяют — линия красная
    style = doc.mleader_styles.duplicate_entry("Standard", "RED")
    style.dxf.leader_line_color = ezdxf.colors.encode_raw_color(1)
    ml = msp.add_multileader_mtext("RED")
    ml.set_content("Стиль RED", char_height=1.0)
    ml.add_leader_line(ConnectionSide.left, [Vec2(0, -10)])
    ml.build(insert=Vec2(8, -6))
    ml.multileader.dxf.property_override_flags = 0
    ml.multileader.dxf.leader_line_color = ezdxf.colors.encode_raw_color(3)
    msp.add_circle((0, 0), 0.3)
    msp.add_circle((20, 0), 0.3)
    msp.add_circle((0, -10), 0.3)
    save(doc, "multileader-block.dxf")


# --- KoTIN-10: типы линий ----------------------------------------------------------------------
def task10():
    doc = new()
    msp = doc.modelspace()
    names = ["CONTINUOUS", "DASHED", "DASHED2", "CENTER", "PHANTOM", "DASHDOT", "DOT", "DIVIDE"]
    for i, name in enumerate(names):
        msp.add_line((0, -i * 3), (100, -i * 3), dxfattribs={"linetype": name, "color": 1 + i % 7})
    # фаза через вершины: рисунок не должен «перезапускаться» в каждой вершине
    msp.add_lwpolyline([(0, -30), (20, -30), (20, -34), (40, -34), (40, -30), (60, -30), (60, -34)],
                       dxfattribs={"linetype": "DASHED", "color": 2})
    # кривые: окружность и дуга
    msp.add_circle((130, -10), 15, dxfattribs={"linetype": "CENTER", "color": 5})
    msp.add_arc((130, -10), 20, 30, 300, dxfattribs={"linetype": "DASHED", "color": 6})
    # масштаб типа линий на сущности
    msp.add_line((0, -40), (100, -40), dxfattribs={"linetype": "DASHED", "ltscale": 0.25, "color": 3})
    msp.add_line((0, -43), (100, -43), dxfattribs={"linetype": "DASHED", "ltscale": 3, "color": 3})
    # тип линий на слое и BYLAYER
    doc.layers.add("Hidden", linetype="DASHED", color=4)
    msp.add_line((0, -46), (100, -46), dxfattribs={"layer": "Hidden", "linetype": "BYLAYER"})
    # BYBLOCK в блоке + масштаб вхождения
    blk = doc.blocks.new("DASHBOX")
    for a, b in (((0, 0), (10, 0)), ((10, 0), (10, 10)), ((10, 10), (0, 10)), ((0, 10), (0, 0))):
        blk.add_line(a, b, dxfattribs={"linetype": "DASHED"})
    msp.add_blockref("DASHBOX", (0, -70))
    msp.add_blockref("DASHBOX", (20, -70), dxfattribs={"xscale": 3, "yscale": 3, "rotation": 15})
    # слишком частый паттерн (глобальный ltscale крошечный в отдельной линии): должна нарисоваться сплошной
    msp.add_line((60, -60), (100, -60), dxfattribs={"linetype": "DOT", "ltscale": 0.0005, "color": 1})
    # очень длинная линия — проверка лимита штрихов
    msp.add_line((0, -90), (200000, -90), dxfattribs={"linetype": "DASHED", "color": 2})
    doc.header["$LTSCALE"] = 1.0
    save(doc, "linetypes.dxf")


# --- KoTIN-11: толщина линий -------------------------------------------------------------------
def task11():
    doc = new()
    doc.header["$LWDISPLAY"] = 1  # файл задачи открывается с весами линий
    msp = doc.modelspace()
    for i, lw in enumerate((0, 13, 25, 50, 100, 150, 211)):
        msp.add_line((0, -i * 3), (30, -i * 3), dxfattribs={"lineweight": lw, "color": 1 + i % 7})
    # по слою, по умолчанию и по блоку
    doc.layers.add("Heavy", lineweight=70, color=3)
    msp.add_line((0, -24), (30, -24), dxfattribs={"layer": "Heavy"})  # BYLAYER (-1 по умолчанию)
    msp.add_line((0, -27), (30, -27), dxfattribs={"lineweight": -3})  # DEFAULT
    blk = doc.blocks.new("WEIGHTED")
    blk.add_line((0, 0), (10, 0), dxfattribs={"lineweight": -2})  # BYBLOCK
    blk.add_line((0, 2), (10, 2), dxfattribs={"lineweight": 25})
    msp.add_blockref("WEIGHTED", (40, -3), dxfattribs={"lineweight": 100})
    # ломаная и окружность с толщиной (стыки вершин ленты)
    msp.add_lwpolyline([(40, -15), (50, -10), (60, -15), (70, -10)], dxfattribs={"lineweight": 60, "color": 6})
    msp.add_circle((85, -10), 8, dxfattribs={"lineweight": 80, "color": 5})
    save(doc, "lineweight.dxf")


# --- KoTIN-12: подписи и точки за поверхностью --------------------------------------------------
def task12():
    doc = new()
    msp = doc.modelspace()

    def hill(x, y):
        return 12 * math.exp(-(((x - 30) / 10) ** 2 + ((y - 20) / 10) ** 2))

    terrain(msp, 0, 0, 30, 20, 2, hill)
    # перед холмом, на вершине и за ним — вид сбоку (со стороны y=-∞) прячет заднюю группу
    for tag, x, y in (("перед", 30, 2), ("вершина", 30, 20), ("за", 30, 38), ("за-2", 20, 36), ("за-3", 40, 36)):
        z = hill(x, y)
        msp.add_point((x, y, z), dxfattribs={"color": 1})
        msp.add_text(tag, height=1).set_placement((x + 0.5, y + 0.5, z + 0.1))
    # подпись высоко над холмом — должна остаться видимой
    msp.add_text("над", height=1).set_placement((30, 30, 25))
    msp.add_point((30, 30, 25), dxfattribs={"color": 3})
    save(doc, "labels-behind-surface.dxf")


# --- KoTIN-13: бинарный DXF --------------------------------------------------------------------
def task13():
    def build(version):
        doc = new(version)
        msp = doc.modelspace()
        msp.add_line((0, 0, 0), (10, 5, 2), dxfattribs={"color": 1})
        msp.add_circle((5, 5), 3, dxfattribs={"color": 3})
        msp.add_arc((5, 5), 5, 10, 200, dxfattribs={"color": 5})
        msp.add_lwpolyline([(0, 10, 0, 0, 0.5), (4, 10), (4, 14), (0, 14)], format="xyseb", close=True)
        msp.add_point((12, 12, 3))
        msp.add_text("Текст кириллицей ё", height=1).set_placement((0, -3))
        msp.add_3dface([(14, 0, 0), (20, 0, 1), (20, 6, 2), (14, 6, 1)], dxfattribs={"color": 2})
        msp.add_spline([(14, 10), (16, 14), (18, 10), (20, 14)], degree=3, dxfattribs={"color": 6})
        blk = doc.blocks.new("B")
        blk.add_line((0, 0), (2, 0))
        blk.add_circle((1, 1), 0.5)
        msp.add_blockref("B", (24, 2), dxfattribs={"rotation": 20, "xscale": 2, "yscale": 2})
        return doc

    # один и тот же чертёж: ASCII и бинарный (R2018 — UTF-8; R2000 — кодовая страница, русский текст в cp1251)
    save(build("R2018"), "binary-ascii-twin.dxf")
    save(build("R2018"), "binary-r2018.dxf", fmt="bin")
    save(build("R2000"), "binary-r2000.dxf", fmt="bin")


# --- KoTIN-15: HATCH штриховки и градиенты -----------------------------------------------------
def task15():
    doc = new()
    msp = doc.modelspace()
    shapes = [(0, 0), (14, 0), (28, 0), (42, 0)]

    def box(h, x, y, w=10, hgt=8):
        h.paths.add_polyline_path([(x, y), (x + w, y), (x + w, y + hgt), (x, y + hgt)], is_closed=True)

    for (x, y), name in zip(shapes, ("ANSI31", "ANSI37", "DOTS", "SOLID")):
        h = msp.add_hatch(color=1 + shapes.index((x, y)))
        h.set_pattern_fill(name, scale=0.5)
        box(h, x, y)
    # штриховка с отверстием
    h = msp.add_hatch(color=2)
    h.set_pattern_fill("ANSI31", scale=0.4, angle=30)
    box(h, 0, 12, 14, 12)
    h.paths.add_polyline_path([(4, 15), (10, 15), (10, 21), (4, 21)], is_closed=True)
    # слишком частая штриховка: масштаб крошечный -> заливка средним цветом
    h = msp.add_hatch(color=3)
    h.set_pattern_fill("ANSI31", scale=0.01)
    box(h, 18, 12)
    # слишком редкая штриховка: масштаб огромный
    h = msp.add_hatch(color=5)
    h.set_pattern_fill("ANSI31", scale=20)
    box(h, 32, 12)
    # градиенты
    h = msp.add_hatch()
    h.set_gradient((255, 0, 0), (0, 0, 255), rotation=0)
    box(h, 0, 28, 12, 8)
    h = msp.add_hatch()
    h.set_gradient((0, 160, 0), (255, 255, 0), rotation=90)
    box(h, 16, 28, 12, 8)
    # в блоке с поворотом
    blk = doc.blocks.new("HATCHED")
    hb = blk.add_hatch(color=6)
    hb.set_pattern_fill("ANSI31", scale=0.3)
    hb.paths.add_polyline_path([(0, 0), (6, 0), (6, 4), (0, 4)], is_closed=True)
    msp.add_blockref("HATCHED", (34, 30), dxfattribs={"rotation": 35})
    save(doc, "hatch-patterns.dxf")


# --- KoTIN-16: стиль текста ----------------------------------------------------------------------
def task16():
    doc = new()
    msp = doc.modelspace()
    doc.styles.add("Narrow", font="arial.ttf", dxfattribs={"width": 0.5})
    doc.styles.add("Wide", font="arial.ttf", dxfattribs={"width": 2.0})
    doc.styles.add("Italic", font="arial.ttf", dxfattribs={"oblique": 20})
    doc.styles.add("FixedH", font="arial.ttf", dxfattribs={"height": 1.5})
    y = 0
    for style in ("Standard", "Narrow", "Wide", "Italic", "FixedH"):
        msp.add_text(f"Стиль {style}: ширина и наклон", height=1.5, dxfattribs={"style": style}).set_placement((0, y))
        y -= 3
    # выравнивание: рядом с маркером в точке привязки
    aligns = [("LEFT", TextEntityAlignment.LEFT), ("CENTER", TextEntityAlignment.CENTER),
              ("RIGHT", TextEntityAlignment.RIGHT), ("MIDDLE", TextEntityAlignment.MIDDLE),
              ("TOP_LEFT", TextEntityAlignment.TOP_LEFT), ("TOP_CENTER", TextEntityAlignment.TOP_CENTER),
              ("TOP_RIGHT", TextEntityAlignment.TOP_RIGHT), ("MIDDLE_LEFT", TextEntityAlignment.MIDDLE_LEFT),
              ("MIDDLE_CENTER", TextEntityAlignment.MIDDLE_CENTER), ("MIDDLE_RIGHT", TextEntityAlignment.MIDDLE_RIGHT),
              ("BOTTOM_LEFT", TextEntityAlignment.BOTTOM_LEFT), ("BOTTOM_CENTER", TextEntityAlignment.BOTTOM_CENTER),
              ("BOTTOM_RIGHT", TextEntityAlignment.BOTTOM_RIGHT)]
    for i, (name, al) in enumerate(aligns):
        x, yy = 40 + (i % 4) * 30, -(i // 4) * 8
        cross(msp, x, yy, 0.5)
        msp.add_text(name, height=1.2, dxfattribs={"color": 1 + i % 7}).set_placement((x, yy), align=al)
    # ALIGNED и FIT: заданы двумя точками
    cross(msp, 40, -40, 0.5); cross(msp, 60, -40, 0.5)
    msp.add_text("ALIGNED", height=1.2).set_placement((40, -40), (60, -40), align=TextEntityAlignment.ALIGNED)
    cross(msp, 70, -40, 0.5); cross(msp, 100, -40, 0.5)
    msp.add_text("FIT", height=1.2).set_placement((70, -40), (100, -40), align=TextEntityAlignment.FIT)
    # выровненный и повёрнутый
    cross(msp, 40, -50, 0.5)
    msp.add_text("CENTER + поворот 30°", height=1.2, rotation=30).set_placement((40, -50), align=TextEntityAlignment.CENTER)
    # MTEXT с привязкой
    for k, al in enumerate((MTextEntityAlignment.TOP_LEFT, MTextEntityAlignment.MIDDLE_CENTER, MTextEntityAlignment.BOTTOM_RIGHT)):
        cross(msp, 70 + k * 20, -50, 0.5)
        msp.add_mtext(f"MTEXT {al.name}", dxfattribs={"char_height": 1.0, "attachment_point": al.value, "insert": (70 + k * 20, -50)})
    save(doc, "text-style.dxf")


# --- KoTIN-23: 3DFACE с четырьмя углами и невидимыми рёбрами -----------------------------------
def task23():
    doc = new()
    msp = doc.modelspace()

    def box(x, y, size, h, color, invisible=0, target=msp):
        """Коробка из 6 четырёхугольных 3DFACE; invisible — код 70 у всех граней."""
        c = [(x, y, 0), (x + size, y, 0), (x + size, y + size, 0), (x, y + size, 0)]
        t = [(px, py, h) for px, py, _ in c]
        faces = [c, t] + [[c[i], c[(i + 1) % 4], t[(i + 1) % 4], t[i]] for i in range(4)]
        for f in faces:
            target.add_3dface(f, dxfattribs={"layer": "Box", "color": color, "invisible_edges": invisible})

    box(0, 0, 4, 3, 1)  # все рёбра видны: каркас — 12 рёбер куба, без диагоналей
    # плоский квадрат 70 = 5: в каркасе только правая и левая стороны
    msp.add_3dface([(8, 0, 0), (12, 0, 0), (12, 4, 0), (8, 4, 0)],
                   dxfattribs={"layer": "Flat", "color": 3, "invisible_edges": 5})
    # треугольники записью 4-й = 3-му и 4-й = 1-му — как раньше
    msp.add_3dface([(14, 0, 0), (18, 0, 0), (16, 4, 0), (16, 4, 0)], dxfattribs={"layer": "Tri", "color": 5})
    msp.add_3dface([(20, 0, 0), (24, 0, 0), (22, 4, 0), (20, 0, 0)], dxfattribs={"layer": "Tri", "color": 5})
    # коробка в блоке, вставлена с поворотом; у граней скрыты все рёбра — каркаса нет
    block = doc.blocks.new("BOX")
    box(0, 0, 3, 2, 4, invisible=15, target=block)
    msp.add_blockref("BOX", (0, 8), dxfattribs={"rotation": 30})
    save(doc, "3dface-quad.dxf")


# --- KoTIN-24: POLYLINE — сеть M×N и сглаженные полилинии ------------------------------------
def task24():
    doc = new()
    msp = doc.modelspace()
    # открытая сеть 6×8: холм
    m, n = 6, 8
    mesh = msp.add_polymesh((m, n), dxfattribs={"layer": "Mesh", "color": 3})
    for i in range(m):
        for j in range(n):
            x, y = i * 4.0, j * 4.0
            mesh.set_mesh_vertex((i, j), (x, y, 6 * math.exp(-((x - 10) ** 2 + (y - 14) ** 2) / 60)))
    # цилиндр: сеть 4×12, замкнутая по N (кольцо)
    m, n = 4, 12
    tube = msp.add_polymesh((m, n), dxfattribs={"layer": "Tube", "color": 5})
    for i in range(m):
        for j in range(n):
            a = 2 * math.pi * j / n
            tube.set_mesh_vertex((i, j), (40 + 5 * math.cos(a), 10 + 5 * math.sin(a), i * 3.0))
    tube.close(False, True)
    # сглаживание сплайном: рамка (флаг 16) и вершины кривой (флаг 8)
    frame = [(0, -20), (10, -10), (20, -30), (30, -20)]
    spline = msp.add_polyline2d([], dxfattribs={"layer": "Spline", "color": 1})
    spline.dxf.flags = 4
    spline.dxf.smooth_type = 6
    spline.append_vertices(frame, dxfattribs={"flags": 16})
    curve = ezdxf.math.BSpline([Vec3(p) for p in frame], order=4)
    spline.append_vertices([(p.x, p.y) for p in curve.approximate(19)], dxfattribs={"flags": 8})
    # рамка тонкой линией рядом для сравнения
    msp.add_lwpolyline(frame, dxfattribs={"layer": "Frame", "color": 8})
    save(doc, "polymesh.dxf")


# --- KoTIN-22: ARC_DIMENSION и LARGE_RADIAL_DIMENSION ---------------------------------------------
def task22():
    doc = new()
    msp = doc.modelspace()
    # дуга R10 и размер её длины
    msp.add_arc((0, 0), 10, 0, 90, dxfattribs={"color": 3})
    msp.add_arc_dim_3p(base=(14, 14), center=(0, 0), p1=(10, 0), p2=(0, 10), dxfattribs={"color": 1}).render()
    # дуга R40 с далёким центром: радиус с изломом. ezdxf такой сущности не умеет — пишем обычный радиус
    # и переименовываем его в LARGE_RADIAL_DIMENSION (геометрия всё равно в анонимном блоке)
    msp.add_arc((-10, -40), 40, 60, 120, dxfattribs={"color": 3})
    dim = msp.add_radius_dim(center=(-10, -40), radius=40, angle=90, dxfattribs={"color": 5, "layer": "LargeRadial"})
    dim.render()
    handle = dim.dimension.dxf.handle
    # повёрнутый блок с размером дуги: проверка трансформации вхождения
    blk = doc.blocks.new("ARCDIM")
    blk.add_arc((0, 0), 5, 0, 180, dxfattribs={"color": 3})
    blk.add_arc_dim_3p(base=(0, 8), center=(0, 0), p1=(5, 0), p2=(-5, 0), dxfattribs={"color": 6}).render()
    msp.add_blockref("ARCDIM", (40, 0), dxfattribs={"rotation": 45})

    import io
    stream = io.StringIO()
    doc.write(stream)
    text = stream.getvalue()
    head = f"  0\nDIMENSION\n  5\n{handle}\n"
    at = text.index(head)
    end = text.index("\n  0\n", at + len(head))
    entity = text[at:end].replace("DIMENSION", "LARGE_RADIAL_DIMENSION", 1)
    entity = entity.replace("AcDbRadialDimension\n", "AcDbRadialDimensionLarge\n", 1)
    text = text[:at] + entity + text[end:]
    (OUT / "arc-dimension.dxf").write_text(text, encoding="utf-8", newline="\n")
    print("   arc-dimension.dxf")


# --- KoTIN-27: MTEXT — перенос по ширине рамки и колонки ------------------------------------------
def task27():
    doc = new()
    msp = doc.modelspace()

    def frame(x, y, w, h, color=8):
        msp.add_lwpolyline([(x, y), (x + w, y), (x + w, y - h), (x, y - h)], close=True, dxfattribs={"color": color})

    words = "Абзац переносится по словам и не выходит за правую границу рамки шириной десять единиц"
    # рамка 10 × 1: абзац в несколько строк, привязка слева сверху
    frame(0, 0, 10, 8)
    cross(msp, 0, 0, 0.5)
    msp.add_mtext(words, dxfattribs={"char_height": 1.0, "width": 10, "insert": (0, 0), "color": 2})
    # привязка по центру сверху и справа снизу: строки выравниваются по рамке, а не по самой длинной строке
    frame(15, 0, 10, 8)
    cross(msp, 20, 0, 0.5)
    msp.add_mtext(words, dxfattribs={"char_height": 1.0, "width": 10, "insert": (20, 0), "color": 3,
                                     "attachment_point": MTextEntityAlignment.TOP_CENTER.value})
    frame(30, 0, 10, 8)
    cross(msp, 40, -8, 0.5)
    msp.add_mtext(words, dxfattribs={"char_height": 1.0, "width": 10, "insert": (40, -8), "color": 4,
                                     "attachment_point": MTextEntityAlignment.BOTTOM_RIGHT.value})
    # два абзаца (\P), неразрывный пробел (\~) между «100» и «м», повёрнутый на 30°
    msp.add_mtext("Первый абзац длиннее рамки и переносится.\\PВторой: ровно 100\\~м и ещё немного слов",
                  dxfattribs={"char_height": 1.0, "width": 12, "insert": (50, 0), "color": 5})
    msp.add_mtext(words, dxfattribs={"char_height": 1.0, "width": 10, "insert": (70, 0), "rotation": 30, "color": 6})
    # без ширины рамки — одна длинная строка, как раньше
    msp.add_mtext("Без ширины рамки строка не переносится", dxfattribs={"char_height": 1.0, "insert": (0, -14)})

    def words_to(n):
        return " ".join(f"слово{i}" for i in range(1, n + 1))

    # динамические колонки одной высоты: 3 по 8 ед., промежуток 2, высота 6 (4 строки) — текст продолжается в следующей
    for k in range(3):
        frame(k * 10, -20, 8, 6)
    msp.add_mtext_dynamic_auto_height_columns(words_to(12), width=8, gutter_width=2, height=6, count=3,
                                              dxfattribs={"char_height": 1.0, "insert": (0, -20), "color": 1})
    # динамические колонки с разной высотой: 4 (2 строки) и 8 (последняя берёт остаток)
    frame(40, -20, 8, 4)
    frame(50, -20, 8, 8)
    msp.add_mtext_dynamic_manual_height_columns(words_to(7), width=8, gutter_width=2, heights=[4, 8],
                                                dxfattribs={"char_height": 1.0, "insert": (40, -20), "color": 3})
    # статические колонки: разрыв колонки (\N) задан в тексте
    for k in range(2):
        frame(70 + k * 10, -20, 8, 6)
    msp.add_mtext_static_columns(["Первая колонка, короткий текст", "Вторая колонка после разрыва"],
                                 width=8, gutter_width=2, height=6,
                                 dxfattribs={"char_height": 1.0, "insert": (70, -20), "color": 5})
    save(doc, "mtext-wrap.dxf")


# --- KoTIN-25: ширина полилиний (40, 41, 43) ----------------------------------------------------
def task25():
    doc = new()
    msp = doc.modelspace()
    # рамка постоянной ширины (43): углы срезаны по биссектрисе, без щелей
    msp.add_lwpolyline([(0, 0), (20, 0), (20, 12), (0, 12)], close=True, dxfattribs={"const_width": 1.5, "color": 1})
    # зигзаг: острые и тупые углы
    msp.add_lwpolyline([(26, 0), (30, 10), (34, 0), (36, 10), (44, 2)], dxfattribs={"const_width": 0.8, "color": 3})
    # стрелка: древко 0,4, голова 3 → 0
    msp.add_lwpolyline([(0, 18, 0.4, 0.4), (10, 18, 3, 0), (14, 18, 0, 0)], format="xyse", dxfattribs={"color": 2})
    # «бублик»: две дуги bulge = 1 шириной 2
    msp.add_lwpolyline([(20, 18, 2, 2, 1), (28, 18, 2, 2, 1)], format="xyseb", close=True, dxfattribs={"color": 5})
    # дуга с шириной от 0 до 3 и прямой хвост без ширины (линия)
    msp.add_lwpolyline([(34, 14, 0, 3, 0.6), (44, 14, 0, 0, 0), (44, 22, 0, 0, 0)], format="xyseb", dxfattribs={"color": 6})
    # POLYLINE: ширина по умолчанию в заголовке, у одной вершины своя (трапеция 0,8 → 3)
    pl = msp.add_polyline2d([(0, 26), (8, 30), (16, 26), (24, 30)], dxfattribs={
        "default_start_width": 0.8, "default_end_width": 0.8, "color": 4})
    pl.vertices[1].dxf.start_width = 0.8
    pl.vertices[1].dxf.end_width = 3
    # блок с широкой полилинией: поворот 30°, масштаб 2 — ширина растёт вместе с блоком
    blk = doc.blocks.new("WIDE")
    blk.add_lwpolyline([(0, 0), (4, 0), (4, 3)], dxfattribs={"const_width": 0.5, "color": 256})
    msp.add_blockref("WIDE", (30, 26), dxfattribs={"rotation": 30, "xscale": 2, "yscale": 2, "color": 30})
    # для сравнения — та же рамка тонкой линией
    msp.add_lwpolyline([(0, 0), (20, 0), (20, 12), (0, 12)], close=True, dxfattribs={"color": 7})
    save(doc, "polyline-width.dxf")


# --- KoTIN-26: толщина (код 39) --------------------------------------------------------------------
def task26():
    doc = new()
    msp = doc.modelspace()
    # стенка из LINE, цилиндр из CIRCLE, дуга, замкнутая LWPOLYLINE — всё с толщиной
    msp.add_line((0, 0), (10, 0), dxfattribs={"thickness": 3, "color": 1})
    msp.add_circle((16, 4), 3, dxfattribs={"thickness": 5, "color": 3})
    msp.add_arc((26, 4), 3, 0, 180, dxfattribs={"thickness": 2, "color": 5})
    msp.add_lwpolyline([(0, 10), (8, 10), (8, 16), (0, 16)], close=True, dxfattribs={"thickness": 4, "color": 2})
    # SOLID с толщиной — призма, TRACE — тоже
    msp.add_solid([(12, 10), (16, 10), (12, 14), (16, 14)], dxfattribs={"thickness": 2, "color": 6})
    msp.add_trace([(20, 10), (24, 10), (20, 12), (24, 12)], dxfattribs={"thickness": 1, "color": 4})
    # точки «Полевика»: толщина 2 у каждой — палки только по переключателю «Толщина точек»
    doc.header["$PDMODE"] = 3
    for i in range(5):
        msp.add_point((2 + i * 2, 22), dxfattribs={"thickness": 2, "color": 7})
    # текст с толщиной — не выдавливается
    msp.add_text("THICK", dxfattribs={"height": 1.5, "thickness": 3, "insert": (14, 20), "color": 7})
    # блок со стенкой, вставка с масштабом по Z = 3: высота 1 → 3
    blk = doc.blocks.new("WALL")
    blk.add_line((0, 0), (4, 0), dxfattribs={"thickness": 1, "color": 256})
    msp.add_blockref("WALL", (20, 18), dxfattribs={"zscale": 3, "color": 30})
    # для сравнения — та же линия без толщины
    msp.add_line((0, -3), (10, -3), dxfattribs={"color": 1})
    save(doc, "thickness.dxf")


# --- KoTIN-34: константные ATTDEF и многострочные атрибуты --------------------------------------
def task34():
    doc = new()
    msp = doc.modelspace()
    blk = doc.blocks.new("PK")
    blk.add_line((-1, 0), (1, 0))
    blk.add_line((0, -1), (0, 1))
    # константный ATTDEF — подпись у каждой вставки
    blk.add_attdef("PK", (0.3, 0.3), "ПК", dxfattribs={"flags": 2, "height": 0.5, "color": 2})
    # невидимый константный и обычный без ATTRIB — не видны
    blk.add_attdef("HIDDEN", (0.3, -0.8), "скрытый", dxfattribs={"flags": 3, "height": 0.5})
    blk.add_attdef("NAME", (0.3, -0.8), "без ATTRIB", dxfattribs={"height": 0.5})
    # константный многострочный ATTDEF
    multi = blk.add_attdef("NOTE", (0.3, -1.5), "", dxfattribs={"flags": 2, "height": 0.4, "color": 4})
    note = ezdxf.entities.MText.new(dxfattribs={"insert": (0.3, -1.5), "char_height": 0.4})
    note.text = "конст.\\Pдве строки"
    multi.embed_mtext(note)
    for i in range(3):
        msp.add_blockref("PK", (i * 10, 0), dxfattribs={"rotation": 15 * i})

    # многострочный ATTRIB у вставки (вложенный MTEXT после кода 101)
    tag = doc.blocks.new("TAG")
    tag.add_circle((0, 0), 0.5)
    tag.add_attdef("INFO", (1, 0), "", dxfattribs={"height": 0.5})
    ref = msp.add_blockref("TAG", (0, 10))
    attrib = ref.add_attrib("INFO", "", (1, 10), dxfattribs={"height": 0.5, "color": 3})
    mt = ezdxf.entities.MText.new(dxfattribs={"insert": (1, 10), "char_height": 0.5})
    mt.text = "Строка 1\\PСтрока 2"
    attrib.embed_mtext(mt)
    # обычный однострочный ATTRIB рядом — для сравнения
    ref2 = msp.add_blockref("TAG", (12, 10))
    ref2.add_attrib("INFO", "одна строка", (13, 10), dxfattribs={"height": 0.5, "color": 1})
    save(doc, "attdef.dxf")


# --- KoTIN-37: XLINE и RAY ----------------------------------------------------------------------
def task37():
    doc = new()
    msp = doc.modelspace()
    # рамка 20×12 задаёт габариты сцены
    msp.add_lwpolyline([(0, 0), (20, 0), (20, 12), (0, 12)], close=True, dxfattribs={"color": 7})
    # красная прямая через (0, 0) по диагонали (1, 1) — уходит за край в обе стороны
    msp.add_xline((0, 0), (1, 1), dxfattribs={"color": 1})
    # зелёный луч из центра рамки вправо — только в одну сторону
    msp.add_circle((10, 6), 0.5, dxfattribs={"color": 3})
    msp.add_ray((10, 6), (1, 0), dxfattribs={"color": 3})
    # синяя штриховая вертикаль x = 5 на своём слое
    doc.layers.add("XL", color=5, linetype="DASHED")
    msp.add_xline((5, 0), (0, 1), dxfattribs={"layer": "XL"})
    # жёлтый луч в блоке, повёрнутом на 90°: идёт вверх от (15, 3)
    blk = doc.blocks.new("RAYB")
    blk.add_ray((0, 0), (1, 0), dxfattribs={"color": 0})
    blk.add_circle((0, 0), 0.5, dxfattribs={"color": 0})
    msp.add_blockref("RAYB", (15, 3), dxfattribs={"rotation": 90, "color": 2})
    save(doc, "xline-ray.dxf")


def task38():
    doc = new()
    msp = doc.modelspace()
    terrain(msp, -10, -10, 4, 5, 10, lambda x, y: 0)
    # пружина: радиус 5, шаг 2, 3 витка, ось — z через (0, 0)
    msp.add_helix(5, 2, 3, dxfattribs={"color": 1})
    # левая, сдвинута на (20, 0, 0)
    msp.add_helix(3, 4, 2, ccw=False, dxfattribs={"color": 3}).translate(20, 0, 0)
    # в блоке: вставка с поворотом 30° и масштабом 2
    blk = doc.blocks.new("SPRING")
    blk.add_helix(2, 1, 4, dxfattribs={"color": 256})
    msp.add_blockref("SPRING", (0, 25), dxfattribs={"rotation": 30, "xscale": 2, "yscale": 2, "zscale": 2, "color": 5})
    save(doc, "helix.dxf")


# --- KoTIN-39: MPOLYGON ------------------------------------------------------------------------
def task39():
    doc = new()
    msp = doc.modelspace()
    # красный контур, зелёная сплошная заливка с квадратным отверстием
    mp = msp.add_mpolygon(color=1, fill_color=3)
    mp.paths.add_polyline_path([(0, 0), (10, 0), (10, 10), (0, 10)], is_closed=True)
    mp.paths.add_polyline_path([(3, 3), (7, 3), (7, 7), (3, 7)], is_closed=True)
    # жёлтый контур с дугой (bulge) и штриховка ANSI31 тем же цветом
    mp = msp.add_mpolygon(color=2)
    mp.paths.add_polyline_path([(14, 0, 0), (24, 0, 0.5), (24, 10, 0), (14, 10, 0)], is_closed=True)
    mp.set_pattern_fill("ANSI31", color=2, scale=0.5)  # без color ezdxf ставит сущности 7
    # контур по слою, заливка синяя
    doc.layers.add("Parcels", color=6)
    mp = msp.add_mpolygon(color=256, fill_color=5, dxfattribs={"layer": "Parcels"})
    mp.paths.add_polyline_path([(28, 0), (38, 0), (33, 8)], is_closed=True)
    # в блоке с поворотом 30° и масштабом 2: белый контур, красная заливка с отверстием
    blk = doc.blocks.new("PARCEL")
    mb = blk.add_mpolygon(color=7, fill_color=1)
    mb.paths.add_polyline_path([(0, 0), (4, 0), (4, 2), (0, 2)], is_closed=True)
    mb.paths.add_polyline_path([(1, 0.5), (3, 0.5), (3, 1.5), (1, 1.5)], is_closed=True)
    msp.add_blockref("PARCEL", (2, 16), dxfattribs={"rotation": 30, "xscale": 2, "yscale": 2})
    save(doc, "mpolygon.dxf")


# --- KoTIN-36: SPLINE по опорным точкам --------------------------------------------------------
def task36():
    doc = new()
    msp = doc.modelspace()

    def fit_spline(points, start=None, end=None, closed=False, color=1):
        # только опорные точки (11/21/31) и касательные (12, 13), без контрольных и узлов
        sp = msp.add_spline(dxfattribs={"color": color})
        sp.dxf.degree = 3
        sp.fit_points = points
        if start:
            sp.dxf.start_tangent = start
        if end:
            sp.dxf.end_tangent = end
        if closed:
            sp.closed = True
        # серая ломаная через те же точки — для сравнения
        msp.add_lwpolyline([p[:2] for p in points], close=closed, dxfattribs={"color": 8})

    def arc_pts(cx, cy, r, a0, a1, n):
        return [(cx + r * math.cos(math.radians(a0 + (a1 - a0) * i / (n - 1))),
                 cy + r * math.sin(math.radians(a0 + (a1 - a0) * i / (n - 1))), 0) for i in range(n)]

    # красный полукруг R10 через 5 точек с касательными; зелёная эталонная дуга под ним
    fit_spline(arc_pts(0, 0, 10, 0, 180, 5), start=(0, 1, 0), end=(0, -1, 0), color=1)
    msp.add_arc((0, 0), 10, 0, 180, dxfattribs={"color": 3})
    # жёлтая «волна» без касательных (естественные концы)
    fit_spline([(20, 0, 0), (25, 8, 0), (30, 0, 0), (35, 8, 0), (40, 0, 0), (45, 8, 0)], color=2)
    # синий замкнутый сплайн через 6 точек окружности R6 — гладкий круг без излома в первой точке
    fit_spline(arc_pts(60, 4, 6, 0, 300, 6), closed=True, color=5)
    # пурпурный сплайн в 3D (отметки 0…6) через 4 точки
    fit_spline([(0, -20, 0), (8, -14, 3), (16, -22, 6), (24, -16, 2)], color=6)
    # бирюзовый хэтч: верхняя кромка — сплайн по опорным точкам, низ — отрезок
    h = msp.add_hatch(color=4)
    path = h.paths.add_edge_path()
    path.add_spline(fit_points=[p[:2] for p in arc_pts(40, -20, 8, 0, 180, 5)], start_tangent=(0, 1), end_tangent=(0, -1))
    path.add_line((32, -20), (48, -20))
    save(doc, "fit-spline.dxf")


# --- KoTIN-35: отражённый и перевёрнутый текст (код 71) ----------------------------------------
def task35():
    doc = new()
    msp = doc.modelspace()
    # строки: обычный, отражённый по X (2), перевёрнутый (4), оба флага (6); маркер — точка вставки
    for row, flags in enumerate((0, 2, 4, 6)):
        y = -row * 6
        cross(msp, 0, y, 0.5)
        msp.add_text(f"ABC 71={flags}", height=1.5, dxfattribs={"insert": (0, y), "text_generation_flag": flags, "color": 1 + row})
        # выравнивание «справа»: при отражении строка уходит вправо от точки
        cross(msp, 40, y, 0.5)
        msp.add_text(f"RIGHT 71={flags}", height=1.5, dxfattribs={"text_generation_flag": flags, "color": 1 + row}) \
            .set_placement((40, y), align=TextEntityAlignment.RIGHT)
    # повёрнутый на 30° и отражённый
    cross(msp, 60, 0, 0.5)
    msp.add_text("ROT 30 71=2", height=1.5, rotation=30, dxfattribs={"insert": (60, 0), "text_generation_flag": 2, "color": 6})
    # строка «вписанный» между точками, перевёрнутая: под линией между маркерами
    cross(msp, 60, -18, 0.5); cross(msp, 80, -18, 0.5)
    msp.add_text("FIT 71=4", height=1.5, dxfattribs={"text_generation_flag": 4, "color": 4}) \
        .set_placement((60, -18), (80, -18), align=TextEntityAlignment.FIT)
    msp.add_line((60, -18), (80, -18), dxfattribs={"color": 8})
    save(doc, "text-mirror.dxf")


# --- KoTIN-29: порядок прорисовки (SORTENTSTABLE) ------------------------------------------------
def task29():
    doc = new()
    msp = doc.modelspace()

    def square(x, y, size, color):
        return msp.add_solid([(x, y), (x + size, y), (x, y + size), (x + size, y + size)], dxfattribs={"color": color})

    # без таблицы: красный, затем зелёный — зелёный сверху
    square(0, 0, 8, 1)
    square(4, 4, 8, 3)
    # с таблицей: красный, затем зелёный, но таблица поднимает красный наверх
    red = square(16, 0, 8, 1)
    square(20, 4, 8, 3)
    # синий хэтч, жёлтая широкая полилиния поверх, белый хэтч поверх обоих; таблица опускает белый под низ
    blue = msp.add_hatch(color=5)
    blue.paths.add_polyline_path([(32, 0), (42, 0), (42, 10), (32, 10)], is_closed=True)
    msp.add_lwpolyline([(30, 5), (44, 5)], dxfattribs={"color": 2, "const_width": 3})
    white = msp.add_hatch(color=7)
    white.paths.add_polyline_path([(36, -2), (40, -2), (40, 12), (36, 12)], is_closed=True)
    msp.set_redraw_order({red.dxf.handle: "FFFF", white.dxf.handle: "1"})
    save(doc, "draworder.dxf")

    # холм из 3DFACE и 200 перекрывающихся SOLID на высоте 0,5: в наклонном виде заливки за холмом скрыты им,
    # ступени порядка прорисовки не вытягивают их сквозь рельеф
    doc = new()
    msp = doc.modelspace()

    def hill(x, y):
        return 60 * math.exp(-((x - 200) ** 2 + (y - 200) ** 2) / (2 * 50 ** 2))

    for i in range(40):
        for j in range(40):
            quad = [(i * 10, j * 10), ((i + 1) * 10, j * 10), ((i + 1) * 10, (j + 1) * 10), (i * 10, (j + 1) * 10)]
            msp.add_3dface([(x, y, hill(x, y)) for x, y in quad], dxfattribs={"layer": "SURF"})
    for k in range(200):
        x, y = 20 + (k // 10) * 18, 20 + (k % 10) * 36
        msp.add_solid([(x, y), (x + 30, y), (x, y + 30), (x + 30, y + 30)],
                      dxfattribs={"layer": "F%d" % (k % 7), "color": 1 + k % 7, "elevation": 0.5})
    save(doc, "draworder-hill.dxf")


# --- KoTIN-40: MLINE и MLINESTYLE --------------------------------------------------------------
def task40():
    from ezdxf.entities import MLineStyle
    doc = new()
    msp = doc.modelspace()
    # STANDARD (+0,5 и −0,5) из 3 вершин: две белые ломаные на расстоянии 1
    msp.add_mline([(0, 0), (10, 0), (10, 10)])
    # три элемента: красный +1, зелёный штриховой по оси, синий −1; жёлтая заливка, скосы в изломах,
    # торец в начале — линией, в конце — дугой снаружи и внутренними дугами
    triple = doc.mline_styles.new("TRIPLE")
    triple.elements.append(1.0, 1)
    triple.elements.append(0.0, 3, "DASHED")
    triple.elements.append(-1.0, 5)
    triple.dxf.flags = MLineStyle.FILL | MLineStyle.MITER | MLineStyle.START_SQUARE | MLineStyle.END_ROUND
    triple.dxf.fill_color = 2
    msp.add_mline([(20, 0), (30, 0), (35, 8), (45, 8)], dxfattribs={"style_name": "TRIPLE"})
    # четыре элемента с внутренними дугами на обоих концах, масштаб 2
    quad = doc.mline_styles.new("QUAD")
    for offset, color in ((1.5, 6), (0.5, 4), (-0.5, 4), (-1.5, 6)):
        quad.elements.append(offset, color)
    quad.dxf.flags = MLineStyle.START_ROUND | MLineStyle.START_INNER_ARC | MLineStyle.END_ROUND | MLineStyle.END_INNER_ARC
    msp.add_mline([(0, -20), (20, -20)], dxfattribs={"style_name": "QUAD", "scale_factor": 2})
    # замкнутая STANDARD: двойной прямоугольник без торцов, масштаб 3, выравнивание «сверху»
    msp.add_mline([(30, -26), (45, -26), (45, -16), (30, -16)], close=True,
                  dxfattribs={"scale_factor": 3, "justification": 0, "color": 2})
    # в блоке с поворотом 30°: TRIPLE по слою Walls (пурпурный)
    doc.layers.add("Walls", color=6)
    blk = doc.blocks.new("WALL")
    blk.add_mline([(0, 0), (8, 0), (8, 4)], dxfattribs={"style_name": "TRIPLE", "layer": "0"})
    msp.add_blockref("WALL", (0, 20), dxfattribs={"rotation": 30, "layer": "Walls"})
    save(doc, "mline.dxf")


# --- KoTIN-44: поворот значка POINT (код 50) --------------------------------------------------
def task44():
    for mode, tag in ((3, "cross"), (4, "tick")):
        doc = new("R2000")
        msp = doc.modelspace()
        doc.header["$PDMODE"] = mode
        doc.header["$PDSIZE"] = 1.5
        # столбцы — углы 0, 45, 90, 180; нижняя строка — точки, верхняя — в блоке, повёрнутом на 45°
        for col, angle in enumerate((0, 45, 90, 180)):
            x = col * 4
            msp.add_point((x, 0), dxfattribs={"angle": angle, "color": 1 + col})
            msp.add_text(str(angle), height=0.5).set_placement((x - 0.5, -2))
        blk = doc.blocks.new("PT")
        blk.add_point((0, 0), dxfattribs={"color": 6})
        msp.add_blockref("PT", (0, 4), dxfattribs={"rotation": 45})
        msp.add_text("INSERT 45", height=0.5).set_placement((1, 4))
        msp.add_line((-2, -3), (14, -3), dxfattribs={"color": 8})
        save(doc, f"point-angle-{tag}.dxf")


# --- KoTIN-42: TOLERANCE -------------------------------------------------------------------------
def task42():
    doc = new()
    msp = doc.modelspace()
    # стиль размеров: текст 2,5, зазор 0,625 — строка рамки высотой 3,75
    doc.dimstyles.new("GDT", dxfattribs={"dimtxt": 2.5, "dimgap": 0.625, "dimscale": 1})

    def tol(target, content, insert, color, x_axis=(1, 0, 0)):
        target.new_entity("TOLERANCE", dxfattribs={
            "dimstyle": "GDT", "insert": insert, "content": content, "x_axis_vector": x_axis, "color": color,
        })
        if target is msp:
            cross(msp, insert[0], insert[1], 0.5)

    # красная позиция: ⌖ | ⌀0.1Ⓜ | A | B | C
    tol(msp, "{\\Fgdt;j}%%v{\\Fgdt;n}0.1{\\Fgdt;m}%%vA%%vB%%vC", (0, 0), 1)
    # зелёная двухстрочная: плоскостность и прямолинейность (^J — вторая строка)
    tol(msp, "{\\Fgdt;c}%%v0.05^J{\\Fgdt;u}%%v0.02", (0, -10), 3)
    # жёлтая: все символы шрифта GDT по ячейке
    tol(msp, "%%v".join("{\\Fgdt;" + ch + "}" for ch in "abcdefghijk"), (0, -22), 2)
    tol(msp, "%%v".join("{\\Fgdt;" + ch + "}" for ch in "lmnprstu"), (0, -28), 2)
    # синяя повёрнутая на 30°
    tol(msp, "{\\Fgdt;b}%%v0.2%%vA", (50, 0), 5, x_axis=(math.cos(math.radians(30)), math.sin(math.radians(30)), 0))
    # бирюзовая: только обозначение базы
    tol(msp, "A", (50, -12), 4)
    # пурпурная в блоке: поворот 45°, масштаб 2
    blk = doc.blocks.new("FCF")
    tol(blk, "{\\Fgdt;f}%%v0.1%%vA", (0, 0), 6)
    msp.add_blockref("FCF", (50, -25), dxfattribs={"rotation": 45, "xscale": 2, "yscale": 2})
    cross(msp, 50, -25, 0.5)
    save(doc, "tolerance.dxf")


class Sat:
    """Тело ACIS 7.0 в тексте SAT вручную: ezdxf.acis пишет только сетки из отрезков, без дуг."""

    def __init__(self):
        self.recs = []
        self.vertices = {}

    def new(self, name):
        self.recs.append([name])
        return len(self.recs) - 1

    def put(self, i, *fields):
        self.recs[i] = [self.recs[i][0]] + [f"${f}" if isinstance(f, int) else str(f) for f in fields]

    def vertex(self, p):
        if p not in self.vertices:
            v, pt = self.new("vertex"), self.new("point")
            self.put(pt, -1, *(f"{c:g}" for c in p))
            self.vertices[p] = [v, pt, None]
        return self.vertices[p]

    def edge(self, p1, p2, curve):
        """Ребро от p1 к p2; curve — ("line",) или ("circle", центр, нормаль, большая полуось)."""
        e, c = self.new("edge"), self.new("straight-curve" if curve[0] == "line" else "ellipse-curve")
        v1, v2 = self.vertex(p1), self.vertex(p2)
        for v in (v1, v2):
            if v[2] is None:
                v[2] = e
                self.put(v[0], -1, e, v[1])
        if curve[0] == "line":
            d = [b - a for a, b in zip(p1, p2)]
            n = math.hypot(*d)
            self.put(c, -1, *(f"{x:g}" for x in p1), *(f"{x / n:g}" for x in d), "I", "I")
            end = n
        else:
            self.put(c, -1, *(f"{x:g}" for x in (*curve[1], *curve[2], *curve[3])), "1", "I", "I")
            end = f"{2 * math.pi:g}"
        return [e, v1[0], 0, v2[0], end, c]

    def face(self, surface, loops):
        """loops: списки (ребро, reversed); surface — имя и поля поверхности."""
        f, s = self.new("face"), self.new(surface[0])
        self.put(s, -1, *surface[1:])
        first = None
        for loop in loops:
            lp = self.new("loop")
            cos = [self.new("coedge") for _ in loop]
            for k, ((edge, rev), co) in enumerate(zip(loop, cos)):
                self.put(co, -1, cos[(k + 1) % len(cos)], cos[k - 1], -1, edge[0], "reversed" if rev else "forward", lp, -1)
                if len(edge) == 6:  # ребро ещё не записано: первая coedge — его
                    e, v1, sp, v2, ep, c = edge
                    self.put(e, -1, v1, str(sp), v2, str(ep), co, c, "forward", "@7", "unknown")
                    edge.append(True)
            first = first if first is not None else lp
            self.put(lp, -1, -1, cos[0], f)  # next_loop проставляется ниже
            if lp != first:
                prev = self.recs[first]
                while prev[2] != "$-1":
                    prev = self.recs[int(prev[2][1:])]
                prev[2] = f"${lp}"
        self.faces.append((f, first, s))

    def body(self, build):
        self.faces = []
        body, lump, shell = self.new("body"), self.new("lump"), self.new("shell")
        build(self)
        for k, (f, lp, s) in enumerate(self.faces):
            nxt = self.faces[k + 1][0] if k + 1 < len(self.faces) else -1
            # грань без петель — замкнутая поверхность целиком (сфера, тор)
            self.put(f, -1, nxt, lp if lp is not None else -1, shell, -1, s, "forward", "single")
        self.put(body, -1, lump, -1, -1)
        self.put(lump, -1, -1, shell, body)
        self.put(shell, -1, -1, -1, self.faces[0][0], -1, lump)
        lines = ["700 0 1 0", "@5 KoTIN @8 ACIS 7.0 @24 Thu Jan 01 00:00:00 2026", "1 9.9999999999999995e-07 1e-10"]
        lines += [" ".join([r[0], "$-1", "-1"] + r[1:] + ["#"]) for r in self.recs]
        lines.append("End-of-ACIS-data")
        return lines


def plane(origin, normal, u):
    return ("plane-surface", *(f"{x:g}" for x in (*origin, *normal, *u)), "forward_v", "I", "I", "I", "I")


def sat_plate(x0, y0, w, h, t, r):
    """Плита w×h×t с круглым сквозным отверстием радиуса r по центру: 6 плоских граней и цилиндр."""
    def build(sat):
        b = [(x0, y0, 0), (x0 + w, y0, 0), (x0 + w, y0 + h, 0), (x0, y0 + h, 0)]
        top = [(x, y, t) for x, y, _ in b]
        cx, cy = x0 + w / 2, y0 + h / 2
        bl = [sat.edge(b[i], b[(i + 1) % 4], ("line",)) for i in range(4)]
        tl = [sat.edge(top[i], top[(i + 1) % 4], ("line",)) for i in range(4)]
        vl = [sat.edge(b[i], top[i], ("line",)) for i in range(4)]
        hb = sat.edge((cx + r, cy, 0), (cx + r, cy, 0), ("circle", (cx, cy, 0), (0, 0, 1), (r, 0, 0)))
        ht = sat.edge((cx + r, cy, t), (cx + r, cy, t), ("circle", (cx, cy, t), (0, 0, 1), (r, 0, 0)))
        seam = sat.edge((cx + r, cy, 0), (cx + r, cy, t), ("line",))
        # верх: внешняя петля против часовой вокруг +z, отверстие — по часовой
        sat.face(plane(top[0], (0, 0, 1), (1, 0, 0)), [[(e, False) for e in tl], [(ht, True)]])
        sat.face(plane(b[0], (0, 0, -1), (1, 0, 0)), [[(bl[i], True) for i in (3, 2, 1, 0)], [(hb, False)]])
        for i in range(4):
            j = (i + 1) % 4
            d = [q - p for p, q in zip(b[i], b[j])]
            normal = (d[1] / math.hypot(*d), -d[0] / math.hypot(*d), 0)
            sat.face(plane(b[i], normal, (0, 0, 1)), [[(bl[i], False), (vl[j], False), (tl[i], True), (vl[i], True)]])
        cone = (cx, cy, 0, 0, 0, 1, r, 0, 0, 1, "I", "I", 0, 1, r, "forward", "I", "I", "I", "I")
        sat.face(("cone-surface", *(x if isinstance(x, str) else f"{x:g}" for x in cone)),
                 [[(hb, True), (seam, False), (ht, False), (seam, True)]])
    return Sat().body(build)


def sat_region(x0, y0, size, hole):
    """REGION: квадрат с квадратным отверстием — одна плоская грань с двумя петлями."""
    def build(sat):
        def square(x, y, s):
            p = [(x, y, 0), (x + s, y, 0), (x + s, y + s, 0), (x, y + s, 0)]
            return [sat.edge(p[i], p[(i + 1) % 4], ("line",)) for i in range(4)]
        outer = square(x0, y0, size)
        inner = square(x0 + (size - hole) / 2, y0 + (size - hole) / 2, hole)
        sat.face(plane((x0, y0, 0), (0, 0, 1), (1, 0, 0)), [[(e, False) for e in outer], [(e, True) for e in reversed(inner)]])
    return Sat().body(build)


# --- KoTIN-48: 3DSOLID, REGION, BODY — плоские грани из SAT ---------------------------------------
def task48():
    from ezdxf.acis import api as acis
    # самопроверка ручного SAT: ezdxf читает ссылки, плоские грани с прямыми рёбрами — 4 боковые грани плиты + верх и низ
    assert len(acis.mesh_from_body(acis.load(sat_plate(0, 0, 30, 20, 4, 4))[0])[0].faces) == 6
    doc = new("R2010")  # SAT в кодах 1/3 пишется до R2010
    msp = doc.modelspace()
    doc.layers.add("Solids", color=1)
    doc.layers.add("Plates", color=3)
    doc.layers.add("Regions", color=5)
    # красный куб 10×10×10 из сетки (у тела есть transform — перенос к центру)
    cube = forms.cube().scale_uniform(10).translate(5, 5, 5)
    solid = msp.add_3dsolid(dxfattribs={"layer": "Solids"})
    acis.export_dxf(solid, [acis.body_from_mesh(cube)])
    # зелёная плита 30×20×4 с отверстием R4: плоские грани с дыркой, цилиндр отверстия не рисуется
    msp.add_3dsolid(dxfattribs={"layer": "Plates"}).sat = sat_plate(20, 0, 30, 20, 4, 4)
    # та же плита в блоке: поворот 30°, подъём на 15
    blk = doc.blocks.new("PLATE")
    blk.add_3dsolid(dxfattribs={"layer": "0"}).sat = sat_plate(0, 0, 30, 20, 4, 4)
    msp.add_blockref("PLATE", (60, 0, 15), dxfattribs={"rotation": 30, "layer": "Plates"})
    # синий REGION: квадрат 20 с отверстием 8; BODY — тот же куб
    msp.add_region(dxfattribs={"layer": "Regions"}).sat = sat_region(0, -30, 20, 8)
    body = msp.add_body(dxfattribs={"layer": "Solids"})
    acis.export_dxf(body, [acis.body_from_mesh(forms.cube().scale_uniform(6).translate(33, -20, 3))])
    msp.add_line((-5, -40), (100, -40), dxfattribs={"color": 8})
    save(doc, "acis-sat.dxf")

    # R2018: тело в SAB (секция ACDSDATA) — не рисуется, только считается
    doc = new("R2018")
    msp = doc.modelspace()
    solid = msp.add_3dsolid()
    acis.export_dxf(solid, [acis.body_from_mesh(forms.cube().scale_uniform(10))])
    msp.add_line((0, -10), (10, -10))
    save(doc, "acis-sab.dxf")


def cone_surface(center, axis, major, sin, cos):
    """cone-surface: эллипс основания (окружность), sin и cos полуугла; цилиндр — sin 0, cos ±1 (−1 — нормаль к оси)."""
    fields = (*center, *axis, *major, 1, "I", "I", sin, cos, math.hypot(*major), "forward", "I", "I", "I", "I")
    return ("cone-surface", *(x if isinstance(x, str) else f"{x:.12g}" for x in fields))


def sat_revolved(base, axis, rings, inner=None):
    """Тело вращения вокруг оси axis (единичная, вдоль x, y или z) из точки base: rings — (высота, радиус) торцов
    снизу вверх (радиус 0 — вершина конуса); inner — то же для отверстия (труба). Торцы плоские, бок — cone-surface,
    каждая боковая грань — две петли-окружности, как у AutoCAD."""
    ux = (0, 0, 1) if abs(axis[2]) < 0.9 else (1, 0, 0)
    perp = (axis[1] * ux[2] - axis[2] * ux[1], axis[2] * ux[0] - axis[0] * ux[2], axis[0] * ux[1] - axis[1] * ux[0])

    def at(h):
        return tuple(b + a * h for b, a in zip(base, axis))

    def build(sat):
        def circles(prof):
            out = []
            for h, r in prof:
                c = at(h)
                if r == 0:
                    out.append(None)
                    continue
                p = tuple(ci + pi * r for ci, pi in zip(c, perp))
                out.append(sat.edge(p, p, ("circle", c, axis, tuple(pi * r for pi in perp))))
            return out

        def side(prof, edges, sense):
            (h0, r0), (h1, r1) = prof
            ang = math.atan2(r1 - r0, h1 - h0)
            sin, cos = math.sin(ang) * sense, math.cos(ang) * sense
            major = tuple(pi * r0 for pi in perp) if r0 else tuple(pi * r1 for pi in perp)
            center = at(h0) if r0 else at(h1)
            loops = [[(e, rev)] for e, rev in zip(edges, (sense < 0, sense > 0)) if e is not None]
            sat.face(cone_surface(center, axis, major, sin, cos), loops)

        out = circles(rings)
        ins = circles(inner) if inner else [None, None]
        neg = tuple(-a for a in axis)
        # торцы: верх смотрит по оси, низ — против; у трубы отверстие — вторая петля
        if out[1] is not None:
            sat.face(plane(at(rings[1][0]), axis, perp), [[(out[1], False)]] + ([[(ins[1], True)]] if ins[1] else []))
        if out[0] is not None:
            sat.face(plane(at(rings[0][0]), neg, perp), [[(out[0], True)]] + ([[(ins[0], False)]] if ins[0] else []))
        side(rings, out, 1)
        if inner:
            side(inner, ins, -1)
    return Sat().body(build)


# --- KoTIN-94: 3DSOLID — конические и цилиндрические грани ---------------------------------------
def task94():
    from ezdxf.acis import api as acis
    doc = new("R2010")
    msp = doc.modelspace()
    doc.layers.add("Cylinders", color=1)
    doc.layers.add("Cones", color=3)
    doc.layers.add("Tubes", color=5)
    bodies = [
        # красный цилиндр R5 высотой 10: 2 плоские грани + цилиндрическая
        ("Cylinders", sat_revolved((0, 0, 0), (0, 0, 1), [(0, 5), (10, 5)])),
        # красный цилиндр R3 длиной 12, лежит вдоль x
        ("Cylinders", sat_revolved((-6, -15, 3), (1, 0, 0), [(0, 3), (12, 3)])),
        # зелёный конус R5, вершина на высоте 10 (у боковой грани одна петля — окружность основания)
        ("Cones", sat_revolved((20, 0, 0), (0, 0, 1), [(0, 5), (10, 0)])),
        # зелёный усечённый конус R4 → R2 высотой 8
        ("Cones", sat_revolved((35, 0, 0), (0, 0, 1), [(0, 4), (8, 2)])),
        # синяя труба R5 / R3 высотой 8: внутренняя стенка — цилиндр с нормалью к оси (cos −1)
        ("Tubes", sat_revolved((20, -20, 0), (0, 0, 1), [(0, 5), (8, 5)], inner=[(0, 3), (8, 3)])),
    ]
    for layer, sat in bodies:
        acis.load(sat)  # самопроверка ссылок ручного SAT
        msp.add_3dsolid(dxfattribs={"layer": layer}).sat = sat
    msp.add_line((-10, -30), (45, -30), dxfattribs={"color": 8})
    save(doc, "acis-cone.dxf")


def fmt_surface(name, *fields):
    return (name, *(x if isinstance(x, str) else f"{x:.12g}" for x in fields))


def sphere_surface(center, r, origin=(1, 0, 0), pole=(0, 0, 1)):
    """sphere-surface: центр, радиус, направление u = 0, полюсная ось."""
    return fmt_surface("sphere-surface", *center, r, *origin, *pole, "forward_v", "I", "I", "I", "I")


def torus_surface(center, axis, major, minor, origin):
    """torus-surface: центр, ось, большой и малый радиусы, направление u = 0."""
    return fmt_surface("torus-surface", *center, *axis, major, minor, *origin, "forward_v", "I", "I", "I", "I")


def circle_edge(sat, center, normal, major):
    """Замкнутое ребро-окружность: одна вершина в center + major, обход против часовой вокруг normal."""
    p = tuple(c + m for c, m in zip(center, major))
    return sat.edge(p, p, ("circle", center, normal, major))


def sat_closed(surface):
    """Тело из одной грани без петель: сфера или тор целиком (как у AutoCAD)."""
    def build(sat):
        sat.face(surface, [])
    return Sat().body(build)


def sat_dome(cx, cy, r, h):
    """Цилиндр R r высотой h с полусферой сверху: у грани сферы одна петля — экватор."""
    def build(sat):
        c0 = circle_edge(sat, (cx, cy, 0), (0, 0, 1), (r, 0, 0))
        c1 = circle_edge(sat, (cx, cy, h), (0, 0, 1), (r, 0, 0))
        sat.face(plane((cx, cy, 0), (0, 0, -1), (1, 0, 0)), [[(c0, True)]])
        sat.face(cone_surface((cx, cy, 0), (0, 0, 1), (r, 0, 0), 0, 1), [[(c0, False)], [(c1, True)]])
        sat.face(sphere_surface((cx, cy, h), r), [[(c1, False)]])
    return Sat().body(build)


def sat_elbow(c, big, small):
    """Отвод трубы: четверть тора (ось z, u от 0 до 90°) между двумя окружностями-сечениями и плоские торцы."""
    def build(sat):
        m0 = (c[0] + big, c[1], c[2])
        m1 = (c[0], c[1] + big, c[2])
        e0 = circle_edge(sat, m0, (0, -1, 0), (small, 0, 0))
        e1 = circle_edge(sat, m1, (-1, 0, 0), (0, 0, small))
        sat.face(plane(m0, (0, -1, 0), (1, 0, 0)), [[(e0, False)]])
        sat.face(plane(m1, (-1, 0, 0), (0, 0, 1)), [[(e1, False)]])
        sat.face(torus_surface(c, (0, 0, 1), big, small, (1, 0, 0)), [[(e0, True)], [(e1, True)]])
    return Sat().body(build)


def sat_fillet_can(cx, cy, r, h, f):
    """Цилиндр R r высотой h со скруглённым верхним ребром (радиус f): дно, стенка, четверть тора, крышка."""
    def build(sat):
        c0 = circle_edge(sat, (cx, cy, 0), (0, 0, 1), (r, 0, 0))
        c1 = circle_edge(sat, (cx, cy, h - f), (0, 0, 1), (r, 0, 0))
        c2 = circle_edge(sat, (cx, cy, h), (0, 0, 1), (r - f, 0, 0))
        sat.face(plane((cx, cy, 0), (0, 0, -1), (1, 0, 0)), [[(c0, True)]])
        sat.face(cone_surface((cx, cy, 0), (0, 0, 1), (r, 0, 0), 0, 1), [[(c0, False)], [(c1, True)]])
        sat.face(torus_surface((cx, cy, h - f), (0, 0, 1), r - f, f, (1, 0, 0)), [[(c1, False)], [(c2, True)]])
        sat.face(plane((cx, cy, h), (0, 0, 1), (1, 0, 0)), [[(c2, False)]])
    return Sat().body(build)


def sat_rounded_box(o, size, r):
    """Брусок со скруглёнными рёбрами: 6 плоскостей, 12 четвертей цилиндров, 8 восьмушек сфер в углах
    (полюс сферы — по z, вершина угла на полюсе)."""
    half = [s / 2 for s in size]
    a = [hh - r for hh in half]
    axes = [(1, 0, 0), (0, 1, 0), (0, 0, 1)]

    def corner(sg):
        return tuple(o[i] + sg[i] * a[i] for i in range(3))

    def vert(sg, m):
        c = list(corner(sg))
        c[m] += sg[m] * r
        return tuple(round(v, 9) for v in c)

    def build(sat):
        edges = {}

        def edge(p, q, center):
            key = (p, q) if p < q else (q, p)
            if key not in edges:
                if center is None:
                    edges[key] = sat.edge(key[0], key[1], ("line",))
                else:
                    u = [x - y for x, y in zip(key[0], center)]
                    w = [x - y for x, y in zip(key[1], center)]
                    n = (u[1] * w[2] - u[2] * w[1], u[2] * w[0] - u[0] * w[2], u[0] * w[1] - u[1] * w[0])
                    ln = math.hypot(*n)
                    edges[key] = sat.edge(key[0], key[1], ("circle", center, tuple(x / ln for x in n), tuple(u)))
            return edges[key], p != key[0]

        def loop(segs, outward):
            # обход против часовой вокруг наружной нормали (по Ньюэллу многоугольника вершин)
            pts = [s[0] for s in segs]
            nx = ny = nz = 0.0
            for i, p in enumerate(pts):
                q = pts[(i + 1) % len(pts)]
                nx += (p[1] - q[1]) * (p[2] + q[2])
                ny += (p[2] - q[2]) * (p[0] + q[0])
                nz += (p[0] - q[0]) * (p[1] + q[1])
            if nx * outward[0] + ny * outward[1] + nz * outward[2] < 0:
                segs = [(q, p, c) for p, q, c in reversed(segs)]
            return [edge(p, q, c) for p, q, c in segs]

        signs = [(1, 1), (-1, 1), (-1, -1), (1, -1)]
        for k in range(3):
            i, j = [m for m in range(3) if m != k]
            for s in (1, -1):
                pts = []
                for si, sj in signs:
                    sg = [0, 0, 0]
                    sg[k], sg[i], sg[j] = s, si, sj
                    pts.append(vert(sg, k))
                n = tuple(s * x for x in axes[k])
                segs = [(pts[t], pts[(t + 1) % 4], None) for t in range(4)]
                origin = tuple(o[m] + (s * half[k] if m == k else 0) for m in range(3))
                sat.face(plane(origin, n, axes[i]), [loop(segs, n)])
        for k in range(3):
            i, j = [m for m in range(3) if m != k]
            for si, sj in signs:
                lo = [0, 0, 0]
                lo[k], lo[i], lo[j] = -1, si, sj
                hi = list(lo)
                hi[k] = 1
                p0, p1, p2, p3 = vert(lo, i), vert(hi, i), vert(hi, j), vert(lo, j)
                segs = [(p0, p1, None), (p1, p2, corner(hi)), (p2, p3, None), (p3, p0, corner(lo))]
                out = tuple(si * axes[i][m] + sj * axes[j][m] for m in range(3))
                major = tuple(si * r * x for x in axes[i])
                sat.face(cone_surface(corner(lo), axes[k], major, 0, 1), [loop(segs, out)])
        for sx in (1, -1):
            for sy in (1, -1):
                for sz in (1, -1):
                    sg = (sx, sy, sz)
                    c = corner(sg)
                    p = [vert(sg, m) for m in range(3)]
                    segs = [(p[t], p[(t + 1) % 3], c) for t in range(3)]
                    sat.face(sphere_surface(c, r), [loop(segs, sg)])
    return Sat().body(build)


# --- KoTIN-95: 3DSOLID — торы и сферы ---------------------------------------------------------
def task95():
    from ezdxf.acis import api as acis
    doc = new("R2010")
    msp = doc.modelspace()
    doc.layers.add("Spheres", color=1)
    doc.layers.add("Tori", color=3)
    doc.layers.add("Rounded", color=5)
    bodies = [
        # красная сфера R4 целиком (грань без петель)
        ("Spheres", sat_closed(sphere_surface((0, 0, 4), 4))),
        # красный цилиндр R3 высотой 4 с полусферой сверху
        ("Spheres", sat_dome(12, 0, 3, 4)),
        # зелёный тор-бублик R5 / r1.5 целиком
        ("Tori", sat_closed(torus_surface((28, 0, 1.5), (0, 0, 1), 5, 1.5, (1, 0, 0)))),
        # зелёный отвод: четверть тора R6 / r1.5 с плоскими торцами
        ("Tori", sat_elbow((36, 0, 1.5), 6, 1.5)),
        # синий брусок 10×8×6 со скруглёнными рёбрами R1.5: плоскости, цилиндры, сферы в углах
        ("Rounded", sat_rounded_box((5, -20, 3), (10, 8, 6), 1.5)),
        # синий цилиндр R5 высотой 6 со скруглённым верхним ребром R1.5: тор между стенкой и крышкой
        ("Rounded", sat_fillet_can(25, -20, 5, 6, 1.5)),
    ]
    for layer, sat in bodies:
        acis.load(sat)  # самопроверка ссылок ручного SAT
        msp.add_3dsolid(dxfattribs={"layer": layer}).sat = sat
    msp.add_line((-10, -30), (45, -30), dxfattribs={"color": 8})
    save(doc, "acis-torus-sphere.dxf")


# --- KoTIN-96: тела R2013+ в SAB (секция ACDSDATA), обычный и бинарный DXF ---------------------------------
def task96():
    from ezdxf.acis import api as acis
    # те же кубы, что слой Solids в acis-sat.dxf: SAB должен дать те же треугольники
    for version, name, fmt in (("R2018", "acis-sab-ascii.dxf", "asc"), ("R2013", "acis-sab-bin.dxf", "bin")):
        doc = new(version)
        msp = doc.modelspace()
        doc.layers.add("Solids", color=1)
        solid = msp.add_3dsolid(dxfattribs={"layer": "Solids"})
        acis.export_dxf(solid, [acis.body_from_mesh(forms.cube().scale_uniform(10).translate(5, 5, 5))])
        body = msp.add_body(dxfattribs={"layer": "Solids"})
        acis.export_dxf(body, [acis.body_from_mesh(forms.cube().scale_uniform(6).translate(33, -20, 3))])
        msp.add_line((-5, -40), (100, -40), dxfattribs={"color": 8})
        save(doc, name, fmt=fmt)


# --- KoTIN-97: SURFACE через путь тел 3DSOLID ---------------------------------------------------------------------
def sat_rect(p0, du, dv, normal):
    """Лист из одной плоской грани-прямоугольника: угол p0, стороны du и dv."""
    def build(sat):
        p = [p0, tuple(a + b for a, b in zip(p0, du)), tuple(a + b + c for a, b, c in zip(p0, du, dv)),
             tuple(a + b for a, b in zip(p0, dv))]
        sat.face(plane(p0, normal, tuple(c / math.hypot(*du) for c in du)),
                 [[(sat.edge(p[i], p[(i + 1) % 4], ("line",)), False) for i in range(4)]])
    return Sat().body(build)


def task97():
    from ezdxf.acis import api as acis
    doc = new("R2010")  # SAT в кодах 1/3
    msp = doc.modelspace()
    doc.layers.add("Surfaces", color=4)
    attribs = {"layer": "Surfaces"}
    # PLANESURFACE (у ezdxf нет — пишется как SURFACE и переименовывается ниже): квадрат 10×10 → 2 треугольника
    plane_surface = msp.add_surface(dxfattribs=attribs)
    plane_surface.sat = sat_rect((0, 0, 0), (10, 0, 0), (0, 10, 0), (0, 0, 1))
    # EXTRUDEDSURFACE: отрезок (20,0)–(30,0), выдавленный на 6 вверх → вертикальный прямоугольник
    extruded = msp.add_extruded_surface(dxfattribs={**attribs, "sweep_vector": (0, 0, 6)})
    extruded.sat = sat_rect((20, 0, 0), (10, 0, 0), (0, 0, 6), (0, -1, 0))
    # SWEPTSURFACE: квадрат 8×8; в подкласс ниже дописываются двоичные данные профиля (90/310)
    swept = msp.add_swept_surface(dxfattribs=attribs)
    swept.sat = sat_rect((40, 0, 0), (8, 0, 0), (0, 8, 0), (0, 0, 1))
    msp.add_line((-5, -5), (50, -5), dxfattribs={"color": 8})
    save(doc, "surface.dxf")
    path = OUT / "surface.dxf"
    text = path.read_text()
    h = plane_surface.dxf.handle
    text = text.replace(f"  0\nSURFACE\n  5\n{h}\n", f"  0\nPLANESURFACE\n  5\n{h}\n", 1)
    text = text.replace("AcDbSweptSurface\n", "AcDbSweptSurface\n 90\n1\n 90\n16\n310\n" + "0123456789ABCDEF" * 2 + "\n", 1)
    # REVOLVEDSURFACE без SAT: не рисуется, только считается
    no_sat = ("  0\nREVOLVEDSURFACE\n  5\nFFF0\n330\n1F\n100\nAcDbEntity\n  8\nSurfaces\n100\nAcDbModelerGeometry\n"
              " 70\n     1\n100\nAcDbSurface\n 71\n     0\n 72\n     0\n100\nAcDbRevolvedSurface\n 90\n     0\n" +
              "".join(f" 42\n{1.0 if k % 5 == 0 else 0.0}\n" for k in range(16)) +
              " 44\n0.0\n 45\n0.0\n 46\n0.0\n290\n     0\n291\n     0\n")
    end = text.index("  0\nENDSEC\n", text.index("ENTITIES"))
    path.write_text(text[:end] + no_sat + text[end:])

    # R2018: тело SURFACE в SAB (секция ACDSDATA) — квадрат из сетки в одну грань
    doc = new("R2018")
    msp = doc.modelspace()
    doc.layers.add("Surfaces", color=4)
    square = ezdxf.render.MeshBuilder()
    square.add_face([(0, 0, 0), (10, 0, 0), (10, 10, 0), (0, 10, 0)])
    acis.export_dxf(msp.add_extruded_surface(dxfattribs={"layer": "Surfaces"}), [acis.body_from_mesh(square)])
    save(doc, "surface-sab.dxf")

# --- KoTIN-33: PLINEGEN и текст в типе линии ---------------------------------------------------
def task33():
    doc = new()
    msp = doc.modelspace()
    # как GAS_LINE из acad.lin, но с подписью «ГАЗ»; второй — поворот абсолютный (A=), третий — с фигурой SHX
    doc.linetypes.add("GAZ", pattern='A,.5,-.2,["ГАЗ",STANDARD,S=.1,R=0.0,X=-0.1,Y=-.05],-.25',
                      description="Газ ----ГАЗ----ГАЗ----", length=0.95)
    gaz_abs = doc.linetypes.add("GAZ_ABS", pattern='A,.5,-.2,["ГАЗ",STANDARD,S=.1,R=0.0,X=-0.1,Y=-.05],-.25',
                                description="Газ, текст горизонтально", length=0.95)
    # ezdxf не знает A= (абсолютный поворот): ставим бит 1 в коде 74 сами
    tags = gaz_abs.pattern_tags.tags
    for i, tag in enumerate(tags):
        if tag.code == 74 and tag.value == 2:
            tags[i] = DXFTag(74, 3)
    doc.styles.add_shx("ltypeshp.shx")
    doc.linetypes.add("FENCE", pattern="A,.25,-.1,[132,ltypeshp.shx,x=-.1,s=.1],-.1,1",
                      description="Забор ----0-----0----", length=1.45)
    doc.header["$LTSCALE"] = 10.0
    # прямая, ломаная с PLINEGEN и без, окружность
    msp.add_line((0, 0), (100, 0), dxfattribs={"linetype": "GAZ", "color": 2})
    corners = [(0, -20), (30, -20), (30, -35), (60, -35), (75, -20), (100, -20)]
    p = msp.add_lwpolyline(corners, dxfattribs={"linetype": "GAZ", "color": 3})
    p.dxf.flags = 128  # PLINEGEN: паттерн через вершины
    msp.add_lwpolyline([(x, y - 30) for x, y in corners], dxfattribs={"linetype": "GAZ", "color": 4})
    msp.add_circle((140, -20), 20, dxfattribs={"linetype": "GAZ", "color": 5})
    # текст горизонтально на наклонной линии
    msp.add_line((0, -80), (60, -110), dxfattribs={"linetype": "GAZ_ABS", "color": 6})
    # фигура SHX не рисуется, пробел под ней остаётся
    msp.add_line((80, -95), (180, -95), dxfattribs={"linetype": "FENCE", "color": 1})
    # DASHED: зигзаг из коротких сегментов — без PLINEGEN каждый начинается штрихом, с PLINEGEN узор сквозной
    zig = [(i * 4, -120 - (i % 2) * 4) for i in range(26)]
    msp.add_lwpolyline(zig, dxfattribs={"linetype": "DASHED", "color": 2, "ltscale": 0.1})
    z2 = msp.add_lwpolyline([(x, y - 15) for x, y in zig], dxfattribs={"linetype": "DASHED", "color": 3, "ltscale": 0.1})
    z2.dxf.flags = 128
    save(doc, "linetype-text.dxf")


def task51():
    doc = new()
    msp = doc.modelspace()
    # основной фрагмент: сетка 10×10 клеток по 10 м с красным квадратом в центре (500, 300)
    for i in range(11):
        msp.add_line((450 + i * 10, 250), (450 + i * 10, 350), dxfattribs={"color": 7})
        msp.add_line((450, 250 + i * 10), (550, 250 + i * 10), dxfattribs={"color": 7})
    msp.add_lwpolyline([(490, 290), (510, 290), (510, 310), (490, 310)], close=True, dxfattribs={"color": 1})
    # далёкая пометка — габариты чертежа в ~20 раз больше сохранённого вида
    msp.add_circle((2000, 1500), 30, dxfattribs={"color": 3})
    msp.add_line((450, 250), (2000, 1500), dxfattribs={"color": 8})
    # сохранённый вид: центр (500, 300), высота 100
    active = doc.viewports.get_config("*Active")[0]
    active.dxf.center = (500, 300)
    active.dxf.height = 100
    active.dxf.aspect_ratio = 1.5
    save(doc, "saved-view.dxf")


# --- KoTIN-28: MTEXT — цвет, высота, линии и фон внутри текста ---------------------------------------
def task28():
    doc = new()
    msp = doc.modelspace()
    rows = [
        r"{\C1;красный} обычный",
        r"\C3;зелёный \C5;синий \c33023;оранжевый",
        r"обычный {\H2x;двойной} \H0.5x;половина",
        r"\Lподчёркнутый\l \Oнадчёркнутый\o \Kзачёркнутый\k",
        r"{\C1;\Lкрасное подчёркнутое} {\C4;\Kголубое зачёркнутое}",
        r"Первая строка\P{\H2x;\C2;Вторая крупная}\PТретья",
    ]
    y = 0
    for text in rows:
        msp.add_mtext(text, dxfattribs={"char_height": 1, "insert": (0, y), "color": 7})
        y -= 4 if "\\P" not in text else 8
    # рамка шириной 14: строка переносится по словам, цвет и высота слов сохраняются
    msp.add_mtext(
        r"В рамке {\C1;красные слова} и {\H1.5x;\C4;крупные голубые} переносятся вместе с {\L\C2;подчёркиванием}",
        dxfattribs={"char_height": 1, "insert": (30, 0), "color": 7, "width": 14},
    )
    # фон: свой цвет с полем 1,5 и шириной рамки; цвет окна
    m = msp.add_mtext("Плашка красная", dxfattribs={"char_height": 1, "insert": (0, y - 2), "color": 7, "width": 12})
    m.set_bg_color(1, scale=1.5)
    m = msp.add_mtext("Плашка цвета окна", dxfattribs={"char_height": 1, "insert": (16, y - 2), "color": 2})
    m.set_bg_color("canvas", scale=1.2)
    # под плашкой окна — линия: должна прерываться
    msp.add_line((14, y - 2.5), (36, y - 2.5), dxfattribs={"color": 3})
    # рамка вокруг: вид подгоняется по ней; слева запас — текст не уходит под панель настроек
    msp.add_lwpolyline([(-28, 3), (48, 3), (48, y - 6), (-28, y - 6)], close=True, dxfattribs={"color": 8})
    save(doc, "mtext-format.dxf")


def task30():
    doc = new()
    msp = doc.modelspace()
    # подложка: сетка белых линий 0…80 × 0…30 с шагом 5 — должна просвечивать сквозь полупрозрачные заливки
    for i in range(17):
        msp.add_line((i * 5, 0), (i * 5, 30), dxfattribs={"color": 7})
    for i in range(7):
        msp.add_line((0, i * 5), (80, i * 5), dxfattribs={"color": 7})

    def box(h, x, y, w=14, hgt=20):
        h.paths.add_polyline_path([(x, y), (x + w, y), (x + w, y + hgt), (x, y + hgt)], is_closed=True)

    # 1. непрозрачная зелёная — для сравнения, сетку закрывает
    h = msp.add_hatch(color=3)
    box(h, 2, 5)
    # 2. красная, 440 = 0x0200007F — альфа 127, сетка видна
    h = msp.add_hatch(color=1)
    h.dxf.transparency = 0x0200007F
    box(h, 18, 5)
    # 3. синяя BYLAYER на слое «Glass» с прозрачностью 75 % (XDATA AcCmTransparency, альфа 63)
    glass = doc.layers.add("Glass", color=5)
    glass.transparency = 0.75
    h = msp.add_hatch(color=256, dxfattribs={"layer": "Glass"})
    box(h, 34, 5)
    # 4. жёлтая в блоке с прозрачностью BYBLOCK; вхождение — альфа 0x40
    blk = doc.blocks.new("PANE")
    h = blk.add_hatch(color=2)
    h.dxf.transparency = 0x01000000
    box(h, 0, 0)
    msp.add_blockref("PANE", (50, 5), dxfattribs={"transparency": 0x02000040})
    # 5. голубой SOLID, альфа 0xB0
    msp.add_solid([(66, 5), (78, 5), (66, 25), (78, 25)], dxfattribs={"color": 4, "transparency": 0x020000B0})
    save(doc, "transparency.dxf")


# --- KoTIN-47: открытые замены шрифтов ---------------------------------------------------------
def task47():
    doc = new()
    msp = doc.modelspace()
    text = "ПК0+00"
    h = 2.5
    # длина «ПК0+00» высотой 1 в AutoCAD по метрикам шрифта (Arial Narrow — 0,82 Arial); у SHX — не проверяется
    rows = [("arial.ttf", 5.165), ("arialn.ttf", 5.165 * 0.82), ("times.ttf", 5.274), ("calibri.ttf", 4.961),
            ("txt", None), ("romans.shx", None), ("isocpeur.ttf", None), ("gost 2304a.shx", None), ("mystery.shx", None)]
    y = 0
    for font, width in rows:
        style = font.split(".")[0].replace(" ", "_").upper()
        doc.styles.add(style, font=font)
        msp.add_text(font, height=1.2, dxfattribs={"color": 8}).set_placement((-30, y))
        msp.add_text(text, height=h, dxfattribs={"style": style, "color": 2}).set_placement((0, y))
        if width:
            # зелёная линия под строкой — длина строки в AutoCAD
            msp.add_line((0, y - 0.6), (width * h, y - 0.6), dxfattribs={"color": 3})
        cross(msp, 40, y, 0.5)
        msp.add_text(text, height=h, dxfattribs={"style": style, "color": 4}).set_placement((40, y), align=TextEntityAlignment.CENTER)
        cross(msp, 75, y, 0.5)
        msp.add_text(text, height=h, dxfattribs={"style": style, "color": 6}).set_placement((75, y), align=TextEntityAlignment.RIGHT)
        y -= 6
    # гарнитура TTF только в XDATA ACAD, файла нет
    family = doc.styles.add("FAMILY_ONLY", font="")
    family.set_extended_font_data("Arial Narrow")
    msp.add_text("Arial Narrow из XDATA", height=1.2, dxfattribs={"color": 8}).set_placement((-30, y))
    msp.add_text(text, height=h, dxfattribs={"style": "FAMILY_ONLY", "color": 2}).set_placement((0, y))
    msp.add_line((0, y - 0.6), (5.165 * 0.82 * h, y - 0.6), dxfattribs={"color": 3})
    # знака ⌀ в заменах нет — его рисует шрифт платформы
    msp.add_text("⌀530 ±0,5 15° №1", height=h, dxfattribs={"style": "ARIAL", "color": 7}).set_placement((40, y))
    # MTEXT в рамке 30: перенос по ширине узкого шрифта
    msp.add_line((0, y - 6), (0, y - 20), dxfattribs={"color": 3})
    msp.add_line((30, y - 6), (30, y - 20), dxfattribs={"color": 3})
    msp.add_mtext("Пикет ПК0+00 до ПК1+50, ширина рамки 30 единиц, шрифт Arial Narrow",
                  dxfattribs={"style": "ARIALN", "char_height": 2.0, "width": 30, "insert": (0, y - 6)})
    # сохранённый вид сверху на весь лист
    active = doc.viewports.get_config("*Active")[0]
    active.dxf.center = (25, -33)
    active.dxf.height = 80
    active.dxf.aspect_ratio = 1.6
    save(doc, "fonts.dxf")


# --- KoTIN-41: WIPEOUT -------------------------------------------------------------------------
def task41():
    doc = new()
    msp = doc.modelspace()
    # до маски: две красные линии и зелёный квадрат — под маской их участки скрыты
    msp.add_line((0, 3), (20, 3), dxfattribs={"color": 1})
    msp.add_line((0, 7), (20, 7), dxfattribs={"color": 1})
    msp.add_solid([(2, 2), (8, 2), (2, 8), (8, 8)], dxfattribs={"color": 3})
    msp.add_wipeout([(5, 0), (15, 0), (15, 10), (5, 10)])
    # после маски: синяя диагональ и жёлтая подпись видны целиком
    msp.add_line((0, 0), (20, 10), dxfattribs={"color": 5})
    msp.add_text("WIPEOUT", height=1.2, dxfattribs={"color": 2}).set_placement((6, 8.5))
    # блок с многоугольной маской (треугольник) поверх своей сетки линий, вставлен с поворотом
    block = doc.blocks.new("MASK")
    for i in range(11):
        block.add_line((0, i), (10, i), dxfattribs={"color": 4})
    block.add_wipeout([(1, 1), (9, 1), (5, 9)])
    block.add_line((0, 0), (10, 10), dxfattribs={"color": 6})
    msp.add_blockref("MASK", (30, 0), dxfattribs={"rotation": 15})
    save(doc, "wipeout.dxf")

    # то же с рамкой: WIPEOUTVARIABLES, флаг 70 = 1
    doc.set_wipeout_variables(frame=1)
    save(doc, "wipeout-frame.dxf")


# --- KoTIN-55: сохранённый вид сверху с ненулевой целью ----------------------------------------
def task55():
    doc = new()
    msp = doc.modelspace()
    # чертёж далеко от начала: рамка 20…220 × 16…300 с крестом в центре (120, 158)
    msp.add_lwpolyline([(20, 16), (220, 16), (220, 300), (20, 300)], close=True, dxfattribs={"color": 7})
    msp.add_line((20, 16), (220, 300), dxfattribs={"color": 1})
    msp.add_line((20, 300), (220, 16), dxfattribs={"color": 1})
    # центр вида в DCS (-33, 10) от цели (153, 148): в мире (120, 158) — центр рамки
    active = doc.viewports.get_config("*Active")[0]
    active.dxf.center = (-33, 10)
    active.dxf.target = (153, 148, 0)
    active.dxf.height = 300
    active.dxf.aspect_ratio = 1.0
    save(doc, "view-target.dxf")


# --- KoTIN-57: IMAGE — рамка и путь к файлу вместо картинки ------------------------------------
def task57():
    doc = new()
    msp = doc.modelspace()
    # опорный чертёж: рамка 0…120 × 0…60, чтобы видеть положение картинок
    msp.add_lwpolyline([(0, 0), (120, 0), (120, 60), (0, 60)], close=True, dxfattribs={"color": 8})
    long_path = "C:\\Users\\surveyor\\Documents\\Projects\\Orthophoto\\2026\\site-plan-north-part.tif"
    # 1) обычная картинка 40 × 20 с абсолютным путём; 2) та же, повёрнутая на 30°; 3) длинный путь в узкой рамке;
    # 4) с многоугольной обрезкой (треугольник); 5) без IMAGEDEF-пути — оборванная ссылка
    plain = doc.add_image_def("C:\\Users\\surveyor\\plan.png", size_in_pixel=(400, 200))
    msp.add_image(plain, insert=(5, 30), size_in_units=(40, 20), rotation=0, dxfattribs={"color": 1})
    msp.add_image(plain, insert=(75, 5), size_in_units=(40, 20), rotation=30, dxfattribs={"color": 3})
    long = doc.add_image_def(long_path, size_in_pixel=(300, 300))
    msp.add_image(long, insert=(50, 35), size_in_units=(20, 20), rotation=0, dxfattribs={"color": 5})
    clipped = doc.add_image_def("/home/user/maps/clipped.jpg", size_in_pixel=(400, 200))
    image = msp.add_image(clipped, insert=(5, 5), size_in_units=(40, 20), rotation=0, dxfattribs={"color": 2})
    # вершины контура — в пикселях от левого верхнего угла, ось y вниз
    image.set_boundary_path([(0, 200), (400, 200), (200, 0)])
    broken = doc.add_image_def("", size_in_pixel=(100, 100))
    msp.add_image(broken, insert=(50, 5), size_in_units=(20, 20), rotation=0, dxfattribs={"color": 6})
    save(doc, "image.dxf")


# --- KoTIN-56: листы и рамки видовых экранов ---------------------------------------------------
def task56():
    doc = new()
    msp = doc.modelspace()
    # модель: квартал 200 × 100 с крестом и подписью
    msp.add_lwpolyline([(0, 0), (200, 0), (200, 100), (0, 100)], close=True, dxfattribs={"color": 3})
    msp.add_line((0, 0), (200, 100), dxfattribs={"color": 1})
    msp.add_line((0, 100), (200, 0), dxfattribs={"color": 1})
    msp.add_text("МОДЕЛЬ", height=8, dxfattribs={"insert": (80, 45), "color": 7})

    # Лист «План»: A3 альбомный, штамп и два видовых экрана — 1:500 (общий вид) и 1:100 (фрагмент)
    plan = doc.layouts.get("Layout1")
    plan.rename("План")
    plan.page_setup(size=(420, 297), margins=(10, 10, 10, 20), units="mm")
    plan.add_lwpolyline([(0, 0), (390, 0), (390, 277), (0, 277)], close=True, dxfattribs={"color": 7})
    plan.add_lwpolyline([(205, 0), (390, 0), (390, 55), (205, 55)], close=True, dxfattribs={"color": 7})
    plan.add_text("ЛИСТ ПЛАН", height=7, dxfattribs={"insert": (215, 30), "color": 2})
    # масштаб — высота вида (м модели) в мм к высоте окна (мм листа): 60 м / 120 мм = 1:500, 12 м / 120 мм = 1:100;
    # первое окно — середина квартала с подписью, второе — перекрестье диагоналей
    plan.add_viewport(center=(100, 180), size=(180, 120), view_center_point=(100, 50), view_height=60)
    plan.add_viewport(center=(300, 170), size=(160, 120), view_center_point=(100, 50), view_height=12)

    # Лист «Узел»: A4 книжный, только текст и один видовой экран 1:20 — угол квартала у (0, 0)
    node = doc.layouts.new("Узел")
    node.page_setup(size=(210, 297), margins=(5, 5, 5, 20), units="mm")
    node.add_text("ЛИСТ УЗЕЛ", height=6, dxfattribs={"insert": (20, 250), "color": 4})
    node.add_viewport(center=(90, 120), size=(150, 100), view_center_point=(1, 0.5), view_height=2)
    save(doc, "layouts.dxf")


# --- KoTIN-89: MULTILEADER — текст переносится по ширине рамки ---------------------------------
def task89():
    from ezdxf.render.mleader import ConnectionSide
    doc = new()
    msp = doc.modelspace()
    # с шириной рамки 15 — переносится; без неё (0) — одна строка
    for dx, width in ((0, 15), (60, 0)):
        ml = msp.add_multileader_mtext("Standard")
        ml.set_content("Длинный текст выноски, который должен переноситься по ширине рамки", char_height=2)
        ml.context.mtext.width = width
        ml.add_leader_line(ConnectionSide.left, [Vec2(dx - 10, -10)])
        ml.build(insert=Vec2(dx, 0))
    save(doc, "mleader-width.dxf")


# --- KoTIN-80: исходный вид по основной массе, далёкие объекты за кадром ---------------------------
def task80():
    doc = new()
    msp = doc.modelspace()
    # участок 120 × 80 у (1000, 1000): контур, сетка кварталов, подписи
    x0, y0 = 940, 960
    msp.add_lwpolyline([(x0, y0), (x0 + 120, y0), (x0 + 120, y0 + 80), (x0, y0 + 80)], close=True, dxfattribs={"color": 7})
    for i in range(1, 6):
        msp.add_line((x0 + i * 20, y0), (x0 + i * 20, y0 + 80), dxfattribs={"color": 8})
    for j in range(1, 4):
        msp.add_line((x0, y0 + j * 20), (x0 + 120, y0 + j * 20), dxfattribs={"color": 8})
    for i in range(6):
        for j in range(4):
            msp.add_circle((x0 + 10 + i * 20, y0 + 10 + j * 20), 4, dxfattribs={"color": 3 + (i + j) % 3})
    msp.add_text("УЧАСТОК", height=6, dxfattribs={"insert": (x0 + 4, y0 + 84), "color": 2})
    # выбросы: точка в 0,0 (1,4 км на юго-запад) и забытый квадрат 6 × 6 в 3,2 км на восток
    msp.add_point((0, 0), dxfattribs={"color": 1})
    msp.add_lwpolyline([(4197, 1027), (4203, 1027), (4203, 1033), (4197, 1033)], close=True, dxfattribs={"color": 1})
    doc.header["$PDMODE"] = 3
    doc.header["$PDSIZE"] = -2
    save(doc, "outliers.dxf")


# --- KoTIN-90: модель в видовом экране листа, масштаб в единицах (модель в м, лист в мм) -----------
def task90():
    doc = ezdxf.new("R2018", setup=True, units=6)
    msp = doc.modelspace()
    msp.add_lwpolyline([(0, 0), (100, 0), (100, 50), (0, 50)], close=True, dxfattribs={"color": 3})
    msp.add_line((0, 0), (100, 50), dxfattribs={"color": 1})
    msp.add_text("МОДЕЛЬ 100×50 м", dxfattribs={"height": 5}).set_placement((20, 22))
    lay = doc.layouts.new("Лист 1-500")
    lay.page_setup(size=(420, 297), margins=(10, 10, 10, 10), units="mm")
    # окно 200 × 100 мм, вид высотой 50 м: 50 000 / 100 = 1:500, модель целиком
    lay.add_viewport(center=(210, 148), size=(200, 100), view_center_point=(50, 25), view_height=50)
    save(doc, "layout-units.dxf")

# --- KoTIN-98: OLE2FRAME рамкой — модель, блок под INSERT, без кода 11 ---------------------------------
def ole2frame(layout, top_left, bottom_right, attribs):
    from ezdxf.lldxf.tags import Tags
    from ezdxf.lldxf.types import DXFBinaryTag, DXFVertex
    data = bytes(range(64))
    tags = [DXFTag(100, "AcDbOle2Frame"), DXFTag(70, 2), DXFTag(3, "Paintbrush Picture")]
    tags.append(DXFVertex(10, top_left))
    if bottom_right is not None:
        tags.append(DXFVertex(11, bottom_right))
    tags += [DXFTag(71, 2), DXFTag(72, 0), DXFTag(90, len(data)),
             DXFBinaryTag(310, data[:32]), DXFBinaryTag(310, data[32:]), DXFTag(1, "OLE")]
    e = layout.new_entity("OLE2FRAME", attribs)
    e.acdb_ole2frame = Tags(tags)
    return e


def task98():
    doc = new()
    msp = doc.modelspace()
    doc.layers.add("OLE", color=4)
    # красная рамка 50 × 30 на высоте 5: углы (10, 40) и (60, 10)
    ole2frame(msp, (10, 40, 5), (60, 10, 5), {"layer": "OLE", "color": 1})
    # рамка 30 × 20 в блоке, вставка в (100, 0) с масштабом 2: в мире (100, 0)–(160, 40), цвет слоя (голубой)
    blk = doc.blocks.new("OLE_BLOCK")
    ole2frame(blk, (0, 20, 0), (30, 0, 0), {"layer": "OLE"})
    msp.add_blockref("OLE_BLOCK", (100, 0), dxfattribs={"xscale": 2, "yscale": 2})
    # без кода 11 — не рисуется
    ole2frame(msp, (200, 40, 0), None, {"layer": "OLE", "color": 2})
    msp.add_text("OLE2FRAME", dxfattribs={"height": 4}).set_placement((10, 45))
    msp.add_text("INSERT x2", dxfattribs={"height": 4}).set_placement((100, 45))
    msp.add_text("no 11", dxfattribs={"height": 4}).set_placement((200, 45))
    save(doc, "ole2frame.dxf")


def main():
    print("Пишу в", OUT)
    for f in (task01, task02, task03, task04, task05, task06, task07, task08, task09, task32, task10, task11, task12,
              task13, task15, task16, task22, task23, task24, task25, task26, task27, task28, task29, task30, task33,
              task34, task35,
              task36, task37, task38, task39, task40, task41, task42, task44, task47, task48, task51, task55, task56,
              task57, task80, task89, task90, task94, task95, task96, task97, task98):
        f()


if __name__ == "__main__":
    main()
