"""生成 DownX 应用图标 PNG（各密度），只用 Pillow。"""
import os
from PIL import Image, ImageDraw, ImageFilter

S = 1024  # 放大 2 倍绘制再缩小，边缘更干净
OUT = r"F:\WorkBuddyDownloader\2026-11-08-09-54-39\DownX\app\src\main\res"

img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
d = ImageDraw.Draw(img)

# 圆角深色底（线性渐变）
grad = Image.new("RGBA", (S, S), (0, 0, 0, 0))
gd = ImageDraw.Draw(grad)
for y in range(S):
    t = y / S
    r = int(14 + (26 - 14) * t)
    g = int(22 + (42 - 22) * t)
    b = int(33 + (60 - 33) * t)
    gd.line([(0, y), (S, y)], fill=(r, g, b, 255))

mask = Image.new("L", (S, S), 0)
ImageDraw.Draw(mask).rounded_rectangle([0, 0, S - 1, S - 1], radius=S * 0.22, fill=255)
img.paste(grad, (0, 0), mask)

# 蓝色光晕
glow = Image.new("RGBA", (S, S), (0, 0, 0, 0))
grid = ImageDraw.Draw(glow)
grid.ellipse([S * 0.18, S * 0.06, S * 0.82, S * 0.70], fill=(29, 155, 240, 46))
glow = glow.filter(ImageFilter.GaussianBlur(S * 0.09))
img = Image.alpha_composite(img, glow)
d = ImageDraw.Draw(img)

BLUE = (29, 155, 240, 255)
WHITE = (244, 248, 252, 255)
W = int(S * 0.088)

cx = S // 2
# 箭头竖线
d.line([(cx, int(S * 0.245)), (cx, int(S * 0.565))], fill=BLUE, width=W)
# 箭头 V 形（圆角）
d.line([(int(S * 0.345), int(S * 0.445)), (cx, int(S * 0.605))], fill=BLUE, width=W, joint="curve")
d.line([(cx, int(S * 0.605)), (int(S * 0.655), int(S * 0.445))], fill=BLUE, width=W, joint="curve")
# 箭头端点圆头
for pt in [(cx, int(S * 0.245)), (int(S * 0.345), int(S * 0.445)), (int(S * 0.655), int(S * 0.445))]:
    d.ellipse([pt[0] - W // 2, pt[1] - W // 2, pt[0] + W // 2, pt[1] + W // 2], fill=BLUE)

# 白色托盘
tw = int(S * 0.080)
d.line([(int(S * 0.285), int(S * 0.635)), (int(S * 0.285), int(S * 0.720))], fill=WHITE, width=tw)
d.line([(int(S * 0.715), int(S * 0.720)), (int(S * 0.715), int(S * 0.635))], fill=WHITE, width=tw)
d.line([(int(S * 0.285), int(S * 0.720)), (int(S * 0.715), int(S * 0.720))], fill=WHITE, width=tw)
for pt in [(int(S * 0.285), int(S * 0.720)), (int(S * 0.715), int(S * 0.720))]:
    d.ellipse([pt[0] - tw // 2, pt[1] - tw // 2, pt[0] + tw // 2, pt[1] + tw // 2], fill=WHITE)
d.line([(int(S * 0.285), int(S * 0.635)), (int(S * 0.285), int(S * 0.635))], fill=WHITE, width=tw)

master = os.path.join(r"F:\android-build", "icon_master.png")
img.resize((512, 512), Image.LANCZOS).save(master)

dens = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
for name, size in dens.items():
    folder = os.path.join(OUT, "mipmap-" + name)
    os.makedirs(folder, exist_ok=True)
    small = img.resize((size, size), Image.LANCZOS)
    small.save(os.path.join(folder, "ic_launcher.png"))
    print("saved", folder, size)
print("master", master)
