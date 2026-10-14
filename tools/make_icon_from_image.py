#!/usr/bin/env python3
"""从 AI 生成的 3D 图标图制作启动图标：裁掉外围橙底，只保留中间的 squircle 图标（四角透明）。

策略（几何拟合，不按颜色抠）：
1. 低饱和白色主体 mask → 最大连通组件 → squircle 白底面（彩色元素是洞，不影响外形）；
2. 组件像素的真实包围盒 = squircle 边界框（强制正方形）；
3. 从角部的白色边界剖面测圆角半径（兜底 0.22*side）；
4. 4x 超采样圆角矩形遮罩抠图 → 内部辉光/元素/阴影 100% 保留，仅四角透明；
5. 输出：legacy mipmap PNG（方/圆两版）+ 自适应前景 bitmap + 预览图。
"""
import os
from collections import deque

import numpy as np
from PIL import Image, ImageChops, ImageDraw

SRC = r"C:\Users\linghit\.workbuddy\clipboard-images\clipboard-2026-10-14T03-52-24-465Z-c1a11ea2.jpg"
RES = r"D:\workbuddy20260529log\GDMusicPlayer-Android-v3\app\src\main\res"
ROOT = r"D:\workbuddy20260529log\GDMusicPlayer-Android-v3"

img = Image.open(SRC).convert("RGB")
a = np.asarray(img).astype(np.float32)
H, W, _ = a.shape
mx = a.max(-1); mn = a.min(-1)
sat = (mx - mn) / np.maximum(mx, 1.0)

# ---- 1. 白色主体 mask + 最大连通组件（BFS） ----
white = sat < 0.30
lab = np.zeros((H, W), dtype=np.int32)
cid = 0; best = 0; best_size = 0
for y in range(H):
    for x in range(W):
        if white[y, x] and lab[y, x] == 0:
            cid += 1; size = 0; dq = deque([(y, x)]); lab[y, x] = cid
            while dq:
                cy, cx = dq.popleft(); size += 1
                for dy, dx in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    ny, nx = cy + dy, cx + dx
                    if 0 <= ny < H and 0 <= nx < W and white[ny, nx] and lab[ny, nx] == 0:
                        lab[ny, nx] = cid; dq.append((ny, nx))
            if size > best_size:
                best_size = size; best = cid
comp = lab == best
print(f"[1] largest white component: {best_size} px ({best_size / (H * W) * 100:.1f}% of image)")

# ---- 2. 组件真实包围盒（强制正方形） ----
ys, xs = np.where(comp)
bx0, bx1, by0, by1 = int(xs.min()), int(xs.max()), int(ys.min()), int(ys.max())
side = max(bx1 - bx0, by1 - by0) + 1
cx, cy = (bx0 + bx1) // 2, (by0 + by1) // 2
x0, y0 = cx - side // 2, cy - side // 2
x1, y1 = x0 + side - 1, y0 + side - 1
print(f"[2] squircle bbox: ({x0},{y0})-({x1},{y1}) side={side} (raw w={bx1-bx0+1} h={by1-by0+1})")

# ---- 3. 圆角半径：从角部白色边界剖面测 ----
def left_edge(row):
    xs = np.where(comp[row, x0:x1 + 1])[0]
    return x0 + xs.min() if len(xs) else None

def top_edge(col):
    ys = np.where(comp[y0:y1 + 1, col])[0]
    return y0 + ys.min() if len(ys) else None

mid = side // 2
straight_left = min(v for v in (left_edge(y) for y in range(cy - side // 6, cy + side // 6)) if v is not None)
straight_top = min(v for v in (top_edge(x) for x in range(cx - side // 6, cx + side // 6)) if v is not None)
rows_to_straight = [y - y0 for y in range(y0, y0 + side // 3) if (v := left_edge(y)) is not None and v <= straight_left + 2]
cols_to_straight = [x - x0 for x in range(x0, x0 + side // 3) if (v := top_edge(x)) is not None and v <= straight_top + 2]
r_meas = max(rows_to_straight[0] if rows_to_straight else 0, cols_to_straight[0] if cols_to_straight else 0)
r = r_meas if r_meas >= 8 else int(0.22 * side)
r = min(r, side // 3)
print(f"[3] corner radius: measured={r_meas} -> use {r} ({r / side * 100:.1f}% of side)")

# ---- 4. 4x 超采样圆角矩形遮罩抠图 ----
S = 4
big = side * S
m = Image.new("L", (big, big), 0)
ImageDraw.Draw(m).rounded_rectangle([0, 0, big - 1, big - 1], radius=r * S, fill=255)
mask = m.resize((side, side), Image.LANCZOS)
art = img.crop((x0, y0, x1 + 1, y1 + 1))
print(f"[4] art cropped: {art.size}, mask {mask.size}")

def make(size, shape):
    im = art.resize((size, size), Image.LANCZOS).convert("RGBA")
    al = mask.resize((size, size), Image.LANCZOS)
    if shape == "circle":
        circ = Image.new("L", (size, size), 0)
        ImageDraw.Draw(circ).ellipse([0, 0, size - 1, size - 1], fill=255)
        al = ImageChops.multiply(al, circ)
    im.putalpha(al)
    return im

# ---- 5. 输出 ----
legacy = {"mipmap-mdpi": 48, "mipmap-hdpi": 72, "mipmap-xhdpi": 96, "mipmap-xxhdpi": 144, "mipmap-xxxhdpi": 192}
fg = {"drawable-mdpi": 108, "drawable-hdpi": 162, "drawable-xhdpi": 216, "drawable-xxhdpi": 324, "drawable-xxxhdpi": 432}
for folder, size in legacy.items():
    out = os.path.join(RES, folder); os.makedirs(out, exist_ok=True)
    make(size, "squircle").save(os.path.join(out, "ic_launcher.png"))
    make(size, "circle").save(os.path.join(out, "ic_launcher_round.png"))
    print(f"  {folder}: ic_launcher.png + ic_launcher_round.png @{size}px")
for folder, size in fg.items():
    out = os.path.join(RES, folder); os.makedirs(out, exist_ok=True)
    make(size, "squircle").save(os.path.join(out, "ic_launcher_foreground.png"))
    print(f"  {folder}: ic_launcher_foreground.png @{size}px")
make(512, "squircle").save(os.path.join(ROOT, "icon_preview.png"))
print("[5] preview saved -> icon_preview.png")
