# -*- coding: utf-8 -*-
"""Generate VideoHub app icon & splash source images for @capacitor/assets."""
from PIL import Image, ImageDraw, ImageFont
from pathlib import Path

ASSETS = Path(__file__).resolve().parent / "assets"
ASSETS.mkdir(exist_ok=True)

ACCENT = (99, 102, 241, 255)       # #6366f1
ACCENT_DARK = (79, 70, 229, 255)   # #4f46e5
DARK_BG = (10, 10, 15, 255)        # #0a0a0f
WHITE = (255, 255, 255, 255)


def draw_play_triangle(draw, cx, cy, size, color):
    """Equilateral-ish play triangle pointing right, centered at (cx, cy)."""
    h = size  # horizontal span
    v = size * 1.15  # vertical span
    pts = [
        (cx - h / 2, cy - v / 2),
        (cx - h / 2, cy + v / 2),
        (cx + h / 2, cy),
    ]
    draw.polygon(pts, fill=color)


# ── icon.png: white play glyph on transparent (adaptive foreground) ──
icon = Image.new("RGBA", (1024, 1024), (0, 0, 0, 0))
d = ImageDraw.Draw(icon)
draw_play_triangle(d, 512, 512, 400, WHITE)
# subtle rounded-square outline glow omitted; keep glyph clean
icon.save(ASSETS / "icon.png")

# ── splash.png / splash-dark.png: glyph + wordmark on transparent ──
for name in ("splash.png", "splash-dark.png"):
    s = Image.new("RGBA", (2732, 2732), (0, 0, 0, 0))
    d = ImageDraw.Draw(s)
    # glyph
    draw_play_triangle(d, 1366, 1240, 320, WHITE)
    # wordmark
    font = ImageFont.truetype(r"C:\Windows\Fonts\arialbd.ttf", 170)
    text = "VideoHub"
    bbox = d.textbbox((0, 0), text, font=font)
    tw = bbox[2] - bbox[0]
    d.text((1366 - tw / 2, 1560), text, font=font, fill=WHITE)
    s.save(ASSETS / name)

# ── logo preview (rounded indigo tile + white glyph) for docs ──
logo = Image.new("RGBA", (1024, 1024), (0, 0, 0, 0))
d = ImageDraw.Draw(logo)
d.rounded_rectangle((32, 32, 992, 992), radius=224, fill=ACCENT)
draw_play_triangle(d, 512, 512, 400, WHITE)
logo.save(ASSETS / "logo-preview.png")

print("assets written:", [p.name for p in sorted(ASSETS.iterdir())])
