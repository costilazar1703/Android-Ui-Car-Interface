# -*- coding: utf-8 -*-
"""
Taie plansa de 12 iconite generate si le scoate fundalul.

De ce nu merge luminozitate -> alfa, metoda obisnuita pentru grafica alb-pe-negru:
iconitele astea au zone NEGRE inauntru - sferturile siglei BMW, cadranul
vitezometrului, patratele din Apps. Cu alfa luata din luminozitate, exact alea ar
deveni gauri, iar prin sigla BMW s-ar vedea fundalul.

Metoda folosita: fundalul e umplut prin inundare (flood fill) pornind de la
marginile celulei. Ce nu poate fi atins de afara ramane opac, indiferent cat e de
intunecat. Asa gaurile din interior supravietuiesc.

Pentru halou se face o exceptie: pixelii de langa fundal nu primesc alfa plin, ci
proportional cu luminozitatea. Fara asta, aura portocalie ar fi decupata cu
foarfeca si s-ar vedea ca un contur murdar peste dala aprinsa.
"""
import io, os, sys
from collections import deque
from PIL import Image, ImageFilter

SRC = r'C:/Users/Costi/Downloads/ChatGPT Image Sep 11, 2026, 02_10_25 PM.png'
# Relativ la script, nu o cale absoluta: proiectul a fost mutat o data in
# Desktop\PROJECTS, iar calea veche scrisa aici ar fi taiat iconitele intr-un
# folder care nu mai exista.
OUT = os.path.normpath(os.path.join(
    os.path.dirname(os.path.abspath(__file__)), '..', 'app', 'src', 'main', 'res', 'drawable-nodpi'))

COLS, ROWS = 4, 3

# Ordinea de pe plansa, citita ca un text: stanga-dreapta, sus-jos.
NAMES = [
    'ic_menu_car_info', 'ic_menu_carplay', 'ic_menu_bluetooth', 'ic_menu_dashboard',
    'ic_menu_settings', 'ic_menu_apps', 'ic_menu_navigation', 'ic_menu_media',
    'ic_menu_telephone', 'ic_menu_connecteddrive', 'ic_menu_weather', 'ic_menu_messages',
]

# Sub pragul asta un pixel e considerat fundal cand vine vorba de inundare.
BG_LUM = 14
# Latimea benzii de langa fundal in care alfa urmeaza luminozitatea (halou).
FEATHER_BAND = 3
# Cat de repede urca alfa in banda de halou.
FEATHER_GAIN = 3.4

# Dimensiunea finala. Pe ecran iconita ocupa ~90 px in cel mai mare caz
# (dala la densitate 160); 192 da peste dublu, deci ramane clara, si costa
# 147 KB decodati - contra 1 MB daca as pastra 512, inmultit cu 12.
SIZE = 192
PAD = 0.06   # aer in jurul iconitei, ca fraciune din latura


def luminance(px):
    return (px[0] * 299 + px[1] * 587 + px[2] * 114) // 1000


def extract(cell):
    """Returneaza celula cu fundalul scos, decupata pe continut."""
    w, h = cell.size
    rgb = cell.convert('RGB')
    pix = rgb.load()

    lum = [[luminance(pix[x, y]) for y in range(h)] for x in range(w)]

    # --- inundare dinspre margini peste pixelii intunecati ---
    is_bg = [[False] * h for _ in range(w)]
    q = deque()
    for x in range(w):
        for y in (0, h - 1):
            if lum[x][y] <= BG_LUM and not is_bg[x][y]:
                is_bg[x][y] = True
                q.append((x, y))
    for y in range(h):
        for x in (0, w - 1):
            if lum[x][y] <= BG_LUM and not is_bg[x][y]:
                is_bg[x][y] = True
                q.append((x, y))
    while q:
        x, y = q.popleft()
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            nx, ny = x + dx, y + dy
            if 0 <= nx < w and 0 <= ny < h and not is_bg[nx][ny] and lum[nx][ny] <= BG_LUM:
                is_bg[nx][ny] = True
                q.append((nx, ny))

    # --- distanta pana la fundal, doar cateva niveluri (banda de halou) ---
    dist = [[0 if is_bg[x][y] else FEATHER_BAND + 1 for y in range(h)] for x in range(w)]
    frontier = [(x, y) for x in range(w) for y in range(h) if is_bg[x][y]]
    for step in range(1, FEATHER_BAND + 1):
        nxt = []
        for x, y in frontier:
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                nx, ny = x + dx, y + dy
                if 0 <= nx < w and 0 <= ny < h and dist[nx][ny] > FEATHER_BAND:
                    dist[nx][ny] = step
                    nxt.append((nx, ny))
        frontier = nxt

    alpha = Image.new('L', (w, h), 0)
    ap = alpha.load()
    for x in range(w):
        for y in range(h):
            if is_bg[x][y]:
                ap[x, y] = 0
            elif dist[x][y] <= FEATHER_BAND:
                # in banda de langa fundal: alfa urmeaza lumina (halou moale)
                ap[x, y] = min(255, int(lum[x][y] * FEATHER_GAIN))
            else:
                ap[x, y] = 255

    alpha = alpha.filter(ImageFilter.GaussianBlur(0.6))
    out = rgb.copy()
    out.putalpha(alpha)
    return out


def drop_edge_fragments(rgba, alpha_min=20):
    """
    Sterge bucatile care ating marginea STANGA sau DREAPTA a celulei.

    Generatorul nu asaza iconitele exact in celule egale: pe plansa meteo o raza a
    soarelui intra in celula lunii si o bucata de nor in celula ploii. Fragmentele
    alea largesc conturul, iar iconita reala iese micsorata cand e incadrata.

    Nu se pastreaza doar bucata cea mai mare: razele soarelui, picaturile de
    ploaie si fulgerul sunt piese separate si fac parte din desen. Se sterg doar
    bucatile care ating marginea SI nu sunt cea mai mare din celula.
    """
    w, h = rgba.size
    a = rgba.split()[3].load()
    seen = bytearray(w * h)
    comps = []
    for sy in range(h):
        for sx in range(w):
            i = sy * w + sx
            if seen[i] or a[sx, sy] <= alpha_min:
                continue
            comp, touches = [], False
            q = deque([(sx, sy)])
            seen[i] = 1
            while q:
                x, y = q.popleft()
                comp.append((x, y))
                if x == 0 or x == w - 1:
                    touches = True
                for nx, ny in ((x + 1, y), (x - 1, y), (x, y + 1), (x, y - 1)):
                    if 0 <= nx < w and 0 <= ny < h:
                        j = ny * w + nx
                        if not seen[j] and a[nx, ny] > alpha_min:
                            seen[j] = 1
                            q.append((nx, ny))
            comps.append((comp, touches))

    # Cea mai mare bucata e iconita celulei - se pastreaza chiar daca atinge
    # marginea. Soarele si norul SUNT cele care se revarsa in celula vecina;
    # o regula „orice atinge marginea" le-ar fi sters pe ele cu totul.
    largest = max(comps, key=lambda c: len(c[0]))[0] if comps else None
    kill = [p for comp, touches in comps if touches and comp is not largest for p in comp]
    if kill:
        out = rgba.copy()
        px = out.load()
        for x, y in kill:
            px[x, y] = (0, 0, 0, 0)
        # halou-ul slab ramas langa fragment, sub prag
        alpha = out.split()[3].point(lambda v: v if v > alpha_min else 0)
        out.putalpha(alpha)
        return out
    return rgba


def square(cut):
    """Patrat cu aer egal de jur imprejur, redimensionat la SIZE."""
    side = max(cut.size)
    canvas_side = int(side * (1 + 2 * PAD))
    canvas = Image.new('RGBA', (canvas_side, canvas_side), (0, 0, 0, 0))
    canvas.paste(cut, ((canvas_side - cut.size[0]) // 2,
                       (canvas_side - cut.size[1]) // 2))
    return canvas.resize((SIZE, SIZE), Image.LANCZOS)


def cut_sheet(src, cols, rows, names):
    sheet = Image.open(src).convert('RGB')
    W, H = sheet.size
    cw, ch = W // cols, H // rows
    print('plansa %dx%d, %dx%d celule de %dx%d' % (W, H, cols, rows, cw, ch))
    if not os.path.isdir(OUT):
        os.makedirs(OUT)

    for idx, name in enumerate(names):
        col, row = idx % cols, idx // cols
        cell = sheet.crop((col * cw, row * ch, (col + 1) * cw, (row + 1) * ch))
        cut = drop_edge_fragments(extract(cell))
        box = cut.getbbox()
        if box is None:
            print('  %-24s GOL - sarit' % name)
            continue
        canvas = square(cut.crop(box))
        path = os.path.join(OUT, name + '.png')
        canvas.save(path, 'PNG', optimize=True)
        print('  %-24s %dx%d -> %s  %.0f KB' % (
            name, box[2] - box[0], box[3] - box[1], name + '.png',
            os.path.getsize(path) / 1024.0))


def main():
    """
    Fara argumente: plansa originala de 12.
    Cu argumente:   cut_icon_sheet.py <plansa> <coloane> <randuri> nume1 nume2 ...
    """
    if len(sys.argv) > 1:
        src, cols, rows = sys.argv[1], int(sys.argv[2]), int(sys.argv[3])
        cut_sheet(src, cols, rows, sys.argv[4:])
    else:
        cut_sheet(SRC, COLS, ROWS, NAMES)


# Garda: fisierul e importat de cut_single_icon.py pentru `extract`. Fara ea,
# un simplu import ar re-taia toata plansa peste iconitele existente - inclusiv
# peste cele inlocuite manual intre timp.
if __name__ == '__main__':
    main()
