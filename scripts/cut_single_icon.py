# -*- coding: utf-8 -*-
"""
Taie o SINGURA iconita generata separat si o pune peste una existenta.

Refoloseste `extract` din cut_icon_sheet: aceeasi inundare dinspre margini si
acelasi halou moale. Daca decuparea s-ar face altfel aici, iconita noua ar avea
marginea taiata cu alt cutit decat restul si s-ar vedea in rand.

  python scripts/cut_single_icon.py <poza> <nume_iconita>
"""
import os
import sys

from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from cut_icon_sheet import extract, square, OUT, SIZE


def main():
    src, name = sys.argv[1], sys.argv[2]
    cut = extract(Image.open(src).convert('RGB'))

    box = cut.getbbox()
    if box is None:
        raise SystemExit('nimic de decupat - poza e goala dupa scoaterea fundalului')
    # Patrat cu aer egal, exact ca la plansa: iconitele trebuie sa aiba aceeasi
    # greutate vizuala in rand, iar aia vine din cat spatiu liber au in jur, nu
    # doar din cat de mare e desenul.
    canvas = square(cut.crop(box))

    path = os.path.join(OUT, name + '.png')
    canvas.save(path, optimize=True)
    print('%s  %dx%d  %.0f KB' % (name, SIZE, SIZE, os.path.getsize(path) / 1024))


if __name__ == '__main__':
    main()
