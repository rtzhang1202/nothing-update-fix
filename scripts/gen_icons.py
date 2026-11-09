#!/usr/bin/env python3
"""Generate Nothing-aesthetic launcher icons (black background + white dot-matrix N).

Outputs (deterministic):
  app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml   (written by hand, see res/)
  app/src/main/res/drawable/ic_launcher_background.png  432x432 solid black
  app/src/main/res/drawable/ic_launcher_foreground.png  432x432 dot-matrix N (transparent bg)
  app/src/main/res/mipmap-xxhdpi/ic_launcher.png        192x192 legacy fallback
  app/src/main/res/mipmap-xxhdpi/ic_launcher_round.png  192x192 legacy fallback (round)
"""
import os
from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")

BLACK = (10, 10, 10, 255)
WHITE = (255, 255, 255, 255)

# dot-matrix N (5 cols x 7 rows), Nothing dot-matrix glyph style
N_GLYPH = [
    "X...X",
    "XX..X",
    "X.X.X",
    "X.X.X",
    "X..XX",
    "X...X",
    "X...X",
]

# small dot-matrix gear accent (7x7) used in the legacy icon corner
GEAR = [
    "..X..",
    ".XXX.",
    "XXXXX",
    ".XXX.",
    "..X..",
]


def draw_dot_matrix(draw, glyph, origin, spacing, radius, color):
    ox, oy = origin
    for r, row in enumerate(glyph):
        for c, ch in enumerate(row):
            if ch == "X":
                x = ox + c * spacing
                y = oy + r * spacing
                draw.ellipse([x - radius, y - radius, x + radius, y + radius], fill=color)


def make_foreground(size=432):
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    spacing = int(size * 0.079)          # ~34px at 432
    radius = int(spacing * 0.33)
    cols, rows = 5, 7
    gw = (cols - 1) * spacing
    gh = (rows - 1) * spacing
    origin = ((size - gw) // 2, (size - gh) // 2)
    draw_dot_matrix(d, N_GLYPH, origin, spacing, radius, WHITE)
    # subtle sparse dot texture in the corners (Nothing aesthetic)
    small = max(2, radius // 2)
    for gx in range(6):
        for gy in range(6):
            x = int(size * 0.10) + gx * spacing
            y = int(size * 0.10) + gy * spacing
            if abs(x - size / 2) > size * 0.22 or abs(y - size / 2) > size * 0.22:
                d.ellipse([x - small, y - small, x + small, y + small],
                          fill=(255, 255, 255, 40))
    return img


def make_background(size=432):
    return Image.new("RGBA", (size, size), BLACK)


def make_legacy(size=192, round_mask=False):
    img = Image.new("RGBA", (size, size), BLACK)
    d = ImageDraw.Draw(img)
    spacing = int(size * 0.105)
    radius = int(spacing * 0.33)
    cols, rows = 5, 7
    gw = (cols - 1) * spacing
    gh = (rows - 1) * spacing
    origin = ((size - gw) // 2, (size - gh) // 2 - spacing // 2)
    draw_dot_matrix(d, N_GLYPH, origin, spacing, radius, WHITE)
    # gear accent bottom-right
    gsp = max(3, spacing // 2)
    gr = max(2, gsp // 2)
    gorigin = (size - 5 * gsp - spacing, size - 5 * gsp - spacing)
    draw_dot_matrix(d, GEAR, gorigin, gsp, gr, (255, 255, 255, 200))
    if round_mask:
        mask = Image.new("L", (size, size), 0)
        ImageDraw.Draw(mask).ellipse([0, 0, size - 1, size - 1], fill=255)
        out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        out.paste(img, (0, 0), mask)
        return out
    return img


def main():
    os.makedirs(os.path.join(RES, "drawable"), exist_ok=True)
    os.makedirs(os.path.join(RES, "mipmap-xxhdpi"), exist_ok=True)
    os.makedirs(os.path.join(RES, "mipmap-anydpi-v26"), exist_ok=True)

    make_background().save(os.path.join(RES, "drawable", "ic_launcher_background.png"))
    make_foreground().save(os.path.join(RES, "drawable", "ic_launcher_foreground.png"))
    make_legacy().save(os.path.join(RES, "mipmap-xxhdpi", "ic_launcher.png"))
    make_legacy(round_mask=True).save(os.path.join(RES, "mipmap-xxhdpi", "ic_launcher_round.png"))
    print("icons written to", RES)


if __name__ == "__main__":
    main()
