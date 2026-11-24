"""Adds "clean" to the in-app Nuvio wordmarks (profile screen, sidebar and so on).

Reads the original wordmarks in branding/wordmarks/ and writes the app's
drawable-nodpi/app_logo_wordmark*.png. The default wordmark is recoloured mint
like the TV banner; theme wordmarks keep their colours and get "clean" in the
theme's own accent colour.

Usage: python3 branding/generate_clean_wordmarks.py app/src/main/res
"""
import importlib.util
import os
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
res = sys.argv[1]
MINT = (120, 236, 200)

# Reuse the mint recolouring from the banner script without running it
spec = importlib.util.spec_from_file_location("banner", os.path.join(HERE, "generate_clean_official_style.py"))
src = open(spec.origin).read()
recolour_src = src[src.index("def recolour"):src.index("def background")]
namespace = {"np": np, "Image": Image, "colorsys": __import__("colorsys")}
exec(recolour_src, namespace)
recolour = namespace["recolour"]


def accent_of(img: Image.Image) -> tuple:
    """Average colour of the strongly coloured pixels of the play mark."""
    a = np.asarray(img.convert("RGBA")).astype(float)
    rgb, alpha = a[..., :3], a[..., 3]
    sat = rgb.max(2) - rgb.min(2)
    mask = (alpha > 200) & (sat > 60)
    if mask.sum() < 50:  # a grey theme: use light grey
        return (220, 220, 225)
    c = rgb[mask].mean(0)
    c = c + (255 - c) * 0.35  # lighten a little so it reads like the letters
    return tuple(int(v) for v in c)


font = ImageFont.truetype(os.path.join(res, "font", "dm_sans_variable.ttf"), 210)
try:
    font.set_variation_by_axes([14, 800])
except Exception:
    pass

for name in sorted(os.listdir(os.path.join(HERE, "wordmarks"))):
    wm = Image.open(os.path.join(HERE, "wordmarks", name)).convert("RGBA")
    if name == "app_logo_wordmark.png":
        wm, colour = recolour(wm), MINT
    else:
        colour = accent_of(wm)
    box = wm.getbbox()
    gap = 50
    probe = ImageDraw.Draw(wm)
    tb = probe.textbbox((0, 0), "clean", font=font)
    out = Image.new("RGBA", (box[2] + gap + (tb[2] - tb[0]) + 10, wm.height), (0, 0, 0, 0))
    out.alpha_composite(wm, (0, 0))
    d = ImageDraw.Draw(out)
    # Sit "clean" on the same baseline as "nuvio" (bottom of the letters)
    a = np.asarray(wm)[..., 3]
    letters = a[:, 330:]
    rows = np.where(letters.max(1) > 10)[0]
    baseline = rows[-1]
    d.text((box[2] + gap - tb[0], baseline - tb[3]), "clean", font=font, fill=(*colour, 255))
    out.save(os.path.join(res, "drawable-nodpi", name), optimize=True)
    print(name, out.size, colour)
