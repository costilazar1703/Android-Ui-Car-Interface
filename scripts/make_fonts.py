# -*- coding: utf-8 -*-
"""
Genereaza cele patru variante statice de Inter din res/font, din fontul variabil
oficial (github.com/google/fonts, ofl/inter, licenta OFL - vezi
app/src/main/assets/licenses/Inter-OFL.txt).

  python scripts/make_fonts.py <Inter[opsz,wght].ttf> app/src/main/res/font

De ce static si nu fontul variabil direct: pe API 27 axele unui font variabil nu
se pot alege din XML, doar din cod, pe fiecare TextView. Patru fisiere statice se
folosesc normal, cu @font/..., ca orice font.

Subsetarea pastreaza doar caracterele folosite - inclusiv Ș/ș/Ț/ț cu virgula
dedesubt (U+0218-021B), care NU sunt in blocul Latin Extended-A si s-ar fi
pierdut cu o subsetare standard. Fontul complet are 860 KB; cele patru ies 54 KB
fiecare.
"""
import os, sys
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont
from fontTools import subset

src, out = sys.argv[1], sys.argv[2]
# nume -> (grosime, dimensiune optica)
#   opsz 14 = desenul pentru text mic, cu spatiere mai larga
#   opsz 32 = desenul pentru cifre mari (temperatura, ceasul), mai strans si mai fin
VARIANTS = {
    'inter_regular':       (400, 14),
    'inter_medium':        (500, 14),
    'inter_semibold':      (600, 14),
    'inter_display_light': (300, 32),
}
UNICODES = (
    list(range(0x20, 0x7F)) +        # ASCII
    list(range(0xA0, 0x100)) +       # Latin-1: ° · « » etc.
    list(range(0x100, 0x180)) +      # Latin Ext-A: ă Ă î Î
    list(range(0x218, 0x21C)) +      # Ș ș Ț ț cu virgula dedesubt (NU sedila)
    [0x2013, 0x2014, 0x2018, 0x2019, 0x201A, 0x201C, 0x201D, 0x201E,
     0x2022, 0x2026, 0x2039, 0x203A, 0x2192, 0x2212]
)
for name, (wght, opsz) in VARIANTS.items():
    f = TTFont(src)
    f = instantiateVariableFont(f, {'wght': wght, 'opsz': opsz})
    opts = subset.Options()
    opts.layout_features = ['kern', 'liga', 'calt', 'tnum', 'case', 'ss01', 'cv11']
    opts.name_IDs = ['*']
    opts.notdef_outline = True
    sub = subset.Subsetter(opts)
    sub.populate(unicodes=UNICODES)
    sub.subset(f)
    path = os.path.join(out, name + '.ttf')
    f.save(path)
    print('%-22s wght %d opsz %d  %4.0f KB' % (name, wght, opsz, os.path.getsize(path) / 1024))
