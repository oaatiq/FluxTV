"""
Builds the artwork of FluxTV's Google Play edition, MotionTV (play/README.md): its TV home screen
banner and the graphics its Google Play listing needs.

    python tools/make_play_art.py [--font path/to/RobotoFlex.ttf]

Writes:
  app/src/play/res/drawable-{xhdpi,xxhdpi,xxxhdpi}/app_banner.png  the banner TV home screens show
  play/graphics/icon-512.png             listing icon, 512 x 512, a full square (Google Play rounds it)
  play/graphics/feature-graphic.png      listing feature graphic, 1024 x 500
  play/graphics/tv-banner-1280x720.png   listing banner for Android TV, 1280 x 720

The TV symbol is FluxTV's, lifted off art/fluxtv-logo.webp the way tools/make_launcher_art.py does it;
the launcher icon is that symbol alone, so both editions keep the same one. The wordmark is set in
Roboto Flex (SIL Open Font License), which Android Studio ships: "Motion" in white and "TV" in the
blue of FluxTV's wordmark. Needs Pillow only.
"""
import argparse
import os

from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_FONT = (r"C:/Program Files/Android/Android Studio/plugins/design-tools/resources/layoutlib/"
                r"data/fonts/RobotoFlex-Regular.ttf")

parser = argparse.ArgumentParser()
parser.add_argument("--font", default=DEFAULT_FONT, help="Roboto Flex (variable) .ttf")
args = parser.parse_args()

LOGO = Image.open(os.path.join(ROOT, "art", "fluxtv-logo.webp")).convert("RGBA")
# Where the TV symbol is in the logo, padded by a margin of background (as in make_launcher_art.py).
PAD = 14
SYMBOL = (312 - PAD, 247 - PAD, 942 + PAD + 1, 715 + PAD + 1)

# The navy of the logo's background, lighter behind the symbol and darker further out.
STOPS = [(0.0, (3, 28, 80)), (0.45, (2, 19, 57)), (0.75, (2, 13, 38)), (1.0, (1, 9, 27))]
WHITE = (246, 248, 252)
TV_TOP, TV_BOTTOM = (102, 196, 255), (22, 108, 246)   # the blue of FluxTV's "TV", top to bottom
TAGLINE = (176, 196, 226)


def symbol_layer():
    """The symbol with the dark background around it made transparent (its dark screen stays)."""
    crop = LOGO.crop(SYMBOL).convert("RGB")
    lum = crop.convert("L")
    # The screen is as dark as the background, so the outside is found by shape: what the background
    # reaches from the corner through dark pixels.
    dark = lum.point(lambda v: 0 if v < 45 else 255)
    ImageDraw.floodfill(dark, (0, 0), 128, thresh=0)
    outside = dark.point(lambda v: 255 if v == 128 else 0)
    ramp = lum.point(lambda v: max(0, min(255, round((v - 28) * 255 / 17))))
    alpha = Image.composite(ramp, Image.new("L", crop.size, 255), outside)
    layer = crop.convert("RGBA")
    layer.putalpha(alpha)
    return layer.crop(layer.getbbox())


def scaled(layer, height):
    w, h = layer.size
    # Premultiplied, so transparent pixels do not bleed their colour into the edges.
    return layer.convert("RGBa").resize((round(w * height / h), round(height)), Image.LANCZOS).convert("RGBA")


def lerp(a, b, t):
    return tuple(round(x + (y - x) * t) for x, y in zip(a, b))


def stop_colour(r):
    for (s0, c0), (s1, c1) in zip(STOPS, STOPS[1:]):
        if r <= s1:
            return lerp(c0, c1, max(0.0, (r - s0) / (s1 - s0)))
    return STOPS[-1][1]


def gradient(w, h, cx, cy, rx, ry):
    """An elliptical gradient of the stops around (cx, cy), worked out small and enlarged smoothly."""
    sw, sh = max(8, w // 4), max(8, h // 4)
    small = Image.new("RGB", (sw, sh))
    px = small.load()
    for y in range(sh):
        for x in range(sw):
            r = (((x + 0.5) / sw - cx) ** 2 / rx ** 2 + ((y + 0.5) / sh - cy) ** 2 / ry ** 2) ** 0.5
            px[x, y] = stop_colour(r)
    return small.resize((w, h), Image.BICUBIC).convert("RGBA")


def font(size, weight="ExtraBlack"):
    f = ImageFont.truetype(args.font, size)
    f.set_variation_by_name(weight)
    return f


def vertical_fill(size, top, bottom):
    w, h = size
    column = Image.new("RGB", (1, h))
    for y in range(h):
        column.putpixel((0, y), lerp(top, bottom, y / max(1, h - 1)))
    return column.resize((w, h))


def wordmark(cap_height):
    """MotionTV, its capitals [cap_height] tall, on a transparent layer trimmed to its ink."""
    size = round(cap_height / 0.711)          # Roboto Flex's capitals are 0.711 of its size
    f = font(size)
    motion_w = f.getlength("Motion")
    w = round(f.getlength("MotionTV")) + size
    h = size * 2
    motion = Image.new("L", (w, h), 0)
    tv = Image.new("L", (w, h), 0)
    ImageDraw.Draw(motion).text((size // 2, size // 2), "Motion", font=f, fill=255)
    ImageDraw.Draw(tv).text((size // 2 + motion_w, size // 2), "TV", font=f, fill=255)
    layer = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    layer.paste(Image.new("RGBA", (w, h), WHITE + (255,)), (0, 0), motion)
    box = tv.getbbox()
    blue = vertical_fill((w, box[3] - box[1]), TV_TOP, TV_BOTTOM).convert("RGBA")
    layer.paste(blue, (0, box[1]), tv.crop((0, box[1], w, box[3])))
    return layer.crop(layer.getbbox())


def text_layer(text, height, colour, weight="SemiBold"):
    f = font(round(height / 0.711), weight)
    w, h = round(f.getlength(text)) + height * 2, height * 3
    layer = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    ImageDraw.Draw(layer).text((height, height // 2), text, font=f, fill=colour + (255,))
    return layer.crop(layer.getbbox())


def banner(w, h):
    """Symbol above the wordmark, as large as a 16:9 tile allows with a safe margin (as FluxTV's)."""
    sym_h, word_h, gap = 0.46 * h, 0.22 * h, 0.07 * h
    word = wordmark(word_h)
    if word.size[0] > 0.84 * w:  # keep clear of the tile's sides
        word = wordmark(word_h * 0.84 * w / word.size[0])
    top = (h - (sym_h + gap + word.size[1])) / 2
    img = gradient(w, h, 0.5, top / h + 0.46 * 0.5, 0.75, 0.95)
    s = scaled(symbol_layer(), sym_h)
    img.alpha_composite(s, (round((w - s.size[0]) / 2), round(top)))
    img.alpha_composite(word, (round((w - word.size[0]) / 2), round(top + sym_h + gap)))
    return img.convert("RGB")


def store_icon(n):
    """The launcher icon's symbol on the navy square, edge to edge: Google Play rounds the corners."""
    img = gradient(n, n, 0.5, 0.47, 0.72, 0.72)
    s = symbol_layer()
    s = scaled(s, 0.78 * n * s.size[1] / s.size[0])
    img.alpha_composite(s, (round((n - s.size[0]) / 2), round((n - s.size[1]) / 2)))
    return img  # RGBA: Google Play asks for a 32-bit PNG


def feature_graphic(w=1024, h=500):
    """Symbol at the left, the wordmark and what MotionTV is at its right."""
    img = gradient(w, h, 0.28, 0.5, 0.62, 1.1)
    s = scaled(symbol_layer(), 0.50 * h)
    word = wordmark(0.17 * h)
    line = text_layer("Live TV from your own playlist", round(0.052 * h), TAGLINE)
    gap = 0.055 * w
    block_w = s.size[0] + gap + max(word.size[0], line.size[0])
    x = round((w - block_w) / 2)
    img.alpha_composite(s, (x, round((h - s.size[1]) / 2)))
    tx = round(x + s.size[0] + gap)
    text_h = word.size[1] + 0.08 * h + line.size[1]
    ty = (h - text_h) / 2
    img.alpha_composite(word, (tx, round(ty)))
    img.alpha_composite(line, (tx + 4, round(ty + word.size[1] + 0.08 * h)))
    return img.convert("RGB")  # no transparency in a feature graphic


def save(img, *path):
    full = os.path.join(ROOT, *path)
    os.makedirs(os.path.dirname(full), exist_ok=True)
    img.save(full, optimize=True)
    print("wrote", "/".join(path), img.size, img.mode)


for bucket, (w, h) in {"xhdpi": (320, 180), "xxhdpi": (480, 270), "xxxhdpi": (640, 360)}.items():
    save(banner(w, h), "app", "src", "play", "res", f"drawable-{bucket}", "app_banner.png")
save(store_icon(512), "play", "graphics", "icon-512.png")
save(feature_graphic(), "play", "graphics", "feature-graphic.png")
save(banner(1280, 720), "play", "graphics", "tv-banner-1280x720.png")
