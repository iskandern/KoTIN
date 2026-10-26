#!/usr/bin/env python3
"""
Файлы историй интерфейса (?story=…, docs/ui-sync-plan.md): маленькие синтетические LandXML для обеих web-сборок.
Файлы — в app/src/wasmJsMain/resources/stories (webhtml берёт их оттуда же).

  python3 scripts/gen_story_files.py

Только стандартная библиотека; файлы детерминированы.
"""
from pathlib import Path

OUT = Path(__file__).resolve().parent.parent / "app/src/wasmJsMain/resources/stories"
OUT.mkdir(parents=True, exist_ok=True)


def num(x):
    return repr(round(x, 6))


# паспорт ограждения: свойств больше, чем влезает в свёрнутую карточку
PASSPORT = [("Материал", "сталь"), ("Высота, м", "2.50"), ("Год", "2019"), ("Секций", "16"), ("Покрытие", "цинк"),
            ("Цвет", "серый"), ("Фундамент", "столбчатый"), ("Шаг опор, м", "2.50"), ("Калитки", "1"), ("Ворота", "нет"),
            ("Владелец", "Квартал 1"), ("Состояние", "хорошее"), ("Осмотр", "2026-05-14"), ("Примечание", "без замечаний")]


def story():
    """Рельеф 3 × 3 точки, трасса через него, три точки съёмки в одном месте (кандидаты выбора), ограждение с паспортом."""
    cols, rows, step = 3, 3, 50.0
    pts, faces = [], []
    for r in range(rows):
        for c in range(cols):
            z = 100.0 + 1.5 * (c % 2) + 0.5 * r
            pts.append('\t\t\t\t\t<P id="%d">%s %s %s</P>' % (1 + r * cols + c, num(r * step), num(c * step), num(z)))
    for r in range(rows - 1):
        for c in range(cols - 1):
            a, b = 1 + r * cols + c, 2 + r * cols + c
            d, e = a + cols, b + cols
            faces += ["\t\t\t\t\t<F>%d %d %d</F>" % (a, b, e), "\t\t\t\t\t<F>%d %d %d</F>" % (a, e, d)]
    text = ['<?xml version="1.0" encoding="UTF-8"?>', "<!--",
            "  История интерфейса: рельеф «Рельеф» 100 × 100 м, трасса «Ось 1» по северу 50 с пикетами,",
            "  три точки съёмки «Т1»…«Т3» в одном месте (восток 50, север 50) — кандидаты одного нажатия —",
            "  и ограждение по северу 90 с паспортом из 14 свойств (Feature).",
            "  Сгенерирован scripts/gen_story_files.py.", "-->",
            '<LandXML xmlns="http://www.landxml.org/schema/LandXML-1.2" version="1.2" date="2026-01-01" time="00:00:00">',
            '\t<Units><Metric linearUnit="meter" areaUnit="squareMeter" volumeUnit="cubicMeter"/></Units>',
            "\t<CgPoints>"]
    for i, (code, desc) in enumerate([("EP", "угол здания"), ("TREE", "дерево"), ("PK", "колодец")]):
        text.append('\t\t<CgPoint name="Т%d" code="%s" desc="%s">50 50 %s</CgPoint>' % (i + 1, code, desc, num(101.0 + i * 0.25)))
    text += ["\t</CgPoints>", "\t<Surfaces>", '\t\t<Surface name="Рельеф">', '\t\t\t<Definition surfType="TIN">',
             "\t\t\t\t<Pnts>", *pts, "\t\t\t\t</Pnts>", "\t\t\t\t<Faces>", *faces, "\t\t\t\t</Faces>",
             "\t\t\t</Definition>", "\t\t</Surface>", "\t</Surfaces>", "\t<Alignments>",
             '\t\t<Alignment name="Ось 1" length="100" staStart="0">',
             "\t\t\t<CoordGeom><Line><Start>50 0</Start><End>50 100</End></Line></CoordGeom>",
             '\t\t\t<Profile><ProfAlign name="Ось 1_V"><PVI>0 100.5</PVI><PVI>50 102</PVI><PVI>100 101</PVI></ProfAlign></Profile>',
             "\t\t</Alignment>", "\t</Alignments>", "\t<PlanFeatures>",
             '\t\t<PlanFeature name="Ограждение" desc="Металлический забор">',
             "\t\t\t<CoordGeom><Line><Start>90 10</Start><End>90 90</End></Line></CoordGeom>",
             '\t\t\t<Feature name="Паспорт">%s</Feature>' % "".join(
                 '<Property label="%s" value="%s"/>' % p for p in PASSPORT),
             "\t\t</PlanFeature>", "\t</PlanFeatures>", "</LandXML>", ""]
    (OUT / "story.xml").write_text("\n".join(text), encoding="utf-8")
    print("   story.xml")


def broken():
    """Файл, который не читается: ошибка открытия в карточке файла."""
    (OUT / "broken.xml").write_text('<?xml version="1.0" encoding="UTF-8"?>\n<LandXML><Surfaces><Surface name="Обрыв">\n',
                                    encoding="utf-8")
    print("   broken.xml")


if __name__ == "__main__":
    story()
    broken()
