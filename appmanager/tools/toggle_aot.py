#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""逐个打开 AOT 开关 —— 每一步都截图验证，绝不在筛选未生效时点开关。

教训：上一版盲点固定坐标，筛选没生效时列表还是全量，
点 (960,690) 会落到第一行（微信）的开关上，把它的 AOT 关了。
"""
import subprocess, time, sys
from PIL import Image

ADB = r"D:\deepcode_default\workbuddy\downloads\platform-tools\adb.exe"
SHOT = r"D:\deepcode_default\workbuddy\android-app\appmanager\out\_probe.png"

# 待处理：包名关键字（小写，search 是 toLowerCase 后 indexOf）
TARGETS = [
    ("taobao.taobao", "淘宝"),
    ("taobao.etao", "一淘"),
    ("cainiao", "菜鸟"),
    ("sankuai", "美团"),
    ("jingyao", "哈啰"),
    ("antfortune", "蚂蚁财富"),
    ("mybank", "网商银行"),
    ("deskclock", "时钟"),
    ("soundrecorder", "录音机"),
    ("miui.calculator", "计算器"),
    ("miui.notes", "便签"),
    ("miui.player", "音乐"),
]

SW_X, SW_Y = 960, 690          # 单结果时第一行开关中心
FIELD_X, FIELD_Y = 540, 384    # 搜索框中心


def sh(cmd):
    subprocess.run([ADB, "shell", cmd], capture_output=True, timeout=120)


def shot():
    with open(SHOT, "wb") as f:
        subprocess.run([ADB, "exec-out", "screencap", "-p"], stdout=f, timeout=120)
    return Image.open(SHOT).convert("RGB")


def dark_count(im, box):
    px = im.crop(box).load()
    w, h = box[2] - box[0], box[3] - box[1]
    return sum(1 for y in range(0, h, 3) for x in range(0, w, 3)
               if sum(px[x, y]) < 330)


def switch_on(im):
    """开关区域有较多品牌绿 = 已开启"""
    px = im.crop((920, 650, 1000, 730)).load()
    green = 0
    for y in range(0, 80, 2):
        for x in range(0, 80, 2):
            r, g, b = px[x, y]
            if r < 90 and g > 80 and b < 110 and g > b + 15:
                green += 1
    return green > 120


def main():
    ok, skip, fail = [], [], []
    for q, name in TARGETS:
        # 1. 聚焦搜索框并清空（DEL 30 次，空框也无害）
        sh(f"input tap {FIELD_X} {FIELD_Y}")
        time.sleep(1.0)
        sh("input keyevent " + " ".join(["67"] * 30))
        time.sleep(0.6)

        # 2. 逐字符输入，避免 WebView 每键重绘丢字
        for c in q:
            sh(f"input text '{c}'")
            time.sleep(0.22)
        time.sleep(1.6)

        # 3. 验证：第二行位置必须是空白（说明只筛出 1 条）
        im = shot()
        second_row_ink = dark_count(im, (60, 760, 1000, 900))
        if second_row_ink > 40:
            print(f"跳过 {name}({q})：筛选未生效，第二行仍有内容 ink={second_row_ink}")
            skip.append(name)
            continue

        # 4. 已经是开的就不动
        if switch_on(im):
            print(f"已是开 {name}")
            ok.append(name)
            continue

        # 5. 点开关，等 aotStatus 返回（dumpsys 单包约 5s）
        sh(f"input tap {SW_X} {SW_Y}")
        time.sleep(9)
        im2 = shot()
        if switch_on(im2):
            print(f"已开启 {name}")
            ok.append(name)
        else:
            print(f"失败   {name}({q})：点了但开关没变绿")
            fail.append(name)
        time.sleep(0.5)

    print("\n=== 汇总 ===")
    print("成功/已开:", ", ".join(ok) or "无")
    print("跳过    :", ", ".join(skip) or "无")
    print("失败    :", ", ".join(fail) or "无")


if __name__ == "__main__":
    main()
