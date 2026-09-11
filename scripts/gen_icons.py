# -*- coding: utf-8 -*-
"""
Generator pentru iconitele cu gradient ale launcher-ului.

De ce generat si nu scris de mana: fiecare iconita are nevoie de DOUA variante
identice ca forma dar diferite ca material (otel cand e stinsa, portocaliu cand e
selectata) plus un selector care le leaga. Scrise de mana ar fi 36 de fisiere in
care acelasi path apare de doua ori - adica 36 de ocazii ca cele doua variante sa
o ia razna una fata de alta. Aici forma e definita o singura data.

Gradientul e pe verticala, luminos sus si stins jos: asa arata o piesa metalica
luminata de sus, care e exact senzatia cautata in bordul unui BMW.
"""
import io, os

OUT = r'C:/Users/Costi/Desktop/E92Launcher__v2.0/app/src/main/res/drawable'

# Materiale: (sus, mijloc, jos)
STEEL = ('#FFFFFFFF', '#FFC2C9D2', '#FF6E7783')
ORANGE = ('#FFFFE4B8', '#FFFF8A1F', '#FFC93F00')

VECTOR_HEAD = (
    '<?xml version="1.0" encoding="utf-8"?>\n'
    '<!-- GENERAT de scripts/gen_icons.py - nu edita direct, editeaza scriptul. -->\n'
    '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
    '    xmlns:aapt="http://schemas.android.com/aapt"\n'
    '    android:width="{w}dp"\n'
    '    android:height="{h}dp"\n'
    '    android:viewportWidth="{vw}"\n'
    '    android:viewportHeight="{vh}">\n'
)

PATH_TMPL = (
    '\n    <path{ftype}\n'
    '        android:pathData="{d}">\n'
    '        <aapt:attr name="android:fillColor">\n'
    '            <gradient\n'
    '                android:type="linear"\n'
    '                android:startX="0" android:startY="0"\n'
    '                android:endX="0" android:endY="{vh}">\n'
    '                <item android:offset="0" android:color="{c0}" />\n'
    '                <item android:offset="0.55" android:color="{c1}" />\n'
    '                <item android:offset="1" android:color="{c2}" />\n'
    '            </gradient>\n'
    '        </aapt:attr>\n'
    '    </path>\n'
)

SELECTOR = (
    '<?xml version="1.0" encoding="utf-8"?>\n'
    '<!-- GENERAT. Forma e aceeasi; se schimba doar materialul. -->\n'
    '<selector xmlns:android="http://schemas.android.com/apk/res/android">\n'
    '    <item android:state_activated="true" android:drawable="@drawable/{name}_on" />\n'
    '    <item android:drawable="@drawable/{name}_off" />\n'
    '</selector>\n'
)

# ---------------------------------------------------------------- iconitele
# name -> (viewport_w, viewport_h, [(pathData, evenOdd), ...])
ICONS = {}

# --- Car Info: sigla BMW (inel + patru sferturi) ---------------------------
ICONS['ic_menu_car_info'] = (24, 24, [
    # inelul exterior, cu o muchie interioara subtire
    ('M12,1A11,11 0 1 0 12,23A11,11 0 1 0 12,1z'
     'M12,2.7A9.3,9.3 0 1 1 12,21.3A9.3,9.3 0 1 1 12,2.7z', True),
    ('M12,3.9A8.1,8.1 0 1 0 12,20.1A8.1,8.1 0 1 0 12,3.9z'
     'M12,4.9A7.1,7.1 0 1 1 12,19.1A7.1,7.1 0 1 1 12,4.9z', True),
    # sfertul dreapta-sus si cel stanga-jos (celelalte doua raman goale)
    ('M12,12L12,4.9A7.1,7.1 0 0 1 19.1,12Z', False),
    ('M12,12L12,19.1A7.1,7.1 0 0 1 4.9,12Z', False),
])

# --- Apple CarPlay: masina in insigna rotunjita ----------------------------
ICONS['ic_menu_carplay'] = (24, 24, [
    # insigna
    ('M5.2,2h13.6A3.2,3.2 0 0 1 22,5.2v13.6A3.2,3.2 0 0 1 18.8,22H5.2'
     'A3.2,3.2 0 0 1 2,18.8V5.2A3.2,3.2 0 0 1 5.2,2z'
     'M5.2,3.9A1.3,1.3 0 0 0 3.9,5.2v13.6a1.3,1.3 0 0 0 1.3,1.3h13.6'
     'a1.3,1.3 0 0 0 1.3,-1.3V5.2a1.3,1.3 0 0 0 -1.3,-1.3z', True),
    # botul masinii vazut din fata
    ('M8.1,7.6h7.8c0.5,0 0.9,0.3 1.1,0.8l1,2.6c0.4,0.2 0.7,0.6 0.7,1.1v3.3'
     'c0,0.4 -0.3,0.7 -0.7,0.7h-0.8c-0.4,0 -0.7,-0.3 -0.7,-0.7v-0.5H7.5v0.5'
     'c0,0.4 -0.3,0.7 -0.7,0.7H6c-0.4,0 -0.7,-0.3 -0.7,-0.7v-3.3'
     'c0,-0.5 0.3,-0.9 0.7,-1.1l1,-2.6c0.2,-0.5 0.6,-0.8 1.1,-0.8z'
     'M7.7,11.3h8.6l-0.7,-1.8H8.4z'
     'M7.4,12.4a0.8,0.8 0 1 0 0,1.6a0.8,0.8 0 1 0 0,-1.6z'
     'M16.6,12.4a0.8,0.8 0 1 0 0,1.6a0.8,0.8 0 1 0 0,-1.6z', True),
])

# --- Bluetooth: runa cu fateta ---------------------------------------------
ICONS['ic_menu_bluetooth'] = (24, 24, [
    ('M12.4,1.4 L18,7 L13.4,11.6 L18,16.2 L12.4,21.8 H11.1 V14 L6.9,18.2 '
     'L5.5,16.8 L10.8,11.5 L5.5,6.2 L6.9,4.8 L11.1,9 V1.4z'
     'M13,4.6 V9 L15.2,6.8z'
     'M13,14 V18.4 L15.2,16.2z', True),
])

# --- Settings: roata dintata cu inel interior si bolt ----------------------
ICONS['ic_menu_settings'] = (24, 24, [
    ('M19.14,12.94c0.04,-0.3 0.06,-0.61 0.06,-0.94c0,-0.32 -0.02,-0.64 -0.07,-0.94'
     'l2.03,-1.58c0.18,-0.14 0.23,-0.41 0.12,-0.61l-1.92,-3.32'
     'c-0.12,-0.22 -0.37,-0.29 -0.59,-0.22l-2.39,0.96'
     'c-0.5,-0.38 -1.03,-0.7 -1.62,-0.94L14.4,2.81'
     'c-0.04,-0.24 -0.24,-0.41 -0.48,-0.41h-3.84c-0.24,0 -0.43,0.17 -0.47,0.41'
     'L9.25,5.35C8.66,5.59 8.12,5.92 7.63,6.29L5.24,5.33'
     'c-0.22,-0.08 -0.47,0 -0.59,0.22L2.74,8.87C2.62,9.08 2.66,9.34 2.86,9.48'
     'l2.03,1.58C4.84,11.36 4.8,11.69 4.8,12s0.02,0.64 0.07,0.94'
     'l-2.03,1.58c-0.18,0.14 -0.23,0.41 -0.12,0.61l1.92,3.32'
     'c0.12,0.22 0.37,0.29 0.59,0.22l2.39,-0.96c0.5,0.38 1.03,0.7 1.62,0.94'
     'l0.36,2.54c0.05,0.24 0.24,0.41 0.48,0.41h3.84c0.24,0 0.44,-0.17 0.47,-0.41'
     'l0.36,-2.54c0.59,-0.24 1.13,-0.56 1.62,-0.94l2.39,0.96'
     'c0.22,0.08 0.47,0 0.59,-0.22l1.92,-3.32c0.12,-0.22 0.07,-0.47 -0.12,-0.61z'
     'M12,15.9A3.9,3.9 0 1 1 12,8.1A3.9,3.9 0 1 1 12,15.9z', True),
    # lip-ul frezat din jurul gaurii centrale
    ('M12,6.9A5.1,5.1 0 1 0 12,17.1A5.1,5.1 0 1 0 12,6.9z'
     'M12,7.9A4.1,4.1 0 1 1 12,16.1A4.1,4.1 0 1 1 12,7.9z', True),
])

# --- Dashboard: vitezometru cu ac ------------------------------------------
ICONS['ic_menu_dashboard'] = (24, 24, [
    ('M20.38,8.57l-1.23,1.85a8,8 0 0 1 -0.22,7.58H5.07A8,8 0 0 1 15.58,6.85'
     'l1.85,-1.23A10,10 0 0 0 3.35,19a2,2 0 0 0 1.72,1h13.85a2,2 0 0 0 1.74,-1'
     'a10,10 0 0 0 -0.28,-10.43z', False),
    ('M10.59,15.41a2,2 0 0 0 2.83,0l5.66,-8.49 -8.49,5.66a2,2 0 0 0 0,2.83z', False),
    # gradatii pe cadran
    ('M6.1,15.4h2.1v1.2H6.1z M15.8,15.4h2.1v1.2h-2.1z M7.2,10.9l1.5,1.5l-0.85,0.85'
     'l-1.5,-1.5z', False),
])

# --- Applications: grila 3x3 -----------------------------------------------
ICONS['ic_menu_apps'] = (24, 24, [
    ('M4,4h4.2v4.2H4zM9.9,4h4.2v4.2H9.9zM15.8,4H20v4.2h-4.2z'
     'M4,9.9h4.2v4.2H4zM9.9,9.9h4.2v4.2H9.9zM15.8,9.9H20v4.2h-4.2z'
     'M4,15.8h4.2V20H4zM9.9,15.8h4.2V20H9.9zM15.8,15.8H20V20h-4.2z', False),
])

# --- Navigation: busola ----------------------------------------------------
ICONS['ic_menu_navigation'] = (24, 24, [
    ('M12,1.6A10.4,10.4 0 1 0 12,22.4A10.4,10.4 0 1 0 12,1.6z'
     'M12,3.4A8.6,8.6 0 1 1 12,20.6A8.6,8.6 0 1 1 12,3.4z', True),
    ('M14.19,14.19L6,18l3.81,-8.19L18,6z', False),
    ('M11.4,0.4h1.2v2.2h-1.2z M11.4,21.4h1.2v2.2h-1.2z'
     'M21.4,11.4h2.2v1.2h-2.2z M0.4,11.4h2.2v1.2H0.4z', False),
])

# --- Media: nota dubla -----------------------------------------------------
ICONS['ic_menu_media'] = (24, 24, [
    ('M20,3v11.6a3.4,3.4 0 1 1 -2,-3.1V7.4l-7,1.5v8.7a3.4,3.4 0 1 1 -2,-3.1V6.3z', False),
])

# --- Telephone: receptor ---------------------------------------------------
ICONS['ic_menu_telephone'] = (24, 24, [
    ('M6.62,10.79c1.44,2.83 3.76,5.14 6.59,6.59l2.2,-2.2'
     'c0.27,-0.27 0.67,-0.36 1.02,-0.24 1.12,0.37 2.33,0.57 3.57,0.57'
     'c0.55,0 1,0.45 1,1V20c0,0.55 -0.45,1 -1,1 -9.39,0 -17,-7.61 -17,-17'
     'c0,-0.55 0.45,-1 1,-1h3.5c0.55,0 1,0.45 1,1 0,1.25 0.2,2.45 0.57,3.57'
     'c0.11,0.35 0.03,0.74 -0.25,1.02z', False),
])

# --- ConnectedDrive: glob cu meridiane -------------------------------------
ICONS['ic_menu_connecteddrive'] = (24, 24, [
    ('M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2z'
     'M11,19.93c-3.95,-0.49 -7,-3.85 -7,-7.93 0,-0.62 0.08,-1.21 0.21,-1.79'
     'L9,15v1c0,1.1 0.9,2 2,2z'
     'M17.9,17.39c-0.26,-0.81 -1,-1.39 -1.9,-1.39h-1v-3c0,-0.55 -0.45,-1 -1,-1H8v-2h2'
     'c0.55,0 1,-0.45 1,-1V7h2c1.1,0 2,-0.9 2,-2v-0.41c2.93,1.19 5,4.06 5,7.41'
     'c0,2.08 -0.8,3.97 -2.1,5.39z', False),
])

# --- Weather: soare in spatele norului -------------------------------------
ICONS['ic_menu_weather'] = (24, 24, [
    ('M12.5,2.5h-1.4v2.4h1.4zM7.1,5.2L5.9,6.4 7.6,8.1 8.8,6.9z'
     'M17.7,5.2L16,6.9l1.2,1.2 1.7,-1.7z'
     'M2.6,10.6v1.4h2.4v-1.4zM18.9,10.6v1.4h2.4v-1.4z'
     'M11.8,6.3c-2.4,0 -4.4,1.9 -4.5,4.3 0.6,-0.2 1.2,-0.4 1.9,-0.4 0.3,0 0.6,0 0.9,0.1'
     '0.4,-1.2 1.5,-2 2.8,-2 1.6,0 3,1.3 3,3 0,0.4 -0.1,0.8 -0.2,1.1'
     '0.7,0.3 1.3,0.7 1.8,1.3 0.5,-0.7 0.8,-1.5 0.8,-2.4 0,-2.8 -2.3,-5 -5.1,-5z', False),
    ('M16.9,14.4c-0.4,-2 -2.2,-3.5 -4.3,-3.5 -1.7,0 -3.1,0.9 -3.8,2.3'
     '-1.8,0.2 -3.2,1.7 -3.2,3.5 0,2 1.6,3.5 3.5,3.5h7.6c1.6,0 2.9,-1.3 2.9,-2.9'
     'c0,-1.5 -1.2,-2.8 -2.7,-2.9z', False),
])

# --- Messages: plic --------------------------------------------------------
ICONS['ic_menu_messages'] = (24, 24, [
    ('M3.2,5h17.6A1.2,1.2 0 0 1 22,6.2v11.6A1.2,1.2 0 0 1 20.8,19H3.2'
     'A1.2,1.2 0 0 1 2,17.8V6.2A1.2,1.2 0 0 1 3.2,5z'
     'M4.6,6.8l7.4,5 7.4,-5z'
     'M3.8,8.3v8.9h16.4V8.3l-7.8,5.3c-0.4,0.3 -0.9,0.3 -1.2,0z', True),
])

# --- Silueta E92, folosita ca iconita de rand pentru "Vehicle app" -----------
ICONS['ic_tile_e92'] = (100, 40, [
    ('M5,31C5,27 6,25 9,24L18,22C21,15 27,11 35,10L57,9C67,9 75,12 82,16L91,19'
     'C95,20 96,23 96,27L96,30C96,31.5 95,32.5 93.5,32.5L87,32.5'
     'C87,27.5 83,24 78.5,24C74,24 70,27.5 70,32.5L33,32.5'
     'C33,27.5 29,24 24.5,24C20,24 16,27.5 16,32.5L7.5,32.5'
     'C6,32.5 5,31.5 5,31Z'
     'M37,13L54,12L54,20L34.5,20C34.5,17 35.5,14.5 37,13Z'
     'M57.5,12L66,11.8C72,12.4 77,14.5 80.5,17.2L57.5,20Z', True),
    ('M24.5,25.5a7,7 0 1 1 0,14a7,7 0 1 1 0,-14Z'
     'M24.5,29a3.5,3.5 0 1 0 0,7a3.5,3.5 0 1 0 0,-7Z', True),
    ('M78.5,25.5a7,7 0 1 1 0,14a7,7 0 1 1 0,-14Z'
     'M78.5,29a3.5,3.5 0 1 0 0,7a3.5,3.5 0 1 0 0,-7Z', True),
])


def emit(name, w, h, paths, mat, suffix):
    c0, c1, c2 = mat
    body = VECTOR_HEAD.format(w=w, h=h, vw=w, vh=h)
    for d, even in paths:
        ftype = '\n        android:fillType="evenOdd"' if even else ''
        body += PATH_TMPL.format(ftype=ftype, d=d, vh=h, c0=c0, c1=c1, c2=c2)
    body += '\n</vector>\n'
    io.open(os.path.join(OUT, name + suffix + '.xml'), 'w', encoding='utf-8').write(body)


count = 0
for name, (w, h, paths) in ICONS.items():
    emit(name, w, h, paths, STEEL, '_off')
    emit(name, w, h, paths, ORANGE, '_on')
    io.open(os.path.join(OUT, name + '.xml'), 'w', encoding='utf-8').write(
        SELECTOR.format(name=name))
    count += 1
print('generate %d iconite (%d fisiere)' % (count, count * 3))
