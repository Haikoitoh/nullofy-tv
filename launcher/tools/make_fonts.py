# Geist kept variable but limited to weights 400-500 (focused text eases to Medium);
# Geist Mono as a static 400. Both cut to Latin + the few symbols the UI draws.
# Run: python launcher/tools/make_fonts.py   (needs fonttools; put Geist-VF.ttf and GeistMono-VF.ttf from
# https://github.com/vercel/geist-font next to this file first)
import os
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer
from fontTools import subset

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, '..', 'assets', 'fonts')
UNI = list(range(0x20, 0x7F)) + list(range(0xA0, 0x180)) + [0x2013, 0x2014, 0x2019, 0x201C, 0x201D, 0x2022, 0x2026, 0x203A, 0x2192, 0x25CF]
for src, dst, wght in [('Geist-VF.ttf', 'Geist.ttf', (400, 500)), ('GeistMono-VF.ttf', 'GeistMono.ttf', 400)]:
    f = instancer.instantiateVariableFont(TTFont(os.path.join(HERE, src)), {'wght': wght})
    opts = subset.Options()
    opts.layout_features = ['kern', 'liga']
    opts.name_IDs = [0, 1, 2, 3, 4, 6]
    opts.hinting = False
    s = subset.Subsetter(opts)
    s.populate(unicodes=UNI)
    s.subset(f)
    f.save(os.path.join(OUT, dst))
    print(dst, os.path.getsize(os.path.join(OUT, dst)))
