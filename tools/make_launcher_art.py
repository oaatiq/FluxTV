"""
Builds FluxTV's launcher artwork from the logo.

    python3 tools/make_launcher_art.py art/fluxtv-logo.webp app/src/main/res <preview dir>

Needs Python 3 with Pillow and NumPy. The positions of the symbol and the wordmark below are those
of art/fluxtv-logo.webp; another logo image needs them measured again.

The logo's TV symbol and "FluxTV" wordmark are lifted off its textured background and set on a
clean navy gradient, without the tagline, which no launcher shows large enough to read:

  * TV banner (android:banner, 16:9): symbol above a large wordmark. Android TV, Google TV and Fire TV
    show this one in their app rows and favorites, often small, so the name is as big as it fits.
  * Launcher icon (legacy square PNGs) and adaptive icon foreground: the symbol alone; a wordmark
    cannot be read at icon size.
"""
import os
import sys

import numpy as np
from PIL import Image, ImageDraw

src_path, res_dir, preview_dir = sys.argv[1], sys.argv[2], sys.argv[3]
src = Image.open(src_path).convert("RGBA")
rgb = np.asarray(src)[..., :3].astype(np.float32)
lum = 0.2126 * rgb[..., 0] + 0.7152 * rgb[..., 1] + 0.0722 * rgb[..., 2]

# The image is very slightly transparent all over (alpha 239-255 on the logo, an export artefact),
# so its colours are used as they are and the new artwork is fully opaque.
a_src = np.asarray(src)[..., 3]
assert a_src[lum > 60].min() >= 240, "the logo's bright parts are more transparent than expected"

# Where the parts are in the supplied image (measured: inspect.py), padded by a margin of background.
PAD = 14
SYMBOL = (312 - PAD, 247 - PAD, 942 + PAD + 1, 715 + PAD + 1)   # TV frame, screen, play button, stand
WORDMARK = (220 - PAD, 769 - PAD, 1038 + PAD + 1, 951 + PAD + 1)  # "FluxTV"


def symbol_layer():
    x0, y0, x1, y1 = SYMBOL
    L = lum[y0:y1, x0:x1]
    # The screen inside the frame is as dark as the background around it, so it is found by shape:
    # everything the background cannot reach from outside, through dark pixels, belongs to the symbol.
    # copy(): an image made from an array can share it read-only, and the fill then changes nothing.
    dark = Image.fromarray(np.where(L < 45, 0, 255).astype(np.uint8)).copy()
    ImageDraw.floodfill(dark, (0, 0), 128, thresh=0)
    assert (np.asarray(dark) == 128).any(), "flood fill did not run"
    outside = np.asarray(dark) == 128
    alpha = np.where(outside, np.clip((L - 28) / (45 - 28), 0, 1), 1.0)
    return rgba(rgb[y0:y1, x0:x1], alpha)


def wordmark_layer():
    x0, y0, x1, y1 = WORDMARK
    L = lum[y0:y1, x0:x1]
    alpha = np.clip((L - 17) / (30 - 17), 0, 1)
    return rgba(rgb[y0:y1, x0:x1], alpha)


def rgba(colors, alpha):
    a = np.dstack([colors, alpha * 255]).round().clip(0, 255).astype(np.uint8)
    img = Image.fromarray(a, "RGBA")
    return img.crop(img.getbbox())  # trim to what is visible


def scaled(layer, width=None, height=None):
    w, h = layer.size
    if width is None:
        width = round(w * height / h)
    if height is None:
        height = round(h * width / w)
    # Resized premultiplied, so transparent pixels do not bleed their colour into the edges.
    return layer.convert("RGBa").resize((width, height), Image.LANCZOS).convert("RGBA")


# Colours of the supplied logo's own background: lighter behind the symbol, darker further out.
STOPS = [(0.0, (3, 28, 80)), (0.45, (2, 19, 57)), (0.75, (2, 13, 38)), (1.0, (1, 9, 27))]


def gradient(w, h, cx, cy, rx, ry):
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    r = np.sqrt(((xx + 0.5) / w - cx) ** 2 / rx ** 2 + ((yy + 0.5) / h - cy) ** 2 / ry ** 2)
    out = np.zeros((h, w, 3), np.float32)
    for c in range(3):
        out[..., c] = np.interp(r, [s for s, _ in STOPS], [col[c] for _, col in STOPS])
    return Image.fromarray(out.round().astype(np.uint8), "RGB").convert("RGBA")


def banner(w, h):
    """Symbol above the wordmark, both as large as a 16:9 tile allows with a safe margin."""
    symbol, word = symbol_layer(), wordmark_layer()
    sym_h, word_h, gap = 0.46 * h, 0.27 * h, 0.04 * h
    word_w = word.size[0] * word_h / word.size[1]
    if word_w > 0.80 * w:  # keep clear of the tile's sides
        word_h *= 0.80 * w / word_w
    top = (h - (sym_h + gap + word_h)) / 2
    img = gradient(w, h, 0.5, top / h + 0.46 * 0.5, 0.75, 0.95)
    s = scaled(symbol, height=round(sym_h))
    img.alpha_composite(s, (round((w - s.size[0]) / 2), round(top)))
    t = scaled(word, height=round(word_h))
    img.alpha_composite(t, (round((w - t.size[0]) / 2), round(top + sym_h + gap)))
    return img.convert("RGB")


def legacy_icon(n):
    """Rounded square with the symbol, for launchers that do not use adaptive icons."""
    bg = gradient(n, n, 0.5, 0.47, 0.72, 0.72)
    s = scaled(symbol_layer(), width=round(0.78 * n))
    bg.alpha_composite(s, (round((n - s.size[0]) / 2), round((n - s.size[1]) / 2)))
    mask = Image.new("L", (n * 4, n * 4), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, n * 4 - 1, n * 4 - 1), radius=round(0.19 * n * 4), fill=255)
    bg.putalpha(mask.resize((n, n), Image.LANCZOS))
    return bg


def adaptive_foreground(n):
    """The symbol on a transparent 108 dp canvas, inside the circle every launcher mask keeps (66 dp)."""
    symbol = symbol_layer()
    a = np.asarray(symbol)[..., 3] > 25
    ys, xs = np.nonzero(a)
    cx, cy = (xs.min() + xs.max()) / 2, (ys.min() + ys.max()) / 2
    reach = np.sqrt((xs - cx) ** 2 + (ys - cy) ** 2).max()  # farthest visible pixel from the centre
    scale = (31.5 / 108 * n) / reach                         # 31.5 dp radius: a little inside 33 dp
    s = scaled(symbol, width=round(symbol.size[0] * scale))
    img = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    img.alpha_composite(s, (round(n / 2 - cx * scale), round(n / 2 - cy * scale)))
    return img


def adaptive_background(n):
    """The adaptive icon's back layer: the same navy gradient as the square icon."""
    return gradient(n, n, 0.5, 0.47, 0.72, 0.72).convert("RGB")


def save(img, *path):
    full = os.path.join(res_dir, *path)
    os.makedirs(os.path.dirname(full), exist_ok=True)
    img.save(full, optimize=True)
    print("wrote", os.path.relpath(full, res_dir), img.size)


for bucket, (w, h) in {"xhdpi": (320, 180), "xxhdpi": (480, 270), "xxxhdpi": (640, 360)}.items():
    save(banner(w, h), f"drawable-{bucket}", "app_banner.png")
for bucket, dp in {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}.items():
    save(legacy_icon(round(48 * dp)), f"mipmap-{bucket}", "ic_launcher.png")
    save(adaptive_foreground(round(108 * dp)), f"mipmap-{bucket}", "ic_launcher_foreground.png")
    save(adaptive_background(round(108 * dp)), f"mipmap-{bucket}", "ic_launcher_background.png")

# Preview: what launchers show, at the sizes a favorites row or an app list uses.
from PIL import ImageFont

os.makedirs(preview_dir, exist_ok=True)
font = ImageFont.load_default(size=18)
ink = (200, 205, 215)
sheet = Image.new("RGB", (1200, 820), (24, 26, 30))
draw = ImageDraw.Draw(sheet)


def tile(img, w, h, radius):
    t = img.resize((w, h), Image.LANCZOS)
    m = Image.new("L", (w, h), 0)
    ImageDraw.Draw(m).rounded_rectangle((0, 0, w - 1, h - 1), radius=radius, fill=255)
    return t, m


big = banner(640, 360)
draw.text((20, 14), "TV home screen tile (banner), full size", font=font, fill=ink)
sheet.paste(big, (20, 40))
y = 40
draw.text((700, 14), "the same tile, small (favorites rows)", font=font, fill=ink)
for tw in (320, 200, 160, 120):
    th = round(tw * 9 / 16)
    t, m = tile(big, tw, th, max(4, tw // 24))
    sheet.paste(t, (700, y), m)
    draw.text((700 + tw + 14, y + th // 2 - 10), f"{tw} x {th}", font=font, fill=ink)
    y += th + 18
draw.text((20, 560), "app icon (launchers and lists that use icons)", font=font, fill=ink)
ix = 20
for n in (192, 96, 64, 48, 32):
    icon = legacy_icon(n)
    sheet.paste(icon, (ix, 590 + (192 - n)), icon)
    draw.text((ix, 790), f"{n}", font=font, fill=ink)
    ix += n + 28
circle_bg = adaptive_background(432).convert("RGBA")
circle_bg.alpha_composite(adaptive_foreground(432))
cm = Image.new("L", (432, 432), 0)
ImageDraw.Draw(cm).ellipse((54, 54, 377, 377), fill=255)  # the 66 dp circle, the tightest mask
masked = circle_bg.crop((54, 54, 378, 378)).resize((192, 192), Image.LANCZOS)
cmask = cm.crop((54, 54, 378, 378)).resize((192, 192), Image.LANCZOS)
ix += 30
sheet.paste(masked, (ix, 590), cmask)
draw.text((ix, 790), "round mask", font=font, fill=ink)
sheet.save(os.path.join(preview_dir, "fluxtv-artwork-preview.png"))
print("preview written")
