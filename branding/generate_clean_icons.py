"""Builds the NuvioTv-Clean TV icons (launcher, TV banner, splash mark).

It reuses the Reshaped prism logo (branding/nuvio-rs-logo.png), recolours it
from blue/purple to a fresher mint-to-sky palette, adds a small sparkle and
writes "Nuvio Clean" on the TV banner.

Usage (CLEAN_THEME=mint, ocean, gold or rose; mint is the default):
  python3 branding/generate_clean_icons.py branding/nuvio-rs-logo.png app/src/main/res [preview.png]
"""
import colorsys
import os
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

src_path, res = sys.argv[1], sys.argv[2]
preview_path = sys.argv[3] if len(sys.argv) > 3 else None

BG = np.array([25, 30, 37], dtype=float)  # background colour of the source artwork
CENTER = (652, 615)                        # centre of the logo in the source artwork

# Colour themes: the logo's blue-to-purple hues (190-290 degrees) are mapped onto
# the theme's hue range; "accent" colours the word "Clean"; "glow" tints the banner.
THEMES = {
    "mint":  dict(hues=(140, 205), accent=(120, 236, 200), glow=(20, 95, 85),  floor=(10, 60, 62)),
    "ocean": dict(hues=(185, 228), accent=(110, 205, 255), glow=(20, 70, 120), floor=(10, 40, 80)),
    "gold":  dict(hues=(28, 50),   accent=(255, 205, 110), glow=(110, 70, 15), floor=(70, 45, 10)),
    "rose":  dict(hues=(318, 352), accent=(255, 150, 190), glow=(115, 30, 70), floor=(70, 20, 45)),
}
THEME = THEMES[os.environ.get("CLEAN_THEME", "mint")]


def recolour(img: Image.Image) -> Image.Image:
    """Rotate the hue of saturated pixels; dark background and white stay as they are."""
    a = np.asarray(img.convert("RGB")).astype(float) / 255
    r, g, b = a[..., 0], a[..., 1], a[..., 2]
    mx, mn = a.max(axis=2), a.min(axis=2)
    sat = np.where(mx > 0, (mx - mn) / np.maximum(mx, 1e-6), 0)
    hsv = np.vectorize(colorsys.rgb_to_hsv, otypes=[float, float, float])
    h, s, v = hsv(r, g, b)
    weight = np.clip((sat - 0.04) / 0.12, 0, 1)  # only clearly coloured pixels move
    lo, hi = THEME["hues"]
    t = np.clip((h * 360 - 190) / 100, 0, 1)
    target = ((lo + t * ((hi - lo) % 360)) % 360) / 360
    diff = (target - h + 0.5) % 1.0 - 0.5          # shortest way round the colour wheel
    h = (h + diff * weight) % 1.0
    back = np.vectorize(colorsys.hsv_to_rgb, otypes=[float, float, float])
    out = np.dstack(back(h, s, v))
    return Image.fromarray((out * 255).round().astype(np.uint8), "RGB")


def sparkle(size: int) -> Image.Image:
    """A white four-point star with a soft glow, drawn on a transparent square."""
    big = size * 4
    star = Image.new("L", (big, big), 0)
    d = ImageDraw.Draw(star)
    c, long, short = big / 2, big * 0.48, big * 0.09
    d.polygon([(c, c - long), (c + short, c - short), (c + long, c), (c + short, c + short),
               (c, c + long), (c - short, c + short), (c - long, c), (c - short, c - short)], fill=255)
    star = star.resize((size, size), Image.LANCZOS)
    glow = star.filter(ImageFilter.GaussianBlur(size * 0.08))
    alpha = np.maximum(np.asarray(star), np.asarray(glow) * 0.8).astype(np.uint8)
    out = Image.new("RGBA", (size, size), (255, 255, 255, 0))
    out.putalpha(Image.fromarray(alpha))
    return out


src = recolour(Image.open(src_path))
# Sparkle at the upper right of the prism
spark = sparkle(230)
src_rgba = src.convert("RGBA")
src_rgba.alpha_composite(spark, (CENTER[0] + 250 - 115, CENTER[1] - 330 - 115))
small = sparkle(110)
src_rgba.alpha_composite(small, (CENTER[0] + 380 - 55, CENTER[1] - 175 - 55))
src = src_rgba.convert("RGB")

# Transparent version of the logo (same un-blending as the Reshaped script)
px = np.asarray(src).astype(float)
up = np.where(px > BG, (px - BG) / (255 - BG), (BG - px) / BG)
raw = up.max(axis=2)
alpha = np.clip((raw - 0.03) / 0.97, 0, 1)
rgb = np.clip((px - BG) / np.maximum(raw, 1e-6)[..., None] + BG, 0, 255)
# Fade out the faint glow far from the mark, so no square edge shows on the banner
yy0, xx0 = np.mgrid[0:px.shape[0], 0:px.shape[1]]
dist = np.hypot(xx0 - CENTER[0], yy0 - CENTER[1])
alpha *= np.clip((620 - dist) / 120, 0, 1)
logo = Image.fromarray(np.dstack([rgb, alpha * 255]).astype(np.uint8), "RGBA")


def centered(side: int) -> Image.Image:
    canvas = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    canvas.paste(logo, (side // 2 - CENTER[0], side // 2 - CENTER[1]), logo)
    return canvas


# Launcher icon (square, opaque)
side = 1070
square = src.crop((CENTER[0] - side // 2, CENTER[1] - side // 2, CENTER[0] + side // 2, CENTER[1] + side // 2))
for name, size in {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}.items():
    square.resize((size, size), Image.LANCZOS).save(os.path.join(res, f"mipmap-{name}", "ic_launcher.png"), optimize=True)
square.resize((432, 432), Image.LANCZOS).save(os.path.join(res, "drawable", "ic_launcher.png"), optimize=True)

# Android 12+ splash icon on the #0D0D0D splash background
mark = Image.new("RGBA", (1080, 1080), (13, 13, 13, 255))
mark = Image.alpha_composite(mark, centered(1560).resize((1080, 1080), Image.LANCZOS)).convert("RGB")
mark.save(os.path.join(res, "drawable", "app_logo_mark.png"), optimize=True)

# TV banner, drawn at 1280x720 and scaled
W, H = 1280, 720
yy = np.linspace(0, 1, H)[:, None]
xx = np.linspace(0, 1, W)[None, :]
base = np.array([16, 24, 30]) * (1 - yy[..., None] * 0.25) + np.array(THEME['floor']) * (yy[..., None] ** 2) * 0.55
glow = np.exp(-(((xx - 0.3) / 0.35) ** 2 + ((yy - 1.05) / 0.45) ** 2))[..., None] * np.array(THEME['glow'])
banner = Image.fromarray(np.clip(base + glow, 0, 255).astype(np.uint8), "RGB").convert("RGBA")

font = ImageFont.truetype(os.path.join(res, "font", "dm_sans_variable.ttf"), 132)
try:
    font.set_variation_by_axes([14, 700])  # optical size, weight
except Exception:
    pass
draw = ImageDraw.Draw(banner)
word1, word2 = "Nuvio ", "Clean"
w1 = draw.textlength(word1, font=font)
w2 = draw.textlength(word2, font=font)
logo_size, gap = 400, -36
group = logo_size + gap + w1 + w2
left = int((W - group) // 2)
banner.alpha_composite(centered(1400).resize((logo_size, logo_size), Image.LANCZOS), (left, (H - logo_size) // 2))
bbox = draw.textbbox((0, 0), word1 + word2, font=font)
tx = left + logo_size + gap - bbox[0]
ty = (H - (bbox[3] - bbox[1])) // 2 - bbox[1]
draw.text((tx, ty), word1, font=font, fill=(255, 255, 255, 255))
draw.text((tx + w1, ty), word2, font=font, fill=(*THEME['accent'], 255))  # "Clean" in the accent colour
banner = banner.convert("RGB")
banner.resize((320, 180), Image.LANCZOS).save(os.path.join(res, "mipmap-xhdpi", "banner.png"), optimize=True)
banner.save(os.path.join(res, "drawable", "tv_banner.png"), optimize=True)
banner.resize((1920, 1080), Image.LANCZOS).save(os.path.join(res, "drawable-nodpi", "tv_banner.png"), optimize=True)

if preview_path:
    prev = Image.new("RGB", (1280 + 20 + 360, 720), (40, 40, 40))
    prev.paste(banner, (0, 0))
    prev.paste(square.resize((360, 360)), (1300, 0))
    prev.paste(mark.resize((360, 360)), (1300, 360))
    prev.save(preview_path)
