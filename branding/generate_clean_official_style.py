"""Builds the Nuvio Clean TV icons in the official Nuvio layout.

Takes the Nuvio wordmark already in the app (drawable-nodpi/app_logo_wordmark.png),
recolours the play mark from blue/purple to mint, adds the word "clean" and writes
the launcher icon, TV banner and splash mark.

Usage:
  python3 branding/generate_clean_official_style.py app/src/main/res [preview.png]
"""
import colorsys
import os
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFont

res = sys.argv[1]
preview_path = sys.argv[2] if len(sys.argv) > 2 else None
MINT = (120, 236, 200)
MARK_RIGHT = 313  # the play mark ends at this column of the wordmark image


def recolour(img: Image.Image, lo: int = 140, hi: int = 205) -> Image.Image:
    """Map the mark's blue-to-purple hues onto green-to-sky; keeps white and dark parts."""
    a = np.asarray(img.convert("RGBA")).astype(float) / 255
    rgb, alpha = a[..., :3], a[..., 3]
    mx, mn = rgb.max(2), rgb.min(2)
    sat = np.where(mx > 0, (mx - mn) / np.maximum(mx, 1e-6), 0)
    h, s, v = np.vectorize(colorsys.rgb_to_hsv, otypes=[float] * 3)(rgb[..., 0], rgb[..., 1], rgb[..., 2])
    t = np.clip((h * 360 - 190) / 110, 0, 1)
    target = (lo + t * (hi - lo)) / 360
    weight = np.clip((sat - 0.04) / 0.12, 0, 1)
    h = (h + ((target - h + 0.5) % 1 - 0.5) * weight) % 1
    r, g, b = np.vectorize(colorsys.hsv_to_rgb, otypes=[float] * 3)(h, s, v)
    return Image.fromarray((np.dstack([r, g, b, alpha]) * 255).round().astype(np.uint8), "RGBA")


def background(w: int, h: int, glow_x: float = 0.5) -> Image.Image:
    """Dark navy with a soft teal glow from below."""
    yy = np.linspace(0, 1, h)[:, None]
    xx = np.linspace(0, 1, w)[None, :]
    base = np.array([18, 26, 40]) + np.array([5, 40, 45]) * (yy[..., None] ** 1.5)
    glow = np.exp(-(((xx - glow_x) / 0.45) ** 2 + ((yy - 1.1) / 0.5) ** 2))[..., None] * np.array([15, 80, 75])
    return Image.fromarray(np.clip(base + glow, 0, 255).astype(np.uint8), "RGB").convert("RGBA")


def font(size: int) -> ImageFont.FreeTypeFont:
    f = ImageFont.truetype(os.path.join(res, "font", "dm_sans_variable.ttf"), size)
    try:
        f.set_variation_by_axes([14, 800])  # optical size, weight
    except Exception:
        pass
    return f


wordmark = recolour(Image.open(os.path.join(res, "drawable-nodpi", "app_logo_wordmark.png")))
mark = wordmark.crop((0, 0, MARK_RIGHT, wordmark.height))
mark = mark.crop(mark.getbbox())

# TV banner: "nuvio" wordmark with "clean" underneath
W, H = 1280, 720
banner = background(W, H)
wm = wordmark.resize((760, round(wordmark.height * 760 / wordmark.width)), Image.LANCZOS)
f = font(120)
draw = ImageDraw.Draw(banner)
text_w = draw.textlength("clean", font=f)
top = (H - (wm.height + 120)) // 2
banner.alpha_composite(wm, ((W - wm.width) // 2, top))
# Centre "clean" under the letters, not under the whole logo
letters_centre = (W - wm.width) // 2 + wm.width * (MARK_RIGHT + wordmark.width) / 2 / wordmark.width
draw.text((letters_centre - text_w / 2, top + wm.height - 10), "clean", font=f, fill=(*MINT, 255))
banner = banner.convert("RGB")
banner.resize((320, 180), Image.LANCZOS).save(os.path.join(res, "mipmap-xhdpi", "banner.png"), optimize=True)
banner.save(os.path.join(res, "drawable", "tv_banner.png"), optimize=True)
banner.resize((1920, 1080), Image.LANCZOS).save(os.path.join(res, "drawable-nodpi", "tv_banner.png"), optimize=True)

# Launcher icon: the play mark on the navy background, with "clean" below it
side = 1024
icon = background(side, side, glow_x=0.5)
m = mark.resize((round(mark.width * 560 / mark.height), 560), Image.LANCZOS)
icon.alpha_composite(m, ((side - m.width) // 2 + 20, 130))
fi = font(190)
di = ImageDraw.Draw(icon)
di.text(((side - di.textlength("clean", font=fi)) / 2, 700), "clean", font=fi, fill=(*MINT, 255))
icon = icon.convert("RGB")
for name, size in {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}.items():
    icon.resize((size, size), Image.LANCZOS).save(os.path.join(res, f"mipmap-{name}", "ic_launcher.png"), optimize=True)
icon.resize((432, 432), Image.LANCZOS).save(os.path.join(res, "drawable", "ic_launcher.png"), optimize=True)

# Android 12+ splash icon: the mark alone, inside the safe circle, on #0D0D0D
splash = Image.new("RGBA", (1080, 1080), (13, 13, 13, 255))
s = mark.resize((round(mark.width * 470 / mark.height), 470), Image.LANCZOS)
splash.alpha_composite(s, ((1080 - s.width) // 2 + 15, (1080 - s.height) // 2))
splash.convert("RGB").save(os.path.join(res, "drawable", "app_logo_mark.png"), optimize=True)

if preview_path:
    prev = Image.new("RGB", (1280 + 20 + 360, 720), (40, 40, 40))
    prev.paste(banner, (0, 0))
    prev.paste(icon.resize((360, 360)), (1300, 0))
    prev.paste(splash.convert("RGB").resize((360, 360)), (1300, 360))
    prev.save(preview_path)
