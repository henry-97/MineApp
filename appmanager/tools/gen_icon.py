# -*- coding: utf-8 -*-
"""生成应用图标：两种造型 × 三种颜色 = 6 套，全部打进包里供 App 内切换。

造型：
  sq —— 四个白色圆角方块，中心镂空挂锁，锁身嵌白色闪电（原版）
  cl —— 白色四叶草 + 短茎

颜色：
  g 墨绿 #0F6E56   y 金 #B8862F   k 近黑 #171716

每个组合产出：
  res/mipmap-*/ic_launcher_<v>.png        传统图标（48dp，圆角方底）
  res/mipmap-*/ic_launcher_fg_<v>.png     自适应前景（108dp）
  res/mipmap-anydpi-v26/ic_launcher_<v>.xml
  assets/icon-<v>.png                     设置页里的预览小图（128px）

坐标统一在 108 单位画布上描述（= 自适应图标 108dp），1 单位 = 1dp。
"""
import math
import os
from PIL import Image, ImageDraw, ImageChops

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(BASE, "res")
ASSETS = os.path.join(BASE, "assets")

SS = 8                          # 每个单位的采样像素，先大后缩，保证边缘平滑
UNITS = 108
S = UNITS * SS

# ---- 配色 ----
COLORS = [
    ("g", (15, 110, 86, 255)),      # #0F6E56 墨绿
    ("y", (184, 134, 47, 255)),     # #B8862F 金
    ("k", (23, 23, 22, 255)),       # #171716 近黑
]
SHAPES = ["sq", "cl"]

# ---- 方块造型的几何参数（单位：dp） ----
PAD = 22.6                      # 四块方块的左上起点
SQ = 26.8                       # 单块方块边长
GAP = 108 - 2 * PAD - 2 * SQ    # 中间十字间隙
RAD = 4.6                       # 方块圆角

CX = CY = 54.0                  # 画布中心
BODY_W, BODY_H = 21.0, 21.0     # 锁身
BODY_TOP = 46.5
BODY_R = 4.5
SH_R_OUT, SH_R_IN = 9.8, 5.0    # 锁梁外/内半径（= 描边 4.8）
SH_CY = BODY_TOP                # 锁梁圆心落在锁身顶边
BOLT_W, BOLT_H = 11.6, 15.8     # 闪电尺寸

# ---- 四叶草的几何参数 ----
# 每片花瓣 = 正方形减去两个圆：
#   朝外角那一头倒圆（圆头朝外），右边、下边各被一个大圆咬进去一个凹弧，
#   两条弧在朝中心的那一角收成一个尖（尖头朝中心）。
# 两个咬口圆的半径由圆心外移量 k 唯一确定（r = √(k² + (边长/2)²)），
# 保证圆弧正好穿过正方形的两个角，尖头才收得出来。
# 四片由同一片旋转 0/90/180/270 得到，四片之间留出镂空的绿十字。
#
# 四叶草：整体转 45°（花瓣落在上下左右四个正方向），中间留出绿色 X 负形
CL = {
    "cl":  dict(pad=11.0, sq=33.0, ck=0.78, rnd=9.0,  lock=0.98, halo=1.8, rot=45.0),
}
CL_ROT_S = 0.97                 # 旋转 45° 后角点会顶出画布，按此缩回

# 内容相对 108dp 画布的缩放系数：自适应图标只露出中间 72dp，不缩会被裁边
K = float(os.environ.get("ICON_K", "0.80"))
# 传统图标（整幅不裁边）：方块版铺满；四叶草版留一点绿边，别顶到圆角上
LEGACY_K = {"sq": 1.0, "cl": 0.94}
WHITE = (255, 255, 255, 255)
# 1 = 四叶草改白底彩叶（仅用于出对比图，不默认启用）
INVERT = os.environ.get("ICON_INVERT") == "1"

# 中心白垫（填补十字间隙，让锁的镂空轮廓完整）
PLATE_W, PLATE_R = 26.0, 8.5
PLATE_TOP = 34.0
PLATE_BOT = BODY_TOP + BODY_H + 2.5


def u(v):
    return v * SS


# ---------------- 造型 1：方块 + 锁 + 闪电 ----------------

def build_plate(w=PLATE_W, top=PLATE_TOP, bot=PLATE_BOT):
    """中心白垫：填补十字间隙，让锁的镂空轮廓完整闭合。"""
    m = Image.new("L", (S, S), 0)
    ImageDraw.Draw(m).rounded_rectangle(
        [u(CX - w / 2), u(top), u(CX + w / 2), u(bot)],
        radius=u(PLATE_R), fill=255)
    return m


def build_plate_round(w, h):
    """椭圆白垫：四叶草版用，比方角矩形更贴合叶瓣的圆润轮廓。"""
    m = Image.new("L", (S, S), 0)
    ImageDraw.Draw(m).ellipse(
        [u(CX - w / 2), u(52.0 - h / 2), u(CX + w / 2), u(52.0 + h / 2)], fill=255)
    return m


def scale_mask(mask, s, cx=CX, cy=52.0, dy=0.0):
    """把蒙版围绕 (cx, cy) 缩放 s 倍，并可选下移 dy（用于给四叶草版单独调锁）。"""
    if abs(s - 1.0) < 1e-6 and abs(dy) < 1e-6:
        return mask
    w = int(round(S * s))
    small = mask.resize((w, w), Image.LANCZOS)
    out = Image.new("L", (S, S), 0)
    # 注意：偏移量必须换算成像素（u()），不能直接拿 dp 值去 paste
    out.paste(small, (int(round(u(cx) * (1 - s))),
                      int(round(u(cy) * (1 - s) + dy * SS))))
    return out


def build_squares():
    m = Image.new("L", (S, S), 0)
    d = ImageDraw.Draw(m)
    x1, x2 = PAD, PAD + SQ
    y1, y2 = PAD, PAD + SQ
    x3, x4 = 108 - PAD - SQ, 108 - PAD
    y3, y4 = 108 - PAD - SQ, 108 - PAD
    for (a, b, c, e) in ((x1, y1, x2, y2), (x3, y1, x4, y2),
                         (x1, y3, x2, y4), (x3, y3, x4, y4)):
        d.rounded_rectangle([u(a), u(b), u(c), u(e)], radius=u(RAD), fill=255)
    return m


def build_lock(s=1.0, grow=0.0):
    """grow>0 时把锁整体向外撑大 grow（单位 dp），用来生成「描边」蒙版：
    先撑大一圈挖掉内容，再把原尺寸的锁填回来，锁四周就多出一条底色缝。"""
    g = grow
    m = Image.new("L", (S, S), 0)
    d = ImageDraw.Draw(m)
    # 锁梁：外圆填满，再挖掉内圆，形成环形
    ro = SH_R_OUT + g
    ri = max(0.6, SH_R_IN - g)
    d.ellipse([u(CX - ro), u(SH_CY - ro), u(CX + ro), u(SH_CY + ro)], fill=255)
    d.ellipse([u(CX - ri), u(SH_CY - ri), u(CX + ri), u(SH_CY + ri)], fill=0)
    d.rounded_rectangle([u(CX - BODY_W / 2 - g), u(BODY_TOP - g),
                         u(CX + BODY_W / 2 + g), u(BODY_TOP + BODY_H + g)],
                        radius=u(BODY_R + g), fill=255)
    return scale_mask(m, s)


def build_bolt(s=1.0):
    pts = [(6, 0), (0, 8), (4, 8), (3, 14), (10, 5.5), (6, 5.5)]
    bx = CX - BOLT_W / 2
    by = BODY_TOP + (BODY_H - BOLT_H) / 2
    poly = [(u(bx + x / 10.0 * BOLT_W), u(by + y / 14.0 * BOLT_H)) for x, y in pts]
    m = Image.new("L", (S, S), 0)
    ImageDraw.Draw(m).polygon(poly, fill=255)
    return scale_mask(m, s)


# ---------------- 造型 2：四叶草 ----------------

def _one_leaf(cfg):
    """左上花瓣：外角倒圆（圆头朝外），两条凹弧在右下收成尖（尖头指向画布中心）。"""
    pad, sq = cfg["pad"], cfg["sq"]
    rnd = cfg["rnd"]
    x1, y1 = pad, pad
    x2, y2 = pad + sq, pad + sq

    leaf = Image.new("L", (S, S), 0)
    d = ImageDraw.Draw(leaf)
    if rnd > 0:
        d.rounded_rectangle([u(x1), u(y1), u(x2), u(y2)],
                            radius=u(rnd), fill=255)
        # rounded_rectangle 会把四个角都倒圆；把朝中心那个内角补回直角，
        # 两条咬口弧才能在那儿收出尖头
        d.rectangle([u(x2 - rnd), u(y2 - rnd), u(x2), u(y2)], fill=255)
    else:
        d.rectangle([u(x1), u(y1), u(x2), u(y2)], fill=255)

    k = cfg["ck"] * sq
    r = math.hypot(k, sq / 2.0)
    # 圆 A：圆心在右侧外 → 咬掉右边，形成向左凹的弧
    ax, ay = x2 + k, y1 + 0.5 * sq
    # 圆 B：圆心在下侧外 → 咬掉下边，形成向上凹的弧
    bx, by = x1 + 0.5 * sq, y2 + k
    for (cx, cy) in ((ax, ay), (bx, by)):
        cut = Image.new("L", (S, S), 0)
        ImageDraw.Draw(cut).ellipse(
            [u(cx - r), u(cy - r), u(cx + r), u(cy + r)], fill=255)
        leaf = ImageChops.subtract(leaf, cut)
    return leaf


def build_clover(shape="cl"):
    """一片叶旋转四次得到四叶草：圆头朝外、尖朝中心，中间留出镂空十字。不要茎。
    cl 再整体转 45°（花瓣挪到上下左右）；cl2 不转（花瓣留在四角）。"""
    cfg = CL[shape]
    leaf = _one_leaf(cfg)
    m = Image.new("L", (S, S), 0)
    for ang in (0, 90, 180, 270):
        m = ImageChops.lighter(m, leaf.rotate(ang, resample=Image.BICUBIC))
    if abs(cfg["rot"]) > 1e-6:
        m = m.rotate(cfg["rot"], resample=Image.BICUBIC, fillcolor=0)
        m = scale_mask(m, CL_ROT_S, cy=CY)
    return m


def recenter(mask):
    """按不透明区域包围盒把内容挪回画布正中（四叶草上下不对称）。"""
    bbox = mask.getbbox()
    if not bbox:
        return mask
    cx = (bbox[0] + bbox[2]) / 2.0
    cy = (bbox[1] + bbox[3]) / 2.0
    dx = int(round(S / 2.0 - cx))
    dy = int(round(S / 2.0 - cy))
    if dx == 0 and dy == 0:
        return mask
    out = Image.new("L", (S, S), 0)
    out.paste(mask, (dx, dy))
    return out


# ---------------- 组装 ----------------

def palette(shape, color):
    """返回 (底色, 内容色)。
    默认：色底 + 白内容（所有造型一致）。
    设 ICON_INVERT=1 时四叶草反过来 —— 白底 + 色叶，仅供对比预览。"""
    if shape in CL and INVERT:
        return WHITE, color
    return color, WHITE


def content_raw(shape, fg=WHITE):
    """底形 + 中心白垫 → 挖出锁形 → 把闪电填白。所有造型共用同一把锁，
    四叶草版把锁缩小，正好落在四片花瓣尖头让出的空档里。"""
    if shape in CL:
        # 四片花瓣 + 中心锁；锁外面先撑大一圈挖出底色描边，锁才不会和花瓣糊在一起
        cfg = CL[shape]
        s = cfg["lock"]
        halo = cfg["halo"]
        alpha = build_clover(shape)
        alpha = ImageChops.subtract(alpha, build_lock(s, halo))
        alpha = ImageChops.lighter(alpha, build_lock(s))
        alpha = ImageChops.subtract(alpha, build_bolt(s))
    else:
        base = build_squares()
        plate = build_plate()
        lock = build_lock()
        bolt = build_bolt()
        alpha = ImageChops.lighter(base, plate)
        alpha = ImageChops.subtract(alpha, lock)
        alpha = ImageChops.lighter(alpha, bolt)
    img = Image.new("RGBA", (S, S), fg)
    img.putalpha(alpha)
    return img


def scaled_content(shape, k, fg=WHITE):
    c = content_raw(shape, fg)
    w = int(round(S * k))
    small = c.resize((w, w), Image.LANCZOS)
    out = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    o = (S - w) // 2
    out.alpha_composite(small, (o, o))
    return out


def foreground(shape, fg=WHITE):
    """自适应图标只露出中间 72/108，内容按 K 缩放居中，避免被裁掉边。"""
    return scaled_content(shape, K, fg)


def legacy_master(shape, color):
    """传统图标：圆角方底 + 内容（整幅不裁边）。"""
    bg_color, fg_color = palette(shape, color)
    bg = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(bg)
    d.rounded_rectangle([0, 0, S - 1, S - 1], radius=u(22), fill=bg_color)
    bg.alpha_composite(scaled_content(shape, LEGACY_K.get(shape, 1.0), fg_color))
    return bg


def save(img, path, size):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.resize((size, size), Image.LANCZOS).save(path)


ADAPTIVE = (
    '<?xml version="1.0" encoding="utf-8"?>\n'
    '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
    '    <background android:drawable="@color/ic_bg_%s"/>\n'
    '    <foreground android:drawable="@mipmap/ic_launcher_fg_%s"/>\n'
    '    <monochrome android:drawable="@mipmap/ic_launcher_fg_%s"/>\n'
    '</adaptive-icon>\n')


def main():
    dens = [("mdpi", 1), ("hdpi", 1.5), ("xhdpi", 2), ("xxhdpi", 3), ("xxxhdpi", 4)]
    ad = os.path.join(RES, "mipmap-anydpi-v26")

    for shape in SHAPES:
        for cname, color in COLORS:
            key = "%s_%s" % (shape, cname)
            fg = foreground(shape, palette(shape, color)[1])
            leg = legacy_master(shape, color)
            # 自适应底：跟随配色；四叶草反色时统一白底
            bg_key = "w" if (shape in CL and INVERT) else cname

            for name, k in dens:
                d = os.path.join(RES, "mipmap-" + name)
                save(leg, os.path.join(d, "ic_launcher_%s.png" % key), int(48 * k))
                save(fg, os.path.join(d, "ic_launcher_fg_%s.png" % key), int(108 * k))

            os.makedirs(ad, exist_ok=True)
            with open(os.path.join(ad, "ic_launcher_%s.xml" % key),
                      "w", encoding="utf-8", newline="\n") as f:
                f.write(ADAPTIVE % (bg_key, key, key))

            # 设置页预览图（圆角方底 + 内容，128px）
            save(leg, os.path.join(ASSETS, "icon-%s.png" % key), 128)

            # 默认图标沿用第一套（墨绿方块），供 android:icon 兜底
            if key == "sq_g":
                for name, k in dens:
                    d = os.path.join(RES, "mipmap-" + name)
                    save(leg, os.path.join(d, "ic_launcher.png"), int(48 * k))
                    save(fg, os.path.join(d, "ic_launcher_fg.png"), int(108 * k))
                with open(os.path.join(ad, "ic_launcher.xml"),
                          "w", encoding="utf-8", newline="\n") as f:
                    f.write(ADAPTIVE % ("g", "sq_g", "sq_g"))

    # 预览大图（不参与打包）
    out = os.path.join(BASE, "out")
    os.makedirs(out, exist_ok=True)
    for shape in SHAPES:
        for cname, color in COLORS:
            legacy_master(shape, color).resize((256, 256), Image.LANCZOS).save(
                os.path.join(out, "icon-%s_%s.png" % (shape, cname)))

    print("icon ok  gap=%.2f  K=%.2f  variants=%d" % (GAP, K, len(SHAPES) * len(COLORS)))


if __name__ == "__main__":
    main()
