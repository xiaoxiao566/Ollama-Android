#!/usr/bin/env python3
# 生成应用图标：启动图标 + 通知图标
from PIL import Image, ImageDraw, ImageFont

OUT = "/data/user/work/ollama-project/res/drawable"

# ---- 启动图标：192x192，靛蓝圆角底 + 白色圆环（代表 Ollama 的 O）----
def launcher():
    size = 192
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    # 圆角方形底
    d.rounded_rectangle([0, 0, size - 1, size - 1], radius=40, fill=(79, 70, 229, 255))
    # 白色圆环
    cx, r, w = size // 2, 58, 22
    d.ellipse([cx - r, cx - r, cx + r, cx + r], outline=(255, 255, 255, 255), width=w)
    img.save(f"{OUT}/ic_launcher.png")

# ---- 通知图标：96x96，透明底 + 白色 O（状态栏常渲染为单色）----
def notification():
    size = 96
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    cx, r, w = size // 2, 30, 12
    d.ellipse([cx - r, cx - r, cx + r, cx + r], outline=(255, 255, 255, 255), width=w)
    img.save(f"{OUT}/ic_notification.png")

launcher()
notification()
print("icons generated")