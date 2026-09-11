# -*- coding: utf-8 -*-
"""
Retusarea fotografiei de E92 folosita ca imagine a dalei Car Info.

Doua interventii, ambele cerute explicit:
  1. jantele trec pe hyper gray - deschise si metalice, nu negru mat;
  2. farurile se STING - inelele de angel eyes nu mai lumineaza, iar caldura
     pe care o aruncau pe grila, capota si bara dispare odata cu ele.

De ce un script si nu o editare manuala salvata peste poza: fotografia sursa
ramane neatinsa, iar daca vrei jantele cu doua trepte mai deschise se schimba
un numar si se re-ruleaza. O poza retusata "in mana" nu se mai poate regla.

Coordonatele sunt masurate pe sursa de 1448x1086. Rotile nu sunt cercuri:
masina e vazuta din trei sferturi, deci sunt elipse, iar raza pe orizontala e
sensibil mai mica decat cea pe verticala.
"""
import math
import os
import sys
from collections import deque

from PIL import Image, ImageDraw, ImageFilter

SRC = r'C:/Users/Costi/Downloads/ChatGPT Image Sep 11, 2026, 08_19_52 PM.png'

# (cx, cy, rx, ry) - eliptic, doar JANTA; anvelopa ramane in afara si neagra.
RIMS = [
    (185, 666, 54, 62),    # spate
    (768, 703, 60, 74),    # fata
]

# Dreptunghiul in care se sting luminile: cele doua faruri plus zona pe care
# isi arunca lumina. Generos intentionat - stralucirea se intinde pe grila si pe
# bara mult mai departe decat pare la prima vedere.
LIGHT_ZONE = (858, 516, 1448, 790)
LIGHT_FEATHER = 18

# Peste atata diferenta R-B un pixel e considerat "cald", adica lumina de far.
WARM_FLOOR = 8
WARM_KNEE = 62
# Cat de mult se stinge un pixel complet cald. 0.78 lasa inelele vizibile ca
# plastic gri - un far stins tot se vede, doar ca nu lumineaza.
WARM_DARKEN = 0.80

# Miezul unui bec e aproape alb, deci diferenta R-B de acolo e mica si testul de
# caldura singur abia il atinge - raman exact varfurile cele mai luminoase, adica
# fix ce trebuia stins. Peste pragul asta de luminozitate, un pixel cat de cat
# cald e tratat ca lumina, nu ca reflexie. Cele doua conditii impreuna: reflexiile
# albe de pe capota sunt luminoase dar NEUTRE, deci scapa neatinse.
HOT_FLOOR = 132
HOT_WARM_MIN = 4

# Peste ce luminozitate incepe sa se ridice un pixel de janta. Sub prag raman
# umbrele dintre spite, altfel janta devine o pata gri uniforma.
RIM_FLOOR = 14
RIM_KNEE = 58
RIM_LIFT = 62
RIM_NEUTRALIZE = 0.45
# Peste atata diferenta intre canale, pixelul e considerat colorat si e lasat in
# pace: etrierele galbene si sigla din butuc sunt in interiorul jantei, iar
# neutralizarea care face aluminiul sa para aluminiu le-ar spala pe amandoua.
RIM_CHROMA_GUARD = 34


def elliptical_mask(size, shapes, feather):
    """Masca alb-pe-negru pentru o lista de elipse, cu margine difuza."""
    m = Image.new('L', size, 0)
    d = ImageDraw.Draw(m)
    for cx, cy, rx, ry in shapes:
        d.ellipse([cx - rx, cy - ry, cx + rx, cy + ry], fill=255)
    return m.filter(ImageFilter.GaussianBlur(feather))


def lift_rims(im):
    """
    Hyper gray: ridica tonurile medii din interiorul jantei si le trage spre
    neutru. Cresterea e proportionala cu cat de luminos e deja pixelul, deci
    spitele se deschid, iar golurile dintre ele raman adanci - fara asta janta
    s-ar aplatiza intr-un disc gri.
    """
    px = im.load()
    mask = elliptical_mask(im.size, RIMS, feather=3).load()

    for cx, cy, rx, ry in RIMS:
        for y in range(int(cy - ry - 4), int(cy + ry + 5)):
            if y < 0 or y >= im.size[1]:
                continue
            for x in range(int(cx - rx - 4), int(cx + rx + 5)):
                if x < 0 or x >= im.size[0]:
                    continue
                w = mask[x, y] / 255.0
                if w <= 0.01:
                    continue
                r, g, b = px[x, y]
                lum = 0.299 * r + 0.587 * g + 0.114 * b
                if lum <= RIM_FLOOR:
                    continue
                chroma = max(r, g, b) - min(r, g, b)
                if chroma > RIM_CHROMA_GUARD:
                    continue
                t = min(1.0, (lum - RIM_FLOOR) / RIM_KNEE)
                lift = RIM_LIFT * t * w
                # spre neutru: hyper gray n-are dominanta de culoare
                nr = r + (lum - r) * RIM_NEUTRALIZE * w
                ng = g + (lum - g) * RIM_NEUTRALIZE * w
                nb = b + (lum - b) * RIM_NEUTRALIZE * w
                px[x, y] = (
                    min(255, int(nr + lift)),
                    min(255, int(ng + lift)),
                    min(255, int(nb + lift * 1.02)),
                )
    return im


def extinguish_lights(im):
    """
    Stinge farurile.

    Nu se acopera cu o forma neagra: o pata peste far ar sterge si reflectorul,
    si lentila, si s-ar vedea imediat ca e un plasture. Se lucreaza pe CALDURA
    fiecarui pixel - cat de mult bate spre portocaliu fata de albastru.

    Lumina de angel eyes e singurul lucru cald din fata masinii; tabla, grila si
    bara sunt neutre sau reci. Deci scazand saturatia calda si luminozitatea
    proportional cu cat de cald era pixelul, inelele redevin plastic gri, iar
    stralucirea aruncata pe grila dispare de la sine - fara sa fie nevoie sa
    spun unde anume cadea. Asta rezolva si partea grea: lumina reflectata nu are
    un contur pe care sa-l pot decupa.

    Zona e limitata la fata masinii, altfel s-ar stinge si stopurile din spate.
    """
    x0, y0, x1, y1 = LIGHT_ZONE

    zone = Image.new('L', im.size, 0)
    ImageDraw.Draw(zone).rectangle([x0, y0, x1, y1], fill=255)
    zone = zone.filter(ImageFilter.GaussianBlur(LIGHT_FEATHER))

    px = im.load()
    zp = zone.load()
    for y in range(max(0, y0 - LIGHT_FEATHER), min(im.size[1], y1 + LIGHT_FEATHER)):
        for x in range(max(0, x0 - LIGHT_FEATHER), min(im.size[0], x1 + LIGHT_FEATHER)):
            w = zp[x, y] / 255.0
            if w <= 0.01:
                continue
            r, g, b = px[x, y]
            warmth = r - b
            lum0 = 0.299 * r + 0.587 * g + 0.114 * b
            hot = lum0 >= HOT_FLOOR and warmth >= HOT_WARM_MIN
            if warmth <= WARM_FLOOR and not hot:
                continue
            t = min(1.0, (warmth - WARM_FLOOR) / WARM_KNEE)
            if hot:
                t = max(t, min(1.0, (lum0 - HOT_FLOOR) / 70.0))
            t *= w
            lum = 0.299 * r + 0.587 * g + 0.114 * b
            # spre gri neutru, apoi in jos
            target = lum * (1.0 - WARM_DARKEN * t)
            px[x, y] = (
                max(0, min(255, int(r + (target - r) * t))),
                max(0, min(255, int(g + (target - g) * t))),
                max(0, min(255, int(b + (target - b) * t))),
            )
    return im


def cut_out(im, thresh=5, feather=2.0):
    """
    Decupeaza masina de pe fundalul negru.

    NU prin prag de luminozitate. Fundalul e negru absolut (lum 0), dar si
    caroseria are zone la lum 1-3 - un prag ar gauri masina exact in umbrele
    care ii dau volumul.

    In schimb se umple din MARGINI: fundalul e singura zona intunecata care
    atinge chenarul imaginii si e continua pana acolo. Umbrele dinauntrul masinii
    sunt inchise de pixeli mai luminosi, deci umplerea nu ajunge la ele si raman
    opace. Ce ramane neumplut e masina.

    Alfa se estompeaza la final, altfel silueta ar avea marginea in trepte peste
    gradientul dalei.
    """
    w, h = im.size
    px = im.convert('L').load()
    bg = bytearray(w * h)          # 1 = fundal
    q = deque()

    def push(x, y):
        i = y * w + x
        if not bg[i] and px[x, y] <= thresh:
            bg[i] = 1
            q.append((x, y))

    for x in range(w):
        push(x, 0)
        push(x, h - 1)
    for y in range(h):
        push(0, y)
        push(w - 1, y)

    while q:
        x, y = q.popleft()
        if x > 0:
            push(x - 1, y)
        if x < w - 1:
            push(x + 1, y)
        if y > 0:
            push(x, y - 1)
        if y < h - 1:
            push(x, y + 1)

    alpha = Image.frombytes('L', (w, h), bytes(255 if not v else 0 for v in bg))
    alpha = alpha.filter(ImageFilter.GaussianBlur(feather))
    out = im.convert('RGBA')
    out.putalpha(alpha)
    return out


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else 'car_edited.png'
    im = Image.open(SRC).convert('RGB')
    im = extinguish_lights(im)
    im = lift_rims(im)
    if '--cutout' in sys.argv:
        im = cut_out(im)
    im.save(out)
    print('scris %s  (%.0f KB)' % (out, os.path.getsize(out) / 1024))


if __name__ == '__main__':
    main()
