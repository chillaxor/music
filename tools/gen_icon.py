#!/usr/bin/env python3
"""Generate GD音乐 launcher icons (gradient bg + white beamed eighth-note pair)."""
import os
from PIL import Image, ImageDraw

RES = r"D:\workbuddy20260529log\GDMusicPlayer-Android-v3\app\src\main\res"
C_TOP = (0x5B, 0x4B, 0xFF, 255)
C_BOT = (0x3A, 0x2B, 0xD6, 255)
WHITE = (255, 255, 255, 255)

def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(4))

def shape_mask(S, shape):
    mask = Image.new("L", (S, S), 0)
    md = ImageDraw.Draw(mask)
    if shape == "circle":
        md.ellipse([0, 0, S, S], fill=255)
    else:
        md.rounded_rectangle([0, 0, S, S], radius=0.22 * S, fill=255)
    return mask

def gradient_bg(S, shape):
    """diagonal gradient (top-left -> bottom-right) clipped to shape"""
    base = Image.new("RGBA", (S, S), C_TOP)
    overlay = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    px = overlay.load()
    for y in range(S):
        for x in range(S):
            t = (x + y) / (2.0 * S)
            px[x, y] = lerp(C_TOP, C_BOT, t)
    out = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    out.paste(overlay, (0, 0), shape_mask(S, shape))
    return out

def note_layer(S):
    """white beamed eighth-note pair; coords designed in a 108 viewport, scaled by k"""
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    k = S / 108.0
    for (cx, cy) in [(39.5, 73), (65.5, 67)]:
        rx, ry = 8.5 * k, 6.8 * k
        head = Image.new("RGBA", (int(rx * 2) + 4, int(ry * 2) + 4), (0, 0, 0, 0))
        hd = ImageDraw.Draw(head)
        hd.ellipse([2, 2, 2 + rx * 2, 2 + ry * 2], fill=WHITE)
        head = head.rotate(-18, resample=Image.BICUBIC, expand=True)
        img.alpha_composite(head, (int(cx * k - head.width / 2), int(cy * k - head.height / 2)))
    d = ImageDraw.Draw(img)
    d.rectangle([47 * k, 39 * k, 51 * k, 75 * k], fill=WHITE)
    d.rectangle([73 * k, 33 * k, 77 * k, 69 * k], fill=WHITE)
    d.polygon([(47 * k, 39 * k), (77 * k, 33 * k), (77 * k, 41.5 * k), (47 * k, 47.5 * k)], fill=WHITE)
    return img

def make(S, shape):
    img = gradient_bg(S, shape)
    img.alpha_composite(note_layer(S))
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
    make(size, "squircle").save(os.path.join(out, "ic_launcher.png"))
    make(size, "circle").save(os.path.join(out, "ic_launcher_round.png"))
    print(f"{folder}: {size}px saved")
make(512, "squircle").save(os.path.join(RES, "..", "..", "..", "icon_preview.png"))
print("preview saved")
