#!/usr/bin/env python3
"""
Генератор синтетических LandXML для задач KoTIN (проверка по схеме там, где нет реального образца).
Файлы — в app/src/jvmTest/resources/landxml/synthetic, что в них, — в комментарии к каждому файлу.

  python3 scripts/gen_test_landxml.py

Только стандартная библиотека. Файлы детерминированы: пересоздание не даёт изменений в git.
Рельеф — наклонная плоскость z = Z0 + 0,1·(восток − E0) + 0,05·(север − N0): отметку любой вершины и площади
поверхности можно проверить без чтения файла (площадь 3D = площадь 2D · √(1 + 0,1² + 0,05²)).
"""
import math
from pathlib import Path

OUT = Path(__file__).resolve().parent.parent / "app/src/jvmTest/resources/landxml/synthetic"
OUT.mkdir(parents=True, exist_ok=True)

E0, N0, Z0, STEP = 2000.0, 5000.0, 100.0, 10.0
SLOPE_E, SLOPE_N = 0.1, 0.05
K3D = math.sqrt(1 + SLOPE_E ** 2 + SLOPE_N ** 2)


def elevation(col, row):
    return Z0 + SLOPE_E * col * STEP + SLOPE_N * row * STEP


def num(x):
    return repr(round(x, 6))


class Grid:
    """cols × rows точек, id с 1 по рядам (ряд — на север); ячейка (r, c) — между рядами r, r+1 и столбцами c, c+1."""

    def __init__(self, cols, rows):
        self.cols, self.rows = cols, rows
        self.z = {(c, r): elevation(c, r) for r in range(rows) for c in range(cols)}

    def pid(self, c, r):
        return 1 + r * self.cols + c

    def corners(self, c, r):
        """Углы ячейки против часовой стрелки (восток вправо, север вверх): ЮЗ, ЮВ, СВ, СЗ."""
        return [(c, r), (c + 1, r), (c + 1, r + 1), (c, r + 1)]

    def points_xml(self):
        lines = []
        for r in range(self.rows):
            for c in range(self.cols):
                lines.append('\t\t\t\t\t<P id="%d">%s %s %s</P>' % (
                    self.pid(c, r), num(N0 + r * STEP), num(E0 + c * STEP), num(self.z[(c, r)])))
        return "\n".join(lines)


def write(name, comment, grid, faces, definition_attrs):
    """faces — список (атрибуты, текст) в порядке файла; definition_attrs — контрольные числа Definition."""
    text = ['<?xml version="1.0" encoding="UTF-8"?>',
            "<!--", comment.strip("\n"), "-->",
            '<LandXML xmlns="http://www.landxml.org/schema/LandXML-1.2" version="1.2" date="2026-01-01" time="00:00:00">',
            '\t<Units><Metric linearUnit="meter" areaUnit="squareMeter" volumeUnit="cubicMeter"/></Units>',
            "\t<Surfaces>",
            '\t\t<Surface name="%s">' % name,
            '\t\t\t<Definition surfType="grid" %s>' % definition_attrs,
            "\t\t\t\t<Pnts>", grid.points_xml(), "\t\t\t\t</Pnts>",
            "\t\t\t\t<Faces>"]
    for attrs, body in faces:
        text.append("\t\t\t\t\t<F%s>%s</F>" % (attrs, body))
    text += ["\t\t\t\t</Faces>", "\t\t\t</Definition>", "\t\t</Surface>", "\t</Surfaces>", "</LandXML>", ""]
    (OUT / ("%s.xml" % name)).write_text("\n".join(text), encoding="utf-8")
    print("  ", name + ".xml")


def control(grid, cells):
    """Контрольные числа по ячейкам [(c, r)]: площади и отметки по их углам."""
    used = {p for (c, r) in cells for p in grid.corners(c, r)}
    area = len(cells) * STEP * STEP
    zs = [grid.z[p] for p in used]
    return 'area2DSurf="%s" area3DSurf="%s" elevMin="%s" elevMax="%s"' % (
        num(area), num(area * K3D), num(min(zs)), num(max(zs)))


def neighbours(cells, c, r):
    """F@n ячейки: номера (с 1, по порядку F) соседей через юг, восток, север, запад; нет соседа — 0."""
    index = {cell: k + 1 for k, cell in enumerate(cells)}
    return " ".join(str(index.get(p, 0)) for p in [(c, r - 1), (c + 1, r), (c, r + 1), (c - 1, r)])


def plane():
    """Чистая сетка: все ячейки против часовой, начиная с ЮЗ, у каждой F@n с четырьмя значениями."""
    grid = Grid(6, 5)
    cells = [(c, r) for r in range(grid.rows - 1) for c in range(grid.cols - 1)]
    faces = [(' n="%s"' % neighbours(cells, c, r), " ".join(str(grid.pid(*p)) for p in grid.corners(c, r)))
             for (c, r) in cells]
    write("grid-plane", """
  Синтетический grid: 6 × 5 точек с шагом 10 (восток 2000…2050, север 5000…5040), z = 100 + 0,1·Δвосток + 0,05·Δсевер.
  20 ячеек по четыре id (ЮЗ, ЮВ, СВ, СЗ — против часовой), у каждой F@n с четырьмя значениями. Ожидается 40 треугольников,
  диагональ ячейки (1–3) в каркасе скрыта; площадь 2D 2000, 3D 2000·√1,0125; отметки 100…107.
  Сгенерирован scripts/gen_test_landxml.py.
""", grid, faces, control(grid, cells))


def orders():
    """Девять ячеек в разном порядке вершин: все должны дать ту же плоскость без самопересечений."""
    grid = Grid(4, 4)
    cells = [(c, r) for r in range(3) for c in range(3)]
    variants = [
        ("против часовой, с ЮЗ", [0, 1, 2, 3]),
        ("против часовой, с ЮВ", [1, 2, 3, 0]),
        ("против часовой, с СВ", [2, 3, 0, 1]),
        ("против часовой, с СЗ", [3, 0, 1, 2]),
        ("по часовой, с ЮЗ", [0, 3, 2, 1]),
        ("по часовой, с СЗ", [3, 2, 1, 0]),
        ("«Z»: ЮЗ ЮВ СЗ СВ (по рядам)", [0, 1, 3, 2]),
        ("«8»: ЮЗ СВ ЮВ СЗ", [0, 2, 1, 3]),
        ("«Z» со сдвигом: ЮВ СЗ СВ ЮЗ", [1, 3, 2, 0]),
    ]
    faces = []
    legend = []
    for k, ((c, r), (title, order)) in enumerate(zip(cells, variants), start=1):
        corners = grid.corners(c, r)
        faces.append(("", " ".join(str(grid.pid(*corners[i])) for i in order)))
        legend.append("    F %d — ячейка (столбец %d, ряд %d): %s" % (k, c, r, title))
    write("grid-vertex-orders", """
  Синтетический grid: 4 × 4 точки, шаг 10, плоскость как в grid-plane.xml. Девять ячеек, у каждой свой порядок четырёх id:
%s
  Ожидается 18 треугольников, нормали вверх, самопересечений нет (порядок «Z» и «8» читатель переставляет),
  площадь 2D 900, 3D 900·√1,0125, отметки 100…104,5.
  Сгенерирован scripts/gen_test_landxml.py.
""" % "\n".join(legend), grid, faces, control(grid, cells))


def nodata():
    """Пропуски сетки: нет ячейки, F из нулей, отметки-заглушки, невидимая ячейка."""
    grid = Grid(6, 6)
    skipped = {}
    skipped[(0, 1)] = "нет F"
    skipped[(1, 1)] = 'F из нулей, n из нулей'
    skipped[(2, 2)] = 'F i="1" (невидима)'
    grid.z[(4, 4)] = -9999.0
    for cell in [(3, 3), (4, 3), (3, 4), (4, 4)]:
        skipped[cell] = "угол (4, 4) с отметкой −9999"
    grid.z[(5, 0)] = 1e30
    skipped[(4, 0)] = "угол (5, 0) с отметкой 1e30"
    all_cells = [(c, r) for r in range(5) for c in range(5)]
    valid = [cell for cell in all_cells if cell not in skipped]
    faces = []
    for cell in all_cells:
        c, r = cell
        ids = " ".join(str(grid.pid(*p)) for p in grid.corners(c, r))
        if cell == (0, 1):
            continue
        if cell == (1, 1):
            faces.append((' n="0 0 0 0"', "0 0 0 0"))
        elif cell == (2, 2):
            faces.append((' i="1" n="%s"' % neighbours(all_cells, c, r), ids))
        else:
            faces.append((' n="%s"' % neighbours(all_cells, c, r), ids))
    write("grid-nodata", """
  Синтетический grid: 6 × 6 точек, шаг 10, плоскость как в grid-plane.xml. 25 ячеек, из них без треугольников 8:
    (0, 1) — F нет совсем; (1, 1) — F «0 0 0 0» с n из нулей; (2, 2) — F i="1";
    (3, 3), (4, 3), (3, 4), (4, 4) — у точки (4, 4) отметка −9999 (заглушка «нет данных»); (4, 0) — у точки (5, 0) отметка 1e30.
  Ожидается 17 ячеек, 34 треугольника, ни одной вершины с заглушкой; площадь 2D 1700, 3D 1700·√1,0125;
  отметки по точкам оставшихся ячеек — контрольные числа Definition.
  Сгенерирован scripts/gen_test_landxml.py.
""", grid, faces, control(grid, valid))


def palette_scene():
    """Палитра сцены (KoTIN-91): все роли с закреплёнными цветами в одном файле."""
    def tin(name, e0, n0, cols, rows, z0):
        pts, faces = [], []
        for r in range(rows):
            for c in range(cols):
                # волны, чтобы была светотень
                z = z0 + 1.5 * (c % 2) + 0.5 * (r % 3)
                pts.append('\t\t\t\t\t<P id="%d">%s %s %s</P>' % (1 + r * cols + c, num(n0 + r * 50), num(e0 + c * 50), num(z)))
        for r in range(rows - 1):
            for c in range(cols - 1):
                a, b = 1 + r * cols + c, 2 + r * cols + c
                d, e = a + cols, b + cols
                faces += ["\t\t\t\t\t<F>%d %d %d</F>" % (a, b, e), "\t\t\t\t\t<F>%d %d %d</F>" % (a, e, d)]
        return ['\t\t<Surface name="%s">' % name, '\t\t\t<Definition surfType="TIN">', "\t\t\t\t<Pnts>", *pts,
                "\t\t\t\t</Pnts>", "\t\t\t\t<Faces>", *faces, "\t\t\t\t</Faces>", "\t\t\t</Definition>", "\t\t</Surface>"]

    text = ['<?xml version="1.0" encoding="UTF-8"?>', "<!--",
            "  Палитра сцены (KoTIN-91): три поверхности полосами с запада на восток («Рельеф», «Проект», «Насыпь»,",
            "  по 100 × 150 м); поверх них — четыре сети с запада на восток (север 30, 60, 90, 120: ливнёвка, канализация,",
            "  водопровод, прочее), в каждой два колодца и труба 600 мм над землёй (цвет виден сверху);",
            "  трасса «Ось» с проектным профилем по северу 140;",
            "  к югу — восемь видов ситуации по desc (север −10…−40), участок и пять точек.",
            "  Сгенерирован scripts/gen_test_landxml.py.", "-->",
            '<LandXML xmlns="http://www.landxml.org/schema/LandXML-1.2" version="1.2" date="2026-01-01" time="00:00:00">',
            '\t<Units><Metric linearUnit="meter" areaUnit="squareMeter" volumeUnit="cubicMeter" diameterUnit="millimeter"/></Units>',
            "\t<CgPoints>"]
    for i in range(5):
        text.append('\t\t<CgPoint name="Т%d">%s %s %s</CgPoint>' % (i + 1, num(-60.0), num(160.0 + i * 25), num(100.0)))
    text += ["\t</CgPoints>", "\t<Surfaces>"]
    for k, name in enumerate(["Рельеф", "Проект", "Насыпь"]):
        text += tin(name, k * 100.0, 0.0, 3, 4, 100.0 + k * 0.5)
    text += ["\t</Surfaces>", "\t<PipeNetworks>"]
    for k, (name, kind) in enumerate([("Ливнёвка", "storm"), ("Канализация", "sanitary"), ("Водопровод", "water"), ("Прочее", "other")]):
        n = 30.0 + 30 * k
        text += ['\t\t<PipeNetwork name="%s" pipeNetType="%s">' % (name, kind), "\t\t\t<Structs>"]
        for s, e in [(1, 20.0), (2, 280.0)]:
            text += ['\t\t\t\t<Struct name="%s-К%d" elevRim="105" elevSump="102">' % (name, s),
                     "\t\t\t\t\t<Center>%s %s</Center>" % (num(n), num(e)), '\t\t\t\t\t<CircStruct diameter="2000"/>',
                     '\t\t\t\t\t<Invert elev="%s" flowDir="%s" refPipe="%s-Т1"/>' % ("103" if s == 1 else "102.5", "out" if s == 1 else "in", name),
                     "\t\t\t\t</Struct>"]
        text += ["\t\t\t</Structs>", "\t\t\t<Pipes>", '\t\t\t\t<Pipe name="%s-Т1" refStart="%s-К1" refEnd="%s-К2">' % (name, name, name),
                 '\t\t\t\t\t<CircPipe diameter="600"/>', "\t\t\t\t</Pipe>", "\t\t\t</Pipes>", "\t\t</PipeNetwork>"]
    text += ["\t</PipeNetworks>", "\t<Alignments>",
             '\t\t<Alignment name="Ось" length="300" staStart="0">',
             "\t\t\t<CoordGeom><Line><Start>140 0</Start><End>140 300</End></Line></CoordGeom>",
             '\t\t\t<Profile><ProfAlign name="Ось_V"><PVI>0 103</PVI><PVI>150 105</PVI><PVI>300 102</PVI></ProfAlign></Profile>',
             "\t\t</Alignment>", "\t</Alignments>", "\t<PlanFeatures>"]
    for k, desc in enumerate(["Бордюр", "Тротуар", "Кромка", "Забор", "Газон", "Разметка", "Обочина", "Ограждение"]):
        text.append('\t\t<PlanFeature desc="%s %d"><CoordGeom><Line><Start>%s %s</Start><End>%s %s</End></Line></CoordGeom></PlanFeature>'
                    % (desc, k + 1, num(-10.0 - 30 * (k // 4)), num(10.0 + 35 * (k % 4)), num(-10.0 - 30 * (k // 4)), num(40.0 + 35 * (k % 4))))
    text += ["\t</PlanFeatures>", '\t<Parcels name="Квартал">', '\t\t<Parcel name="Участок 1">',
             "\t\t\t<CoordGeom>",
             "\t\t\t\t<Line><Start>-5 150</Start><End>-5 290</End></Line>",
             "\t\t\t\t<Line><Start>-5 290</Start><End>-45 290</End></Line>",
             "\t\t\t\t<Line><Start>-45 290</Start><End>-45 150</End></Line>",
             "\t\t\t\t<Line><Start>-45 150</Start><End>-5 150</End></Line>",
             "\t\t\t</CoordGeom>", "\t\t</Parcel>", "\t</Parcels>", "</LandXML>", ""]
    (OUT / "palette-scene.xml").write_text("\n".join(text), encoding="utf-8")
    print("   palette-scene.xml")


def ground_profile():
    """Профиль земли ProfSurf вдоль трассы (KoTIN-87): рельеф сеткой 100 м и две трассы по её рядам."""
    cols, rows = 5, 4
    zc, zr = [0, 3, -1, 2, 0], [0, 0, 1, 0]

    def z(c, r):
        return 100.0 + zc[c] + zr[r]

    def ground(r, e_from, e_to):
        # через 10 м по ряду сетки: на вершинах — их отметки, между ними — на прямой (прореживание их убирает)
        nums = []
        for s in range(e_from, e_to + 1, 10):
            c, k = divmod(s, 100)
            value = z(c, r) if k == 0 else z(c, r) + (z(c + 1, r) - z(c, r)) * k / 100
            nums.append("%s %s" % (num(float(s)), num(value)))
        return "<PntList2D>%s</PntList2D>" % " ".join(nums)

    pts, faces = [], []
    for r in range(rows):
        for c in range(cols):
            pts.append('\t\t\t\t\t<P id="%d">%s %s %s</P>' % (1 + r * cols + c, num(r * 100.0), num(c * 100.0), num(z(c, r))))
    for r in range(rows - 1):
        for c in range(cols - 1):
            a, b = 1 + r * cols + c, 2 + r * cols + c
            d, e = a + cols, b + cols
            faces += ["\t\t\t\t\t<F>%d %d %d</F>" % (a, b, e), "\t\t\t\t\t<F>%d %d %d</F>" % (a, e, d)]
    text = ['<?xml version="1.0" encoding="UTF-8"?>', "<!--",
            "  Профиль земли ProfSurf (KoTIN-87): рельеф «Рельеф» 400 × 300 м сеткой 100 м, отметки 99…104.",
            "  «Ось 1» — по северу 100 с востока 0 до 400: проект ProfAlign (101 → парабола 120 м у ПК2 на 104 → 99)",
            "  и земля ProfSurf «Земля» через 10 м по ряду сетки: 41 точка, из них 5 на вершинах, остальные на прямой.",
            "  «Ось 2» — по северу 200, только земля, два списка PntList2D: ПК0…ПК1+50 и ПК2+50…ПК4 (между ними разрыв).",
            "  Ожидается: умбровая линия земли на отметках рельефа, у «Оси 1» — стойки от земли до проекта на пикетах;",
            "  «Ось 2» в плане лежит на рельефе, земля — своей линией с разрывом.",
            "  Сгенерирован scripts/gen_test_landxml.py.", "-->",
            '<LandXML xmlns="http://www.landxml.org/schema/LandXML-1.2" version="1.2" date="2026-01-01" time="00:00:00">',
            '\t<Units><Metric linearUnit="meter" areaUnit="squareMeter" volumeUnit="cubicMeter"/></Units>',
            "\t<Surfaces>", '\t\t<Surface name="Рельеф">', '\t\t\t<Definition surfType="TIN">', "\t\t\t\t<Pnts>", *pts,
            "\t\t\t\t</Pnts>", "\t\t\t\t<Faces>", *faces, "\t\t\t\t</Faces>", "\t\t\t</Definition>", "\t\t</Surface>",
            "\t</Surfaces>", "\t<Alignments>",
            '\t\t<Alignment name="Ось 1" length="400" staStart="0">',
            "\t\t\t<CoordGeom><Line><Start>100 0</Start><End>100 400</End></Line></CoordGeom>",
            '\t\t\t<Profile>',
            '\t\t\t\t<ProfSurf name="Земля">%s</ProfSurf>' % ground(1, 0, 400),
            '\t\t\t\t<ProfAlign name="Ось 1_V"><PVI>0 101</PVI><ParaCurve length="120">200 104</ParaCurve><PVI>400 99</PVI></ProfAlign>',
            "\t\t\t</Profile>",
            "\t\t</Alignment>",
            '\t\t<Alignment name="Ось 2" length="400" staStart="0">',
            "\t\t\t<CoordGeom><Line><Start>200 0</Start><End>200 400</End></Line></CoordGeom>",
            '\t\t\t<Profile><ProfSurf name="Земля">%s%s</ProfSurf></Profile>' % (ground(2, 0, 150), ground(2, 250, 400)),
            "\t\t</Alignment>", "\t</Alignments>", "</LandXML>", ""]
    (OUT / "ground-profile.xml").write_text("\n".join(text), encoding="utf-8")
    print("   ground-profile.xml")


if __name__ == "__main__":
    plane()
    orders()
    nodata()
    palette_scene()
    ground_profile()
