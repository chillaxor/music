#!/usr/bin/env python3
"""用「此生皆欢喜/从前慢」圆形书法徽章图制作 launcher 图标。

策略：原图本身是圆形徽章构图（淡紫底 + 紫->橙粉书法字），按圆形裁切即可：
1) 自动检测圆形徽章的边框半径 R（半径亮度分布找暗谷，边框是深色环）；
2) 圆形裁切（圆心=图中心，半径=R），四角透明；
3) 右下角「豆包AI生成」水印正好落在圆外，被一并裁掉；
4) 输出 legacy mipmap（方/圆两版）+ 自适应 foreground + 预览图。
"""
import os
import numpy as np
from PIL import Image

SRC = r"C:\Users\linghit\.workbuddy\clipboard-images\clipboard-2026-10-29T07-28-02-793Z-898dc84d.jpg"
RES = r"D:\workbuddy20260529log\GDMusicPlayer-Android-v3\app\src\main\res"
ROOT = r"D:\workbuddy20260529log\GDMusicPlayer-Android-v3"

img = Image.open(SRC).convert("RGB")
a = np.asarray(img).astype(np.float32)
H, W, _ = a.shape
gray = a.mean(-1)
cx, cy = W // 2, H // 2
maxr = min(cx, cy)

# ---- 半径亮度分布：找圆环边框（暗谷） ----
bins = maxr // 4
prof = np.zeros(bins)
yy, xx = np.indices((H, W))
rr = np.sqrt((xx - cx) ** 2 + (yy - cy) ** 2)
for b in range(bins):
    mb = (rr >= b * 4) & (rr < (b + 1) * 4)
    prof[b] = gray[mb].mean() if mb.any() else (prof[b - 1] if b else gray.mean())

print(f"[image] {W}x{H}, center=({cx},{cy})")
print("[profile] r : brightness  (first 110 bins ~ r 0..440)")
for b in range(0, min(bins, 110)):
    bar = '#' * int((235 - prof[b]) / 6)
    print(f"  r={b*4:4d}: {prof[b]:6.1f} {bar}")

# 整图从中心到边缘都是均匀浅色、无强对比边框环 -> 徽章圆形基本充满整图。
# 用内切圆裁切：去掉四角、保留完整圆形徽章；右下角水印在大半径处一并被裁掉。
R = int(min(cx, cy) * 0.97)
print(f"[detect] use inscribed-circle R = {R} (maxr={maxr})")

# ---- 圆形裁切 ----
mask = Image.new("L", (W, H), 0)
ImageDraw = __import__("PIL.ImageDraw").ImageDraw
ImageDraw.Draw(mask).ellipse([cx - R, cy - R, cx + R, cy + R], fill=255)
art = img.convert("RGBA")
art.putalpha(mask)
crop = art.crop([cx - R, cy - R, cx + R, cy + R])
print(f"[crop] square {crop.size}, then resize to targets")


def make(size):
    return crop.resize((size, size), Image.LANCZOS).convert("RGBA")


legacy = {"mipmap-mdpi": 48, "mipmap-hdpi": 72, "mipmap-xhdpi": 96, "mipmap-xxhdpi": 144, "mipmap-xxxhdpi": 192}
fg = {"drawable-mdpi": 108, "drawable-hdpi": 162, "drawable-xhdpi": 216, "drawable-xxhdpi": 324, "drawable-xxxhdpi": 432}
for folder, size in legacy.items():
    out = os.path.join(RES, folder)
    os.makedirs(out, exist_ok=True)
    make(size).save(os.path.join(out, "ic_launcher.png"))
    make(size).save(os.path.join(out, "ic_launcher_round.png"))
    print(f"  {folder}: ic_launcher.png + ic_launcher_round.png @{size}px")
for folder, size in fg.items():
    out = os.path.join(RES, folder)
    os.makedirs(out, exist_ok=True)
    make(size).save(os.path.join(out, "ic_launcher_foreground.png"))
    print(f"  {folder}: ic_launcher_foreground.png @{size}px")

make(512).save(os.path.join(ROOT, "icon_preview.png"))
print("[done] preview -> icon_preview.png")
