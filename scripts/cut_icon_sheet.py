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
OUT = r'C:/Users/Costi/Desktop/E92Launcher__v2.0/app/src/main/res/drawable-nodpi'

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


def main():
    sheet = Image.open(SRC).convert('RGB')
    W, H = sheet.size
    cw, ch = W // COLS, H // ROWS
    print('plansa %dx%d, celula %dx%d' % (W, H, cw, ch))

    if not os.path.isdir(OUT):
        os.makedirs(OUT)

    for idx, name in enumerate(NAMES):
        col, row = idx % COLS, idx // COLS
        cell = sheet.crop((col * cw, row * ch, (col + 1) * cw, (row + 1) * ch))
        cut = extract(cell)

        box = cut.getbbox()
        if box is None:
            print('  %-24s GOL - sarit' % name)
            continue
        cut = cut.crop(box)

        # patrat, cu aer egal de jur imprejur
        side = max(cut.size)
        canvas_side = int(side * (1 + 2 * PAD))
        canvas = Image.new('RGBA', (canvas_side, canvas_side), (0, 0, 0, 0))
        canvas.paste(cut, ((canvas_side - cut.size[0]) // 2,
                           (canvas_side - cut.size[1]) // 2))
        canvas = canvas.resize((SIZE, SIZE), Image.LANCZOS)

        path = os.path.join(OUT, name + '.png')
        canvas.save(path, 'PNG', optimize=True)
        print('  %-24s %dx%d -> %s  %.0f KB' % (
            name, box[2] - box[0], box[3] - box[1], name + '.png',
            os.path.getsize(path) / 1024.0))


main()
