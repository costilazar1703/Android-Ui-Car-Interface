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


import math


def gear(cx, cy, teeth, r_out, r_root, hole, twist=0.0):
    """
    Path data pentru o roata dintata.

    Calculata, nu desenata de mana: o roata cu 12 dinti scrisa manual inseamna 48
    de perechi de coordonate in care o singura greseala se vede imediat ca un dinte
    strambat. Aici se schimba un parametru.

    Dintii au varful plat (doua puncte pe raza exterioara) si radacina plata, cu
    flancurile inclinate - profilul care citeste a piesa frezata, nu a stea.
    """
    step = 2 * math.pi / teeth
    pts = []
    for i in range(teeth):
        a = i * step + twist
        # varful dintelui
        pts.append((cx + r_out * math.cos(a + step * 0.09), cy + r_out * math.sin(a + step * 0.09)))
        pts.append((cx + r_out * math.cos(a + step * 0.41), cy + r_out * math.sin(a + step * 0.41)))
        # radacina dintre dinti
        pts.append((cx + r_root * math.cos(a + step * 0.59), cy + r_root * math.sin(a + step * 0.59)))
        pts.append((cx + r_root * math.cos(a + step * 0.91), cy + r_root * math.sin(a + step * 0.91)))
    d = 'M%.2f,%.2f' % pts[0] + ''.join('L%.2f,%.2f' % q for q in pts[1:]) + 'Z'
    # gaura centrala, in sens invers -> evenOdd o scoate
    d += 'M%.2f,%.2fA%.2f,%.2f 0 1 0 %.2f,%.2fA%.2f,%.2f 0 1 0 %.2f,%.2fZ' % (
        cx - hole, cy, hole, hole, cx + hole, cy, hole, hole, cx - hole, cy)
    return d


def ring(cx, cy, r_out, r_in):
    """Inel plin: cerc exterior plus cerc interior in sens invers (evenOdd)."""
    return ('M%.2f,%.2fA%.2f,%.2f 0 1 1 %.2f,%.2fA%.2f,%.2f 0 1 1 %.2f,%.2fZ'
            'M%.2f,%.2fA%.2f,%.2f 0 1 0 %.2f,%.2fA%.2f,%.2f 0 1 0 %.2f,%.2fZ') % (
        cx - r_out, cy, r_out, r_out, cx + r_out, cy, r_out, r_out, cx - r_out, cy,
        cx - r_in, cy, r_in, r_in, cx + r_in, cy, r_in, r_in, cx - r_in, cy)



def rrect(x, y, w, h, r):
    """
    Dreptunghi cu colturi rotunjite, de la coltul stanga-sus, latime w, inaltime h.

    Exista pentru ca varianta scrisa de mana greseste mereu la fel: segmentul
    orizontal trebuie sa fie w MINUS cele doua raze, nu w. Cu w intreg, fiecare
    placa iese cu 2r mai lata decat trebuie, iar intr-o grila de 3x3 placile se
    suprapun si se contopesc in bare - exact ce s-a intamplat prima data.
    """
    return ('M%.2f,%.2fh%.2fa%.2f,%.2f 0 0 1 %.2f,%.2fv%.2f'
            'a%.2f,%.2f 0 0 1 %.2f,%.2fh%.2fa%.2f,%.2f 0 0 1 %.2f,%.2f'
            'v%.2fa%.2f,%.2f 0 0 1 %.2f,%.2fZ') % (
        x + r, y, w - 2 * r, r, r, r, r, h - 2 * r,
        r, r, -r, r, -(w - 2 * r), r, r, -r, -r,
        -(h - 2 * r), r, r, r, -r)


def grid(cols, rows, x0, y0, cell, gap, r):
    """Grila de placi rotunjite, fara suprapuneri prin constructie."""
    out = ''
    for row in range(rows):
        for col in range(cols):
            out += rrect(x0 + col * (cell + gap), y0 + row * (cell + gap), cell, cell, r)
    return out


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
    # Inelul deschis spre dreapta. Golul nu e decorativ: prin el iese varful
    # triunghiului, si exact asta face silueta recognoscibila de la distanta.
    ('M18.46,17.05A8.2,8.2 0 1 1 18.46,6.95L17.36,7.81A6.8,6.8 0 1 0 17.36,16.19Z', False),
    # Triunghiul de redare, cu varful in dreptul golului.
    ('M9.5,7.2L18.9,12L9.5,16.8Z', False),
])

# --- Bluetooth: runa cu fateta ---------------------------------------------
ICONS['ic_menu_bluetooth'] = (24, 24, [
    # Runa singura, centrata. Undele de emisie s-au dovedit zgomot: la 85 px
    # se lipeau de runa si citeau ca o pata, nu ca un semnal.
    ('M12.4,1.4L18,7L13.4,11.6L18,16.2L12.4,21.8H11.1V14L6.9,18.2'
     'L5.5,16.8L10.8,11.5L5.5,6.2L6.9,4.8L11.1,9V1.4z'
     'M13,4.6V9L15.2,6.8z'
     'M13,14V18.4L15.2,16.2z', True),
])

# --- Settings: roata dintata cu inel interior si bolt ----------------------
ICONS['ic_menu_settings'] = (24, 24, [
    # Roata mare, 12 dinti. Nu mai e roata Material plata: e un angrenaj.
    (gear(10.2, 10.2, 12, 8.4, 6.5, 3.0), True),
    # Lip-ul frezat din jurul butucului.
    (ring(10.2, 10.2, 4.5, 3.6), True),
    # Roata mica, angrenata jos-dreapta. Dintii ei sunt decalati cu jumatate
    # de pas ca sa intre intre dintii celei mari, nu peste ei.
    (gear(18.3, 18.3, 8, 5.4, 4.0, 1.7, twist=0.39), True),
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
    # Noua placi EGALE, generate - vezi rrect() pentru ce se strica cand sunt
    # scrise de mana. Placi de latimi diferite aratau a widget-uri, iar colturi
    # taiate aratau a tabel; asta e intre ele.
    (grid(3, 3, 3.4, 3.4, 5.2, 1.3, 1.1), False),
])

# --- Navigation: busola ----------------------------------------------------
ICONS['ic_menu_navigation'] = (24, 24, [
    # Harta pliata in trei panouri; cutele sunt goluri, nu linii desenate peste.
    ('M20.5,3L20.34,3.03L15,5.1L9,3L3.38,4.9C3.16,4.97 3,5.15 3,5.38V20.5'
     'C3,20.78 3.22,21 3.5,21L3.66,20.97L9,18.9L15,21L20.62,19.1'
     'C20.84,19.03 21,18.85 21,18.62V3.5C21,3.22 20.78,3 20.5,3Z'
     'M9.6,5.16L14.4,6.84V18.84L9.6,17.16Z', True),
    # Un singur pin, asezat peste cuta din mijloc. Traseul in S care era aici
    # inainte se aglomera cu cutele si nu se mai citea nimic la scara mica.
    ('M12,6.1C10.1,6.1 8.6,7.6 8.6,9.5C8.6,12.1 12,15.9 12,15.9'
     'S15.4,12.1 15.4,9.5C15.4,7.6 13.9,6.1 12,6.1Z'
     'M12,10.9A1.35,1.35 0 1 1 12,8.2A1.35,1.35 0 1 1 12,10.9Z', True),
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
