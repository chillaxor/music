#!/usr/bin/env python3
"""Generate GD Music launcher icons (purple rounded bg + white eighth note)."""
import math, os
from PIL import Image, ImageDraw

RES = r"D:\workbuddy20260529log\GDMusicPlayer-Android-v3\app\src\main\res"
PURPLE = (0x5B, 0x4B, 0xFF, 255)
PURPLE_DARK = (0x47, 0x3A, 0xD8, 255)  # bottom gradient shade
WHITE = (255, 255, 255, 255)

def rounded_bg(S):
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    r = 0.22 * S
    d.rounded_rectangle([0, 0, S, S], radius=r, fill=PURPLE)
    # subtle bottom gradient for depth
    for i in range(int(0.5 * S), S):
        t = (i - 0.5 * S) / (0.5 * S)
        col = tuple(int(PURPLE[j] * (1 - t) + PURPLE_DARK[j] * t) for j in range(4))
        d.line([(0, i), (S, i)], fill=col)
    return img

def draw_note(d, S):
    # eighth note, white
    head_cx, head_cy = 0.37 * S, 0.66 * S
    rx, ry = 0.155 * S, 0.115 * S
    d.ellipse([head_cx - rx, head_cy - ry, head_cx + rx, head_cy + ry], fill=WHITE)
    # stem: vertical bar from top of head to upper area, on right side of head
    stem_x = head_cx + rx - 0.02 * S
    stem_w = 0.055 * S
    top_y = 0.24 * S
    d.rectangle([stem_x, top_y, stem_x + stem_w, head_cy + ry * 0.3], fill=WHITE)
    # flag: filled polygon from top of stem curving right
    fx0 = stem_x + stem_w
    fy0 = top_y
    fx1 = stem_x + stem_w + 0.17 * S
    fy1 = top_y + 0.10 * S
    fx2 = stem_x + stem_w + 0.02 * S
    fy2 = top_y + 0.16 * S
    d.polygon([(fx0, fy0), (fx1, fy1), (fx2, fy2), (fx0 + 0.01 * S, fy0 + 0.13 * S)], fill=WHITE)

def make(S):
    img = rounded_bg(S)
    d = ImageDraw.Draw(img)
    draw_note(d, S)
    return img

dpi_map = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}
for folder, size in dpi_map.items():
    out = os.path.join(RES, folder)
    os.makedirs(out, exist_ok=True)
    img = make(size)
    img.save(os.path.join(out, "ic_launcher.png"))
    img.save(os.path.join(out, "ic_launcher_round.png"))
    print(f"{folder}: {size}px saved")
print("done")
