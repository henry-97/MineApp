#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
应用管理器 · Termux 本地 Web UI

启动：python ~/server.py
打开：http://127.0.0.1:8088

依赖：pkg install python android-tools
链路：本服务 -> adb(127.0.0.1:5555) -> 手机 adbd -> shell 权限
"""
import http.server
import json
import os
import re
import socketserver
import subprocess
import sys
import urllib.parse

PORT = int(os.environ.get("PORT", "8088"))
ADB = os.environ.get("ADB", "adb")
# Termux 内的 adb 会把本机 adbd 误识别成 emulator-5554（自动扫描 5554-5585 端口），
# 导致 "more than one device"。必须固定设备序列号。
DEVICE = os.environ.get("ADB_DEVICE", "127.0.0.1:5555")

APP_NAMES = {
    "com.tencent.mm": "微信",
    "com.tencent.mobileqq": "QQ",
    "com.tencent.wework": "企业微信",
    "com.alibaba.android.rimet": "钉钉",
    "com.eg.android.AlipayGphone": "支付宝",
    "com.taobao.taobao": "淘宝",
    "com.taobao.idlefish": "闲鱼",
    "com.taobao.trip": "飞猪",
    "com.taobao.etao": "一淘",
    "com.jingdong.app.mall": "京东",
    "com.xunmeng.pinduoduo": "拼多多",
    "com.achievo.vipshop": "唯品会",
    "com.sankuai.meituan": "美团",
    "com.sankuai.meituan.takeoutnew": "美团外卖",
    "com.cainiao.wireless": "菜鸟",
    "com.tencent.qqlive": "腾讯视频",
    "com.qiyi.video": "爱奇艺",
    "com.youku.phone": "优酷",
    "tv.danmaku.bili": "哔哩哔哩",
    "com.ss.android.ugc.aweme": "抖音",
    "com.ss.android.ugc.aweme.lite": "抖音极速版",
    "com.smile.gifmaker": "快手",
    "com.kuaishou.nebula": "快手极速版",
    "com.xingin.xhs": "小红书",
    "com.zhihu.android": "知乎",
    "com.sina.weibo": "微博",
    "com.netease.cloudmusic": "网易云音乐",
    "com.tencent.qqmusic": "QQ音乐",
    "com.spotify.music": "Spotify",
    "com.google.android.gms": "Google 服务",
    "com.android.vending": "Google Play",
    "com.whatsapp": "WhatsApp",
    "com.facebook.katana": "Facebook",
    "com.facebook.orca": "Messenger",
    "com.instagram.android": "Instagram",
    "com.autonavi.minimap": "高德地图",
    "com.baidu.BaiduMap": "百度地图",
    "com.dragon.read": "番茄小说",
    "com.mybank.android.phone": "网商银行",
    "com.antfortune.wealth": "蚂蚁财富",
    "com.unionpay": "云闪付",
    "com.carriez.flutter_hbb": "RustDesk",
    "com.v2ray.ang": "v2rayNG",
    "org.jellyfin.mobile": "Jellyfin",
    "com.microsoft.office.outlook": "Outlook",
    "com.tencent.ima": "腾讯ima",
    "com.xiaomi.market": "小米应用商店",
    "com.xiaomi.smarthome": "米家",
    "com.mi.health": "小米运动健康",
    "com.miui.home": "系统桌面",
    "com.miui.gallery": "相册",
    "com.miui.securitycenter": "安全中心",
    "com.miui.powerkeeper": "电量与性能",
    "com.miui.voiceassist": "小爱同学",
    "com.miui.personalassistant": "智能助理",
    "com.milink.service": "小米互联",
    "com.xiaomi.aicr": "AI 认知服务",
    "com.xiaomi.mi_connect_service": "小米妙享",
    "com.miui.analytics": "系统分析",
    "com.miui.hybrid": "快应用框架",
    "com.termux": "Termux",
}


def adb(args, timeout=90):
    cmd = [ADB]
    if DEVICE:
        cmd += ["-s", DEVICE]
    cmd += args
    try:
        p = subprocess.run(
            cmd, capture_output=True, text=True,
            timeout=timeout, encoding="utf-8", errors="replace",
        )
        return p.stdout or ""
    except Exception as e:
        return "ERR:" + str(e)


def adb_state():
    """检测 adb 通道是否可用，返回 (ok, state, msg)。

    调试关闭 / 手机重启 / 授权失效都会让这里失败。缺这条检查时，
    所有 adb 命令静默返回空串，界面表现为「0 个应用、电量 0%」——
    用户会误判成数据丢失或程序 bug，而不是「调试没开」。
    """
    out = adb(["devices"], timeout=15)
    if out.startswith("ERR:"):
        return False, "error", "adb 启动失败：" + out[4:][:80]
    for line in out.splitlines():
        parts = line.split()
        if len(parts) < 2 or parts[0] != DEVICE:
            continue
        st = parts[1]
        if st == "device":
            return True, "ok", ""
        if st == "unauthorized":
            return False, "unauthorized", "adb 授权失效"
        if st == "offline":
            return False, "offline", "设备离线"
        return False, st, "设备状态异常：" + st
    return False, "disconnected", "未连接 " + DEVICE


def pkgs(extra):
    out = adb(["shell", "pm", "list", "packages"] + extra)
    s = set()
    for line in out.splitlines():
        line = line.strip()
        if line.startswith("package:"):
            s.add(line[8:].strip())
    return s


def get_pss():
    """解析 dumpsys meminfo 的 Total PSS by process，返回 {包名: PSS_KB}。

    同一应用的多个进程（如 com.tencent.mm 与 com.tencent.mm:push）会累加。
    """
    out = adb(["shell", "dumpsys", "meminfo"], timeout=120)
    result = {}
    inside = False
    for line in out.splitlines():
        s = line.strip()
        if s.startswith("Total PSS by process:"):
            inside = True
            continue
        if not inside:
            continue
        if s.startswith("Total PSS by OOM") or s.startswith("Total PSS by category"):
            break
        if not s:
            continue
        m = re.match(r"^([\d,]+)K:\s+([A-Za-z0-9_.]+)", s)
        if not m:
            continue
        kb = int(m.group(1).replace(",", ""))
        name = m.group(2)
        base = name.split(":")[0]
        result[base] = result.get(base, 0) + kb
    return result


def get_mem_info():
    """返回 (MemTotal_kB, MemAvailable_kB)。"""
    out = adb(["shell", "cat /proc/meminfo"], timeout=30)
    total = 0
    avail = 0
    for line in out.splitlines():
        m = re.match(r"MemTotal:\s+(\d+)\s+kB", line)
        if m:
            total = int(m.group(1))
            continue
        m = re.match(r"MemAvailable:\s+(\d+)\s+kB", line)
        if m:
            avail = int(m.group(1))
    return total, avail


def get_disabled_archived():
    """被「我们」用 disable-user 归档的应用（数据保留）。

    pm list packages -d 会把系统出厂就禁用的应用（如 devicelockcontroller）也列出，
    那不是我们归档的。用 enabled 状态区分：
      enabled=3 (DISABLED_USER) —— pm disable-user 设置，是我们做的
      enabled=2 / 4             —— 系统默认禁用，不应显示为已归档
    -d 集合通常只有个位数，逐个查代价可接受。
    """
    s = set()
    out = adb(["shell", "pm list packages -d --user", "0"])
    for line in out.splitlines():
        line = line.strip()
        if not line.startswith("package:"):
            continue
        pkg = line[8:].strip()
        st = adb(["shell",
                  "dumpsys package %s | grep -m1 -oE 'enabled=[0-9]+'" % pkg])
        if "enabled=3" in st:
            s.add(pkg)
    return s


def get_restricted():
    """返回被设置 RUN_IN_BACKGROUND=ignore 的包名集合（一次命令批量查）。"""
    out = adb(["shell", "cmd", "appops", "query-op", "RUN_IN_BACKGROUND", "ignore"],
              timeout=30)
    s = set()
    for line in out.splitlines():
        line = line.strip()
        if line and "." in line and " " not in line:
            s.add(line)
    return s


# 电池设计容量（mAh）。Redmi Note 12 Turbo = 5000。
# 若换机型，改这里或设环境变量 BATTERY_DESIGN_MAH。
BATTERY_DESIGN_MAH = int(os.environ.get("BATTERY_DESIGN_MAH", "5000"))


# Android BatteryManager 的健康状态枚举
HEALTH_TEXT = {
    1: "未知",
    2: "良好",
    3: "过热",
    4: "损坏",
    5: "过压",
    6: "故障",
    7: "过冷",
}


def get_battery():
    """从 dumpsys battery 读取电量 / 健康度 / 温度。

    /sys/class/power_supply/battery/ 下的 charge_full 与 uevent 被 SELinux
    拒绝（Permission denied），因此健康度改用「满电时的 Charge counter
    ÷ 设计容量」估算，仅在 level >= 99 时可信。
    """
    out = adb(["shell", "dumpsys", "battery"], timeout=20)
    info = {}
    for line in out.splitlines():
        if ":" in line:
            k, v = line.split(":", 1)
            info[k.strip()] = v.strip()

    def num(key, div=1.0):
        try:
            return float(info.get(key, 0)) / div
        except Exception:
            return 0.0

    level = int(num("level"))
    health = int(num("health"))
    temp = round(num("temperature", 10.0), 1)
    charge = int(num("Charge counter"))

    health_pct = 0
    if charge > 0 and level >= 99 and BATTERY_DESIGN_MAH > 0:
        health_pct = int(round(charge * 100.0 / BATTERY_DESIGN_MAH))

    return {
        "level": level,
        "health": health,
        "health_text": HEALTH_TEXT.get(health, "未知"),
        "health_pct": health_pct,
        "temp": temp,
        "charge": charge,
    }


def get_apps(with_pss=False):
    active = pkgs(["--user", "0"])
    allp = pkgs(["-u", "--user", "0"]) or active
    sysp = pkgs(["-s"])
    pss_map = get_pss() if with_pss else {}
    restricted = get_restricted()
    disabled = get_disabled_archived()
    apps = []
    for pkg in sorted(allp):
        apps.append({
            "pkg": pkg,
            "label": APP_NAMES.get(pkg, pkg),
            # 两种归档状态都认：disable-user（数据保留）与旧的 uninstall --user 0
            "frozen": (pkg in disabled) or (pkg not in active),
            "system": pkg in sysp,
            "pss": pss_map.get(pkg, 0),
            "restricted": pkg in restricted,
        })
    # 服务端统一用默认排序；内存 / 名称排序由前端完成。
    # 顺序：已归档 > 已限制 > 其他；组内：有中文名 > 第三方 > 系统 > 名称
    def rank(a):
        if a["frozen"]:
            return 0
        if a["restricted"]:
            return 1
        return 2

    apps.sort(key=lambda a: (
        rank(a),
        0 if a["label"] != a["pkg"] else 1,
        1 if a["system"] else 0,
        a["label"].lower(),
    ))
    return apps, len(active), len(allp)


def do_batch(items, action):
    ok = 0
    fail = []
    # 释放要区分「被禁用」与「已卸载当前用户」两种归档方式，先各取一次全集。
    active = disabled = None
    if action == "unfreeze":
        active = pkgs(["--user", "0"])
        disabled = get_disabled_archived()
    for p in items:
        if action == "freeze":
            # 优先 disable-user：只改启用状态，APK 与用户数据完整保留。
            # 不能用 uninstall --user 0 直接归档 —— 对用户安装的应用等于彻底卸载，
            # 数据会丢且 install-existing 无法恢复（已实测）。
            r = adb(["shell", "pm", "disable-user", "--user", "0", p])
            rl = r.lower()
            if ("disabled" in rl) or ("success" in rl):
                good = True
            else:
                # MIUI/HyperOS 拒绝禁用系统应用（Cannot disable system packages），
                # 退回卸载当前用户：APK 在系统分区不受影响、可由「释放」恢复，
                # 但用户数据可能丢失 —— UI 已就此提示。
                r2 = adb(["shell", "pm", "uninstall", "--user", "0", p])
                good = "success" in r2.lower()
        elif action == "unfreeze":
            # 注意：对「已卸载当前用户」的应用，pm enable 会返回
            # "new state: enabled" 却并不真正恢复 —— 必须按状态选命令。
            if disabled is not None and p in disabled:
                r = adb(["shell", "pm", "enable", p])
                rl = r.lower()
                good = ("enabled" in rl) or ("success" in rl)
            elif active is not None and p not in active:
                # 系统应用走 uninstall --user 0 归档的，只能用 install-existing 恢复。
                # 其成功输出是 "Package com.xxx installed for user: 0"，不含 success。
                r = adb(["shell", "cmd", "package", "install-existing", p])
                rl = r.lower()
                good = ("success" in rl) or ("installed for user" in rl)
            else:
                good = True   # 本来就在启用状态
        elif action == "restrict":
            # 禁止后台运行 + 放入 restricted 待机桶
            adb(["shell", "cmd", "appops", "set", p, "RUN_IN_BACKGROUND", "ignore"])
            adb(["shell", "am", "set-standby-bucket", p, "restricted"])
            chk = adb(["shell", "cmd", "appops", "get", p, "RUN_IN_BACKGROUND"])
            good = "ignore" in chk
        elif action == "unrestrict":
            adb(["shell", "cmd", "appops", "set", p, "RUN_IN_BACKGROUND", "allow"])
            adb(["shell", "am", "set-standby-bucket", p, "rare"])
            chk = adb(["shell", "cmd", "appops", "get", p, "RUN_IN_BACKGROUND"])
            good = "allow" in chk
        else:
            good = False
        if good:
            ok += 1
        else:
            fail.append(p)
    return ok, fail


HTML = r'''<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<meta name="theme-color" content="#0f6e56">
<title>Mine App</title>
<script>
/* 首帧前先套主题，避免闪白 */
try{
  var _t = localStorage.getItem('theme') || 'auto';
  var _d = _t === 'dark' || (_t === 'auto' && window.matchMedia('(prefers-color-scheme: dark)').matches);
  if (_d) document.documentElement.className = 'dark';
}catch(e){}
</script>
<style>
:root{--bg:#faf9f6;--card:#fff;--ink:#1c1c1a;--ink2:#5f5e5a;--ink3:#888780;--line:#e3e1da;
--track:#dcdad3;
--red:#a32d2d;--redbg:#fcebeb;--green:#3b6d11;--greenbg:#eaf3de;--blue:#185fa5;--bluebg:#e6f1fb}
/* 主题：html.dark 由设置页切换；未设置时跟随系统 */
html.dark{--bg:#141413;--card:#1e1e1c;--ink:#f0efe8;--ink2:#a8a69e;
--ink3:#7a7870;--line:#33322e;--track:#3b3a36;--redbg:#3a1f1f;--greenbg:#25301a;--bluebg:#17293a}
html.dark li.restricted{background:#2e2a1c}
html.dark .tag.f{background:#4a2222;border-color:#6b3030}
html.dark .tag.w{background:#3a2a12;color:#fac775}
*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
html,body{margin:0;padding:0;height:100%}
body{background:var(--bg);color:var(--ink);font-family:system-ui,-apple-system,"PingFang SC","Microsoft YaHei",sans-serif;
font-size:17px;line-height:1.55;display:flex;flex-direction:column;overflow:hidden;
touch-action:manipulation;-webkit-text-size-adjust:100%}
header{position:relative;flex:none;z-index:10;background:var(--bg);padding:calc(10px + env(safe-area-inset-top)) 16px 8px;
border-bottom:0.5px solid var(--line)}
.hd{display:flex;align-items:center;gap:8px;margin-bottom:3px}
h1{font-size:19px;font-weight:600;margin:0;letter-spacing:-.01em}
#stat{font-size:11.5px;color:var(--ink3);white-space:nowrap}
.subline{display:flex;align-items:baseline;gap:10px;margin:0 0 9px}
.banner{display:flex;flex-direction:column;gap:3px;margin:0 0 10px;padding:9px 12px;
border-radius:10px;background:var(--redbg);border:0.5px solid #eec2c2;line-height:1.4}
.banner b{font-size:13px;font-weight:600;color:var(--red)}
.banner .fix{font-size:11.5px;color:var(--ink2);font-family:ui-monospace,monospace;word-break:break-all}
.banner .guide{font-size:12.5px;color:#185fa5;text-decoration:underline;margin-top:1px}
#battery:active{opacity:.55}
.info-title{font-size:15px;font-weight:600;color:var(--ink);margin:0 0 10px}
.info-t{font-size:13px;color:var(--ink2);line-height:1.7;margin-bottom:10px}
.info-kv{display:flex;justify-content:space-between;align-items:baseline;font-size:13px;
padding:7px 0;border-bottom:0.5px solid var(--line);color:var(--ink2)}
.info-kv b{color:var(--ink);font-weight:600}
.info-sub{font-size:11.5px;color:var(--ink3);margin-left:6px}
.info-step{display:flex;gap:9px;font-size:13px;color:var(--ink2);line-height:1.6;margin-bottom:9px}
.info-step b:first-child{flex:none;width:19px;height:19px;border-radius:50%;background:var(--bg);
border:0.5px solid var(--line);color:var(--ink);font-size:11.5px;font-weight:600;
display:flex;align-items:center;justify-content:center;margin-top:1px}
.info-step code{background:var(--bg);padding:1px 6px;border-radius:5px;
font-family:ui-monospace,monospace;font-size:12px;color:var(--ink)}
.info-note{font-size:12px;color:var(--ink3);line-height:1.65;margin-top:12px;
padding-top:10px;border-top:0.5px solid var(--line)}
.refresh{flex:none;width:36px;height:36px;min-height:36px;padding:0;border-radius:50%;
background:var(--card);border:0.5px solid var(--line);color:var(--ink2);
font-size:17px;line-height:1;display:flex;align-items:center;justify-content:center;margin-left:auto}
.refresh:active{background:var(--bg)}
.sw-text{font-size:11.5px;color:var(--ink3);flex:none}
.sw-group{display:flex;align-items:center;gap:5px;margin-left:10px;flex:none}
.bolt{flex:none;width:10px;height:14px;fill:#f0a020;display:block}
#btnTune{display:flex;align-items:center;gap:3px;padding:6px 2px;border-radius:8px}
#btnTune:active{background:var(--bg);opacity:.7}
.sheet{position:fixed;inset:0;background:var(--bg);z-index:50;display:none;flex-direction:column}
.sheet.show{display:flex}
/* 设置页走「侧滑翻页」而不是弹出：右滑从左侧推入，左滑退回主界面 */
#settings{display:flex;visibility:hidden;transform:translateX(-100%);
transition:transform .26s cubic-bezier(.22,.8,.28,1),visibility .26s;
box-shadow:2px 0 18px rgba(0,0,0,.10)}
#settings.show{visibility:visible;transform:translateX(0)}
.sheet-hd{display:flex;align-items:center;padding:calc(14px + env(safe-area-inset-top)) 16px 10px;
border-bottom:0.5px solid var(--line)}
.sheet-hd h2{font-size:17px;font-weight:600;margin:0}
.sheet-hd .close{margin-left:auto;width:34px;height:34px;min-height:34px;padding:0;border-radius:50%;
background:var(--card);border:0.5px solid var(--line);font-size:18px;color:var(--ink2)}
.hd-save{margin-left:auto;min-height:34px;padding:0 15px;border-radius:9px;font-size:15px;
font-weight:600;color:#fff;background:#0f6e56}
.hd-save:active{opacity:.85}
#milletClose{margin-left:4px}
.sheet-body{flex:1;overflow-y:auto;padding:12px 16px calc(24px + env(safe-area-inset-bottom))}
.trow{display:flex;align-items:center;gap:12px;padding:13px 0;border-bottom:0.5px solid var(--line)}
.trow .tl{font-size:15px;color:var(--ink)}
.trow .ts{font-size:11.5px;color:var(--ink3);margin-top:3px}
.trow.disabled{opacity:.45}
.trow .tv{font-size:13px;color:var(--ink3);font-family:ui-monospace,monospace}
.tsect{font-size:12px;color:var(--ink3);margin:18px 0 2px}
.edit{flex:none;font-size:13px;min-height:30px;padding:0 14px;border-radius:8px;
background:var(--card);border:0.5px solid var(--line);color:var(--ink2)}
.sheet-ft{padding:10px 16px calc(12px + env(safe-area-inset-bottom));border-top:0.5px solid var(--line)}
.sheet-ft button{width:100%;min-height:42px;font-size:16px}
.switch{position:relative;display:inline-block;width:38px;height:22px;flex:none}
.switch input{display:none}
.switch .slider{position:absolute;inset:0;background:var(--line);border-radius:22px;
transition:background .18s}
.switch .slider:before{content:"";position:absolute;width:18px;height:18px;left:2px;top:2px;
background:#fff;border-radius:50%;transition:transform .18s;box-shadow:0 1px 2px rgba(0,0,0,.18)}
.switch input:checked + .slider{background:#0f6e56}
.switch input:checked + .slider:before{transform:translateX(16px)}
.refresh:disabled{opacity:.4}
input[type=search]{width:100%;padding:11px 14px;font-size:13px;border:0.5px solid var(--line);
border-radius:12px;background:var(--card);color:var(--ink);outline:none;-webkit-appearance:none}
input[type=search]:focus{border-color:var(--blue)}
ul{list-style:none;margin:0;padding:0}
li{display:flex;align-items:center;gap:14px;padding:15px 18px;min-height:68px;border-bottom:0.5px solid var(--line);
background:var(--card);cursor:pointer}
li:active{background:var(--bg)}
li.frozen{background:var(--redbg)}
li.restricted{background:#fdf8ec}
.chk{flex:none;width:28px;height:28px;border-radius:8px;border:1.5px solid var(--ink3);
display:flex;align-items:center;justify-content:center;font-size:14px;color:#fff;transition:background .12s}
li.on .chk{background:var(--blue);border-color:var(--blue)}
.meta{flex:1;min-width:0}
.name{display:flex;align-items:center;gap:7px;font-size:17px;min-width:0}
.name .nm{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.pkg{font-size:12.5px;color:var(--ink3);font-family:ui-monospace,monospace;white-space:nowrap;
overflow:hidden;text-overflow:ellipsis;margin-top:1px}
.mem{position:relative;font-size:12px;color:var(--ink2);margin-top:2px;white-space:nowrap}
/* 文本单独包一层：省略号在这里做，.mem 本身不裁切，绿点才能画到左侧空隙里 */
.mem-t{display:block;overflow:hidden;text-overflow:ellipsis}
.mem .ago{color:var(--ink3);font-family:ui-monospace,monospace}
.msearch{padding:10px 16px 0}
.tag{flex:none;font-size:12px;padding:4px 10px;border-radius:20px;background:var(--bg);color:var(--ink3)}
.tag.f{background:#f9dcdc;color:var(--red);border:0.5px solid #eec2c2}
.rt{flex:none;display:flex;flex-direction:column;align-items:flex-end;gap:5px}
/* AOT 开关：已预编译 = 绿色划开；编译中 = 轨道变进度条 */
.aot-sw{flex:none;position:relative;display:block;width:44px;height:26px;border-radius:13px;
background:var(--track);transition:background .16s;overflow:hidden}
.aot-sw::after{content:'';position:absolute;top:3px;left:3px;width:20px;height:20px;border-radius:50%;
background:#fff;box-shadow:0 1px 3px rgba(0,0,0,.22);transition:transform .16s ease,opacity .12s}
.aot-sw.on{background:#0f6e56}
.aot-sw.on::after{transform:translateX(18px)}
.aot-sw .fill{position:absolute;left:0;top:0;bottom:0;width:0;background:#0f6e56;opacity:.5;
transition:width .2s linear}
.aot-sw.busy::after{transform:translateX(18px);opacity:0}
/* 编译中：百分比画在轨道内部，不占用左侧 AOT 标签的位置 */
.aot-sw .pct{position:absolute;left:0;right:0;top:0;bottom:0;display:none;
align-items:center;justify-content:center;font-size:10px;font-weight:600;
color:#0b3b2e;font-family:ui-monospace,monospace;letter-spacing:-.4px;line-height:1}
.aot-sw.busy .pct{display:flex}
/* 活跃指示：有内存占用、未被限制/归档、且还有非缓存进程 */
/* 绿点绝对定位到文字列左边的空隙里（li 的 gap=14px），
   完全不参与文本流 —— 所以内存数字和上一行的应用名左边缘对齐 */
.dot-live{position:absolute;left:-11px;top:50%;margin-top:-3px;
width:6px;height:6px;border-radius:50%;background:#12b76a;
box-shadow:0 0 0 2px rgba(18,183,106,.16);
animation:dotPulse 2.2s ease-in-out infinite}
@keyframes dotPulse{0%,100%{opacity:.35;transform:scale(.78)}50%{opacity:1;transform:scale(1)}}
@media (prefers-reduced-motion:reduce){.dot-live{animation:none}}
/* 单应用清理：只清后台进程（am kill），不动自启动 */
.kill-x{flex:none;width:19px;height:19px;line-height:17px;text-align:center;border-radius:50%;
font-size:14px;color:var(--ink3);border:1px solid var(--track);cursor:pointer;
user-select:none;-webkit-tap-highlight-color:transparent;transition:background .12s,color .12s}
.kill-x:active{background:var(--track);color:var(--ink)}
/* 设置页外链：跳到浏览器打开，不在 WebView 里替换界面 */
.ext-row{display:flex;align-items:center;gap:8px;padding:2px 0 10px;font-size:13px}
.ext{color:#0f6e56;text-decoration:none;padding:6px 2px;border-radius:6px}
.ext:active{background:var(--track)}
.ext-sep{color:var(--ink3);font-size:12px}
.ext-note{font-size:11.5px;color:var(--ink3);line-height:1.6;padding:0 0 10px}
.refresh{transform-origin:50% 50%;will-change:transform}
/* 用对称 SVG 代替 ↻ 字符：字符的字形墨迹不在 em 框正中，绕中心旋转会看出偏心摇晃 */
.refresh svg{display:block}
.refresh.spinning{color:#0f6e56}
.aot-lb{flex:none;font-size:11px;color:var(--ink3);font-family:ui-monospace,monospace;
min-width:22px;text-align:right}
.rt-top{display:flex;align-items:center;gap:7px}
.tag.sys{flex:none;background:var(--bluebg);color:var(--blue);font-size:10px;
padding:1px 6px;border-radius:5px;line-height:1.6;font-weight:400}
footer{position:relative;flex:none;z-index:20;background:var(--card);
border-top:0.5px solid var(--line);padding:12px 18px calc(12px + env(safe-area-inset-bottom));
display:flex;flex-direction:column;gap:10px}
#list{flex:1;min-height:0;overflow-y:auto;-webkit-overflow-scrolling:touch;overscroll-behavior:contain}
#empty{flex:none}
#info{display:flex;align-items:center;gap:8px;font-size:13px;color:var(--ink2);line-height:1.4}
#info .i-sel{flex:none}
#info .i-mem{margin-left:auto;flex:none;color:var(--ink3);text-align:right}
#info .i-mem b{color:var(--ink2)}
#battery{flex:1;font-size:11.5px;color:var(--ink3);line-height:1.4}
.btns{display:flex;gap:8px}
.btns button{flex:1;padding:0 6px;font-size:16px;min-height:36px}
#info b{color:var(--ink);font-weight:600}
#clearSel{color:#185fa5;margin-left:8px;font-size:12px;text-decoration:underline}
.mfoot{flex:none;display:flex;align-items:center;gap:10px;background:var(--card);
border-top:0.5px solid var(--line);padding:10px 18px calc(10px + env(safe-area-inset-bottom))}
#milletSel{flex:none;font-size:13px;color:var(--ink2);line-height:1.4}
#milletSel b{color:var(--ink);font-weight:600}
#milletMem{margin-left:auto;flex:none;font-size:13px;color:var(--ink3);
line-height:1.4;text-align:right}
#milletMem b{color:var(--ink2);font-weight:600}
.mclear{flex:none;min-height:34px;padding:0 14px;font-size:14px;font-weight:400;
border-radius:10px;background:var(--bg);color:var(--ink);border:0.5px solid var(--line)}
button{border:0;border-radius:12px;padding:14px 20px;font-size:16px;font-weight:500;min-height:52px;
font-family:inherit;cursor:pointer;white-space:nowrap}
button:disabled{opacity:.45}
.primary{background:#0f6e56;color:#fff}
.ghost{background:var(--bg);color:var(--ink);border:0.5px solid var(--line)}
#toast{position:fixed;left:50%;bottom:116px;transform:translateX(-50%) translateY(10px);
background:#2c2c2a;color:#fff;padding:11px 20px;border-radius:20px;font-size:14px;opacity:0;
transition:opacity .2s,transform .2s;pointer-events:none;z-index:40;max-width:80vw;text-align:center}
#toast.show{opacity:1;transform:translateX(-50%) translateY(0)}
.empty{padding:60px 20px;text-align:center;color:var(--ink3);font-size:16px}
.sortbar{display:flex;gap:8px;margin-top:8px}
.sec{font-size:12px;color:var(--ink3);margin:20px 0 2px}
#appTitle{cursor:pointer;display:flex;align-items:center;gap:5px;min-width:0}
#appNameTx{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
/* 三处 ⓘ 提示统一用同一个字符字形，保证视觉大小完全一致（11.5px = 电池行/性能模式行字号） */
.i-dot{flex:none;font-style:normal;font-size:11.5px;line-height:1;color:var(--ink3);
transition:color .12s}
#appTitle:active .i-dot{color:var(--ink2)}
.tip-i{font-style:normal}
body.no-info .tip-i{display:none}
/* 设置页的图标选择器 */
.icon-grid{display:grid;grid-template-columns:repeat(3,1fr);gap:10px;margin-top:8px}
.icon-cell{position:relative;display:flex;flex-direction:column;align-items:center;gap:5px;
padding:10px 4px 8px;border-radius:12px;background:var(--card);border:1.5px solid var(--line)}
.icon-cell img{width:52px;height:52px;border-radius:12px;display:block;
box-shadow:0 0 0 0.5px rgba(0,0,0,.12)}
.icon-cell span{font-size:11px;color:var(--ink3);line-height:1}
.icon-cell.on{border-color:#0f6e56}
.icon-cell.on span{color:#0f6e56;font-weight:600}
.seg{flex:1;background:var(--card);border:0.5px solid var(--line);border-radius:10px;
padding:0;font-size:16px;color:var(--ink2);min-height:36px;font-weight:400}
.seg.on{background:#0f6e56;color:#fff;border-color:#0f6e56;font-weight:500}
.tag.w{background:#faeeda;color:#854f0b}
.modal{position:fixed;inset:0;background:rgba(0,0,0,.45);display:none;
align-items:center;justify-content:center;padding:24px;z-index:60}
.modal.show{display:flex}
.modal-box{background:var(--card);border-radius:16px;padding:22px 20px 18px;
width:100%;max-width:340px;box-shadow:0 8px 32px rgba(0,0,0,.18)}
#modal-msg{font-size:16px;line-height:1.6;margin:0 0 20px;white-space:pre-wrap;color:var(--ink)}
.modal-opt{display:none;align-items:center;gap:7px;margin:-2px 0 14px;font-size:12px;
color:var(--ink3);cursor:pointer;user-select:none}
.modal-opt input{width:15px;height:15px;accent-color:#0f6e56;margin:0}
.modal-btns{display:flex;gap:10px}
/* 启动风险提示：比普通弹窗更高一层，且不允许点遮罩关闭 */
.risk{position:fixed;inset:0;background:rgba(0,0,0,.5);display:none;
align-items:center;justify-content:center;padding:22px;z-index:90}
.risk.show{display:flex}
.risk-box{background:var(--card);border-radius:16px;padding:22px 20px 18px;
width:100%;max-width:340px;box-shadow:0 8px 32px rgba(0,0,0,.22)}
.risk-box h3{font-size:16px;margin:0 0 10px;color:var(--ink)}
.risk-box p{font-size:13.5px;line-height:1.65;color:var(--ink2);margin:0 0 10px}
.risk-link{display:inline-block;font-size:13px;color:#0f6e56;text-decoration:none;
padding:2px 0 10px}
.risk-foot{font-size:12px;color:var(--ink3);line-height:1.6;margin:2px 0 16px;
padding-top:10px;border-top:0.5px solid var(--line)}
.risk-btns{display:flex;gap:10px}
.risk-btns button{flex:1}
.modal-btns button{flex:1}
</style>
</head>
<body>
<header>
  <div class="hd"><h1 id="appTitle"><span id="appNameTx">Mine App</span><i class="i-dot tip-i" aria-hidden="true">ⓘ</i></h1>
    <span class="sw-group">
      <svg class="bolt" viewBox="0 0 10 14" aria-hidden="true"><path d="M6 0 L0 8 H4 L3 14 L10 5.5 H6 Z"/></svg>
      <label class="switch"><input type="checkbox" id="perfChk"><span class="slider"></span></label>
    </span>
    <span class="sw-text" id="btnTune" aria-label="tuning">性能模式 <i class="tip-i">ⓘ</i></span>
    <button class="refresh" id="btnRefresh" aria-label="刷新"><svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true"><path fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" d="M4 12a8 8 0 1 0 8-8"/><path fill="currentColor" d="M12 1.3 16.4 4 12 6.7z"/></svg></button></div>
  <div class="subline"><span id="battery"></span><span id="stat">加载中…</span></div>
  <div class="banner" id="banner" style="display:none"></div>
  <input id="q" type="search" data-tp="searchPh" placeholder="搜索应用名或包名，点下方按钮切换排序" autocomplete="off">
  <div class="sortbar" id="sortbar">
    <button class="seg on" data-sort="default" data-t="sortDefault">默认</button>
    <button class="seg" data-sort="mem" data-t="sortMem">运存</button>
    <button class="seg" data-sort="idle" data-t="sortIdle">闲置</button>
    <button class="seg" data-sort="name" data-t="sortName">名称</button>
  </div>
</header>
<ul id="list"></ul>
<div class="empty" id="empty" data-t="empty" style="display:none">没有匹配的应用</div>
<footer>
  <div id="info"></div>
  <div class="btns">
    <button class="ghost" id="btnFreezeSel" data-t="archive">归档</button>
    <button class="ghost" id="btnUnfreeze" data-t="release">还原</button>
    <button class="ghost" id="btnRestrict" data-t="restrict">限制</button>
    <button class="ghost" id="btnUnrestrict" data-t="unrestrict">解限</button>
  </div>
</footer>
<div id="tune" class="sheet">
  <div class="sheet-hd"><h2 data-t="tuneTitle">性能调优</h2><button class="close" id="tuneClose">×</button></div>
  <div class="sheet-body" id="tuneBody"></div>
</div>
<div id="millet" class="sheet">
  <div class="sheet-hd"><h2 data-t="milletTitle">Millet 白名单</h2>
    <button class="hd-save" id="milletSave" data-t="save">保存</button>
    <button class="close" id="milletClose">×</button></div>
  <div class="msearch"><input type="search" id="mq" data-tp="searchPh" placeholder="搜索应用名或包名"></div>
  <div class="sortbar" id="milletSort" style="margin:8px 16px 0">
    <button class="seg on" data-msort="default" data-t="sortDefault">默认</button>
    <button class="seg" data-msort="mem" data-t="sortMem">运存</button>
    <button class="seg" data-msort="idle" data-t="sortIdle">闲置</button>
    <button class="seg" data-msort="name" data-t="sortName">名称</button>
    <button class="seg" data-msort="picked" data-t="picked">已选</button>
  </div>
  <ul id="milletList" style="flex:1;overflow-y:auto;margin:8px 0 0"></ul>
  <div class="mfoot">
    <span id="milletSel"></span>
    <button class="mclear" id="milletClear" data-t="clearSel">清空</button>
    <span id="milletMem"></span>
  </div>
</div>
<div id="settings" class="sheet">
  <div class="sheet-hd"><h2 data-t="settings">设置</h2><button class="close" id="setClose">×</button></div>
  <div class="sheet-body">
    <div class="sec" data-t="secName">应用名称</div>
    <div class="sortbar" id="nameBar">
      <button class="seg" data-name="manager">应用管理器</button>
      <button class="seg" data-name="mine">Mine App</button>
    </div>
    <div class="sec" data-t="secTheme">主题</div>
    <div class="sortbar" id="themeBar">
      <button class="seg" data-theme="auto" data-t="themeAuto">跟随系统</button>
      <button class="seg" data-theme="light" data-t="themeLight">浅色</button>
      <button class="seg" data-theme="dark" data-t="themeDark">深色</button>
    </div>
    <div class="sec" data-t="secLang">语言</div>
    <div class="sortbar" id="langBar">
      <button class="seg" data-lang="auto" data-t="langAuto">跟随系统</button>
      <button class="seg" data-lang="zh" data-t="langZh">中文</button>
      <button class="seg" data-lang="en" data-t="langEn">English</button>
    </div>
    <div class="sec" data-t="secIcon">应用图标</div>
    <div class="icon-grid" id="iconGrid"></div>
    <div class="sec" data-t="secTip">界面提示（ⓘ）</div>
    <div class="sortbar" id="tipBar">
      <button class="seg" data-tip="1" data-t="tipShow">显示</button>
      <button class="seg" data-tip="0" data-t="tipHide">隐藏</button>
    </div>
    <div class="sec" data-t="secAbout">关于</div>
    <div class="ext-row">
      <a class="ext" id="lnkRepo" href="https://github.com/henry-97/MineApp">GitHub GPL-3.0 开源</a>
      <span class="ext-sep">|</span>
      <a class="ext" id="lnkFund" href="https://github.com/henry-97/MineApp#sponsor">打赏</a>
    </div>
    <div class="ext-note" data-t="extLicense">本项目以 GPL-3.0 开源，禁止闭源分发；如有闭源合作需求，可以提 Issue</div>
    <div class="info-note" style="border-top:0"><span data-t="setEntry">左滑主界面或点标题可再次进入本页。</span><br>
      <span data-t="setNameNote">应用名称会同时改变桌面图标的名称。</span></div>
  </div>
</div>
<div id="risk" class="risk show">
  <div class="risk-box">
    <h3 data-t="riskTitle">使用前请留意</h3>
    <p data-t="riskBody">本软件通过 ADB 直接调用系统命令，能力很强。请勿随意冻结或限制系统必要组件（时钟、桌面启动器、输入法等），否则可能导致闹钟不响、桌面异常、收不到来电等。</p>
    <a class="risk-link" id="lnkTerms" href="https://github.com/henry-97/MineApp/blob/main/docs/TERMS.md" data-t="riskLink">查看完整「使用须知」→</a>
    <p class="risk-foot" data-t="riskFoot">继续使用本软件，即表示你自行承担因使用不当造成的风险。</p>
    <div class="risk-btns">
      <button class="ghost" id="riskQuit" data-t="riskQuit">退出</button>
      <button class="primary" id="riskOk" data-t="riskAgree">同意并使用</button>
    </div>
  </div>
</div>
<div id="toast"></div>
<div id="modal" class="modal">
  <div class="modal-box">
    <p id="modal-msg"></p>
    <label class="modal-opt" id="modal-opt"><input type="checkbox" id="modal-opt-cb"><span id="modal-opt-tx"></span></label>
    <div class="modal-btns">
      <button class="ghost" id="modal-cancel" data-t="cancel">取消</button>
      <button class="primary" id="modal-ok" data-t="confirm">确定</button>
    </div>
  </div>
</div>

<script>
var apps = [];
var keep = new Set(JSON.parse(localStorage.getItem('keep') || '[]'));
/* 已由本应用做过 AOT 预编译的包（本地记录；dexopt 全量查询太大，不适合每次刷新跑） */
var aotDone = new Set(JSON.parse(localStorage.getItem('aot') || '[]'));
/* 各应用最后一次前台使用时间（毫秒），供「闲置」排序 */
var idleMap = null;
function saveAot(){ localStorage.setItem('aot', JSON.stringify(Array.from(aotDone))); }
var busy = false;
var sortMode = (function(){
  var s = localStorage.getItem('sortMode');
  return (s === 'mem' || s === 'idle' || s === 'name') ? s : 'default';
})();
var hasPss = false;
var memTotal = 0;
var memPct = 0;
var memAvail = 0;
var adbOk = true;
var lastBattery = null;

/* ---------- 多语言 ---------- */
var I18N = {
  zh: {
    perf: '性能模式',
    searchPh: '搜索应用名或包名，点下方按钮切换排序',
    sortDefault: '默认', sortMem: '运存', sortIdle: '闲置', sortName: '名称',
    archive: '归档', release: '还原', restrict: '限制', unrestrict: '解限',
    empty: '没有匹配的应用', adbOff: '调试未连接', loading: '加载中…', connFail: '连接失败',
    battery: '电池', health: '健康', temp: '℃',
    hGood: '良好', hUnknown: '未知', hOverheat: '过热', hDead: '损坏',
    hOverVolt: '过压', hFailure: '故障', hCold: '过冷',
    statArch: '归档 {a} 个 · 限制 {r} 个',
    infoSel: '已选 <b>{n}</b> · 共 <b>{t}</b>',
    infoMem: '内存 <b>{u}/{g}G</b> ({p}%)',
    memSel: '内存 <b>{u}G</b> ({p}%)',
    clearSel: '清空',
    picked: '已选',
    tagArchived: '已归档', tagRestricted: '已限制', tagSystem: '系统', tagNormal: '正常',
    agoNever: '24h 以上',
    bannerOff: '调试通道未连接', bannerOffHint: '手机重启后需在电脑上执行：adb tcpip 5555',
    bannerUnauth: '等待授权', bannerUnauthHint: '解锁手机，勾选「一律允许使用这台计算机进行调试」再点「允许」',
    waitAuth: '等待手机授权…',
    bannerOffline: '设备离线', bannerOfflineHint: '重新执行一次：adb tcpip 5555',
    bannerErr: '调试通道异常', bannerErrHint: '请按下方指引重新开启',
    bannerGuide: '查看操作指引 ›',
    guideTitle: '开启调试通道',
    guide1: '设置 → 关于手机 → 连点「版本号」7 次，开启开发者选项',
    guide2: '设置 → 更多设置 → 开发者选项 → 打开「USB 调试」',
    guide3: '用数据线连接电脑，在电脑上执行：',
    guide4: '回到本应用，点右上角刷新',
    guide5: '手机弹出「允许 USB 调试吗」时，勾选「一律允许」再点确定',
    guideNote: '以上步骤只需在每次手机重启后重做第 3、5 步；不重启则可长期使用。本应用不需要 Termux、Python 或 root。',
    ok: '知道了', cancel: '取消', confirm: '确定',
    battTitle: '电池健康度 {p}',
    battLevel: '当前电量', battSys: '系统状态',
    battMax: '历史最高', battLast: '最近一次学习', battMin: '历史最低（采用值）',
    battNote: '系统在每次完整充电后，会用实际充入的电量反推电池当时的真实满电容量，记为一个学习值。<br>健康度 = 学习容量 ÷ 设计容量（5000 mAh）。<br>因为电池健康只会下降不会回升，这里取<b>历史最低值 Min</b> 作为依据，避免个别偏高的学习值把结果拉高。'
      + '<br><br><b>关于数值波动</b>：首次检测结果可能滞后或有波动，这是系统的学习机制决定的 —— 它需要若干个完整的充放电周期才能收敛。'
      + '<b>日常使用一周左右</b>即可得到比较稳定的值；刚装好时看到的数字仅供参考，不必在意一两天的跳动。',
    tSaved: '已保存 {n} 个应用', tCompiling: '正在编译，请稍候',
    compiling: '编译中', reverting: '还原中',
    tFail: '操作失败：{m}', tCompiled: '预编译完成 {n}s', tReverted: '已取消预编译 {n}s',
    tAlready: '已经是 AOT 状态，无需重复编译',
    tLowMem: '可用内存只剩 {n}MB，全量编译几乎一定失败，请先清理后台',
    cfmKill: '清理「{n}」的后台进程？仅清理后台，不影响应用自启动。',
    killDontAsk: '下次不再提示',
    tKilled: '已清理「{n}」，释放 {m} MB · 稍后列表自动刷新',
    tKilledZero: '已清理「{n}」，未释放运存 · 稍后列表自动刷新',
    tKillIdle: '「{n}」当前没有后台进程',
    tKillFail: '清理失败：{m}',
    tCleared: '已清空选择', tConnFail: '无法连接服务：{m}', tAdbOff: '调试未连接',
    tPerfNoAdb: '调试未连接，性能模式未生效',
    tPerfOn: '性能模式已开启', tPerfOff: '性能模式已关闭',
    tNameOk: '名称已更新，桌面图标稍后刷新', tNameFail: '已保存，桌面名称未能修改',
    tBatch: '完成 {ok} 个，失败 {fail} 个',
    tuneTitle: '性能调优',
    tunePerf: '性能模式', tunePerfSub: '关闭省电与均衡，优先性能',
    tuneSave: '省电模式', tuneSaveSub: '限制后台活动以延长续航',
    tuneBal: '均衡模式', tuneBalSub: '默认的功耗与性能折中',
    tuneGpu: 'GPU 调优', tuneGame: '游戏周期优化',
    milletTitle: 'Millet 白名单', milletSub: '豁免后台限制的应用，当前 {n} 个',
    edit: '编辑', save: '保存',
    rootNote: '以下项目需要 root，当前仅显示',
    rtName: '实时调度 RT', tempLimit: '温度限制阈值', bootMode: '开机默认模式', governor: 'CPU 调度器',
    settings: '设置', secName: '应用名称', secTheme: '主题', secLang: '语言',
    themeAuto: '跟随系统', themeLight: '浅色', themeDark: '深色',
    langAuto: '跟随系统', langZh: '中文', langEn: 'English',
    setNameNote: '应用名称会同时改变桌面图标的名称。',
    perfTip: '性能模式',
    secTip: '界面提示（ⓘ）', tipShow: '显示', tipHide: '隐藏',
    secAbout: '关于', extRepo: 'GitHub GPL-3.0 开源', extFund: '打赏',
    extLicense: '本项目以 GPL-3.0 开源，禁止闭源分发；如有闭源合作需求，可以提 Issue',
    riskTitle: '使用前请留意',
    riskBody: '本软件通过 ADB 直接调用系统命令，能力很强。请勿随意冻结或限制系统必要组件（时钟、桌面启动器、输入法等），否则可能导致闹钟不响、桌面异常、收不到来电等。',
    riskLink: '查看完整「使用须知」→',
    riskFoot: '继续使用本软件，即表示你自行承担因使用不当造成的风险。',
    riskAgree: '同意并使用', riskQuit: '退出',
    secIcon: '应用图标',
    icSq: '方块', icCl: '四叶草', icG: '墨绿', icY: '金色', icK: '黑色',
    tIconOk: '图标已切换，桌面可能需要几秒刷新', tIconFail: '图标切换失败',
    tBusy: '正在处理，请稍候',
    tNoArchivable: '所选应用中没有可归档的',
    tNoArchived: '所选应用中没有已归档的',
    tPickRestrict: '请先勾选要限制的应用',
    tPickUnrestrict: '请先勾选要解除限制的应用',
    cfmArchive: '归档所选的 {n} 个应用？\n\n普通应用只做禁用，数据完整保留；\n系统应用无法禁用，会改为卸载当前用户，其数据可能丢失。',
    cfmRestrict: '将限制 {n} 个应用的后台运行。\n\n效果：切到后台后系统会限制其 CPU、网络与唤醒，但这些应用可能因此收不到消息通知。',
    cfmUnrestrict: '恢复 {n} 个应用的后台运行权限？',
    cfmRelease: '还原 {n} 个应用？',
    running: '正在处理 {n} 个…',
    tDone: '{w}完成：成功 {ok} 个',
    tDoneFail: '，失败 {n} 个',
    tRunFail: '{w}失败：{m}',
    tUnknown: '未知错误',
    tAdbNoRun: '调试未连接，操作未执行',
    setEntry: '主界面从左往右滑、或点标题，进入本页；向左滑返回。'
  },
  en: {
    perf: 'Performance',
    searchPh: 'Search app or package, tap below to sort',
    sortDefault: 'Default', sortMem: 'RAM', sortIdle: 'Idle', sortName: 'Name',
    archive: 'Archive', release: 'Restore', restrict: 'Restrict', unrestrict: 'Unrestrict',
    empty: 'No matching apps', adbOff: 'ADB off', loading: 'Loading…', connFail: 'Failed',
    battery: 'Battery', health: 'Health ', temp: '℃',
    hGood: 'Good', hUnknown: 'Unknown', hOverheat: 'Overheat', hDead: 'Dead',
    hOverVolt: 'Over volt', hFailure: 'Failure', hCold: 'Cold',
    statArch: '{a} archived · {r} restricted',
    infoSel: 'Selected <b>{n}</b> · Total <b>{t}</b>',
    infoMem: 'RAM <b>{u}/{g}G</b> ({p}%)',
    memSel: 'RAM <b>{u}G</b> ({p}%)',
    clearSel: 'Clear',
    picked: 'Picked',
    tagArchived: 'Archived', tagRestricted: 'Restricted', tagSystem: 'System', tagNormal: 'Normal',
    agoNever: '>24h',
    bannerOff: 'ADB channel not connected', bannerOffHint: 'After reboot, run on a PC: adb tcpip 5555',
    bannerUnauth: 'Waiting for authorization', bannerUnauthHint: 'Unlock the phone, tick "Always allow from this computer" then tap Allow',
    waitAuth: 'Waiting for phone authorization…',
    bannerOffline: 'Device offline', bannerOfflineHint: 'Run again: adb tcpip 5555',
    bannerErr: 'ADB channel error', bannerErrHint: 'Follow the guide below to re-enable',
    bannerGuide: 'Setup guide ›',
    guideTitle: 'Enable the ADB channel',
    guide1: 'Settings → About phone → tap "Version" 7 times to unlock Developer options',
    guide2: 'Settings → Additional settings → Developer options → turn on "USB debugging"',
    guide3: 'Connect to a PC with a cable, then run:',
    guide4: 'Return to this app and tap refresh at the top right',
    guide5: 'When "Allow USB debugging?" appears, tick "Always allow" and confirm',
    guideNote: 'Steps 3 and 5 are only needed after each reboot. No Termux, Python or root required.',
    ok: 'Got it', cancel: 'Cancel', confirm: 'OK',
    battTitle: 'Battery health {p}',
    battLevel: 'Level', battSys: 'System status',
    battMax: 'All-time high', battLast: 'Latest learned', battMin: 'All-time low (used)',
    battNote: 'After each full charge the system infers the real full capacity from the charge actually delivered, and records it as a learned value.<br>Health = learned capacity ÷ design capacity (5000 mAh).<br>Battery health only decreases, so the <b>all-time low (Min)</b> is used to keep occasional high readings from inflating the result.'
      + '<br><br><b>About fluctuations</b>: the first reading may lag or bounce around. That is how the system learns — it needs a few complete charge cycles to converge. '
      + 'Expect a stable figure after <b>about a week of normal use</b>; right after installing, treat the number as a rough reference and do not worry about day-to-day jitter.',
    tSaved: 'Saved {n} apps', tCompiling: 'Compiling, please wait',
    compiling: 'Compiling', reverting: 'Reverting',
    tFail: 'Failed: {m}', tCompiled: 'Compiled in {n}s', tReverted: 'Reverted in {n}s',
    tAlready: 'Already AOT-compiled, nothing to do',
    tLowMem: 'Only {n}MB free — full compilation will almost certainly fail. Close some apps first.',
    cfmKill: 'Clear background processes of "{n}"? Background only — auto-start is unaffected.',
    killDontAsk: "Don't ask again",
    tKilled: 'Cleared "{n}", freed {m} MB · list refreshes shortly',
    tKilledZero: 'Cleared "{n}", no memory freed · list refreshes shortly',
    tKillIdle: '"{n}" has no background processes right now',
    tKillFail: 'Failed: {m}',
    tCleared: 'Selection cleared', tConnFail: 'Cannot reach service: {m}', tAdbOff: 'ADB not connected',
    tPerfNoAdb: 'ADB not connected, performance mode not applied',
    tPerfOn: 'Performance mode on', tPerfOff: 'Performance mode off',
    tNameOk: 'Name updated, launcher label refreshes shortly', tNameFail: 'Saved, but launcher label could not change',
    tBatch: '{ok} done, {fail} failed',
    tuneTitle: 'Performance tuning',
    tunePerf: 'Performance mode', tunePerfSub: 'Disable battery saver and balanced mode',
    tuneSave: 'Battery saver', tuneSaveSub: 'Limit background activity to extend battery life',
    tuneBal: 'Balanced mode', tuneBalSub: 'Default power/performance trade-off',
    tuneGpu: 'GPU tuning', tuneGame: 'Game periodic tuning',
    milletTitle: 'Millet allowlist', milletSub: 'Apps exempt from background limits · {n}',
    edit: 'Edit', save: 'Save',
    rootNote: 'The following need root and are read-only',
    rtName: 'Real-time scheduling', tempLimit: 'Temperature limits', bootMode: 'Default boot mode', governor: 'CPU governor',
    settings: 'Settings', secName: 'App name', secTheme: 'Theme', secLang: 'Language',
    themeAuto: 'System', themeLight: 'Light', themeDark: 'Dark',
    langAuto: 'System', langZh: '中文', langEn: 'English',
    setNameNote: 'The app name also changes the launcher label.',
    perfTip: 'Performance',
    secTip: 'Hints (ⓘ)', tipShow: 'Show', tipHide: 'Hide',
    secAbout: 'About', extRepo: 'GitHub · GPL-3.0', extFund: 'Sponsor',
    extLicense: 'GPL-3.0 — closed-source distribution not permitted. Need a proprietary licence? Open an issue.',
    riskTitle: 'Before you start',
    riskBody: 'This app drives system commands directly through ADB and is genuinely powerful. Do not freeze or restrict components the system depends on (Clock, launcher, keyboard, …) — alarms, the home screen and incoming calls can break, among other things.',
    riskLink: 'Read the full usage notes →',
    riskFoot: 'By continuing, you accept responsibility for any risk caused by misuse.',
    riskAgree: 'Agree and continue', riskQuit: 'Quit',
    secIcon: 'App icon',
    icSq: 'Blocks', icCl: 'Clover', icG: 'Green', icY: 'Gold', icK: 'Black',
    tIconOk: 'Icon changed; the launcher may take a few seconds', tIconFail: 'Failed to change icon',
    tBusy: 'Working, please wait',
    tNoArchivable: 'None of the selected apps can be archived',
    tNoArchived: 'None of the selected apps is archived',
    tPickRestrict: 'Select apps to restrict first',
    tPickUnrestrict: 'Select apps to unrestrict first',
    cfmArchive: 'Archive {n} selected apps?\n\nNormal apps are only disabled; their data is kept.\nSystem apps cannot be disabled, so the current user is uninstalled and their data may be lost.',
    cfmRestrict: 'Restrict background activity for {n} apps?\n\nThe system will limit their CPU, network and wakeups in the background, so they may stop receiving notifications.',
    cfmUnrestrict: 'Restore background permission for {n} apps?',
    cfmRelease: 'Restore {n} apps?',
    running: 'Working on {n}…',
    tDone: '{w}: {ok} succeeded',
    tDoneFail: ', {n} failed',
    tRunFail: '{w} failed: {m}',
    tUnknown: 'unknown error',
    tAdbNoRun: 'ADB not connected, nothing was executed',
    setEntry: 'Swipe right on the main screen, or tap the title, to open this page; swipe left to go back.'
  }
};

var langMode = (function(){
  var v = localStorage.getItem('lang');
  return (v === 'zh' || v === 'en') ? v : 'auto';
})();

function lang(){
  if (langMode === 'zh' || langMode === 'en') return langMode;
  var n = (navigator.language || 'zh').toLowerCase();
  return n.indexOf('zh') === 0 ? 'zh' : 'en';
}

function T(k){
  var d = I18N[lang()] || I18N.zh;
  var v = d[k];
  if (v == null) v = I18N.zh[k];
  return v == null ? k : v;
}

/* 占位替换：T2('statArch', {a: 3, r: 5}) */
function T2(k, o){
  var s = T(k);
  for (var p in o) if (o.hasOwnProperty(p)) s = s.split('{' + p + '}').join(o[p]);
  return s;
}

function applyLang(){
  document.documentElement.lang = (lang() === 'en' ? 'en' : 'zh-CN');
  var els = document.querySelectorAll('[data-t]');
  for (var i = 0; i < els.length; i++) els[i].textContent = T(els[i].getAttribute('data-t'));
  var bt = document.getElementById('btnTune');
  if (bt) bt.innerHTML = T('perfTip') + ' <i class="tip-i">ⓘ</i>';
  var ph = document.querySelectorAll('[data-tp]');
  for (var j = 0; j < ph.length; j++) ph[j].setAttribute('placeholder', T(ph[j].getAttribute('data-tp')));
  applyExtLinks();
}

/* 设置页的 GitHub / 打赏链接：跟着界面语言走。
   中文 → 仓库首页（默认渲染 README.md）；英文 → README.en.md。
   「打赏与资助」直接跳到 README 里的赞助小节锚点。 */
var GH = 'https://github.com/henry-97/MineApp';
function applyExtLinks(){
  var en = (lang() === 'en');
  var repo = document.getElementById('lnkRepo');
  var fund = document.getElementById('lnkFund');
  if (repo){
    repo.textContent = T('extRepo');
    repo.setAttribute('href', en ? (GH + '/blob/main/README.en.md') : GH);
  }
  if (fund){
    fund.textContent = T('extFund');
    // 直接指向独立的 docs/SPONSOR.md，而不是 README 里的 #赞助 锚点：
    // 实测 GitHub App 完全不认 fragment 锚点（仓库首页和 blob 文件页都停在页首），
    // 而浏览器认。独立页两种客户端都能直接落在赞助内容上。
    fund.setAttribute('href', GH + '/blob/main/docs/SPONSOR.md');
  }
}

/* ---------- 名称与主题（设置页） ---------- */
var APP_NAMES = { manager: '应用管理器', mine: 'Mine App' };
var appName = localStorage.getItem('appName') === 'manager' ? 'manager' : 'mine';
var themeMode = (function(){
  var t = localStorage.getItem('theme');
  return (t === 'light' || t === 'dark') ? t : 'auto';
})();

/* 六套桌面图标（顺序与 tools/gen_icon.py 一致），名称沿用它的 shape_color 命名 */
var ICONS = ['sq_g', 'sq_y', 'sq_k', 'cl_g', 'cl_y', 'cl_k'];
var iconKey = (function(){
  var k = localStorage.getItem('iconKey');
  return ICONS.indexOf(k) >= 0 ? k : 'sq_g';
})();
/* 三处 ⓘ 提示是否显示 */
var showTip = localStorage.getItem('showTip') !== '0';
/* 只有装进 APK 才有 Java 桥；Termux 版没有，图标选择器就不显示 */
var HAS_BRIDGE = (typeof Bridge !== 'undefined');

var lastFrozen = 0, lastRestricted = 0;
function updateStat(){
  var el = document.getElementById('stat');
  if (!el) return;
  el.textContent = !adbOk ? T('adbOff') : T2('statArch', {a: lastFrozen, r: lastRestricted});
}

function applyName(){
  var el = document.getElementById('appNameTx') || document.getElementById('appTitle');
  if (el) el.textContent = APP_NAMES[appName] || 'Mine App';
}

function applyTip(){
  document.body.classList.toggle('no-info', !showTip);
}

function iconLabel(k){
  var p = k.split('_');
  return T(p[0] === 'cl' ? 'icCl' : 'icSq') + '·' + T('ic' + p[1].toUpperCase());
}

function renderIcons(){
  var g = document.getElementById('iconGrid');
  if (!g) return;
  var h = '';
  for (var i = 0; i < ICONS.length; i++){
    var k = ICONS[i];
    h += '<div class="icon-cell' + (k === iconKey ? ' on' : '') + '" data-icon="' + k + '">'
       + '<img src="icon-' + k + '.png" alt="">'
       + '<span>' + iconLabel(k) + '</span></div>';
  }
  g.innerHTML = h;
}

function applyTheme(){
  var dark = themeMode === 'dark'
    || (themeMode === 'auto' && window.matchMedia('(prefers-color-scheme: dark)').matches);
  document.documentElement.classList.toggle('dark', dark);
}

function renderSettings(){
  var n = document.querySelectorAll('#nameBar .seg');
  for (var i = 0; i < n.length; i++)
    n[i].classList.toggle('on', n[i].getAttribute('data-name') === appName);
  var t = document.querySelectorAll('#themeBar .seg');
  for (var j = 0; j < t.length; j++)
    t[j].classList.toggle('on', t[j].getAttribute('data-theme') === themeMode);
  var g = document.querySelectorAll('#langBar .seg');
  for (var m = 0; m < g.length; m++)
    g[m].classList.toggle('on', g[m].getAttribute('data-lang') === langMode);
  var p = document.querySelectorAll('#tipBar .seg');
  for (var q = 0; q < p.length; q++)
    p[q].classList.toggle('on', (p[q].getAttribute('data-tip') === '1') === showTip);
  renderIcons();
}

/* 切换语言后刷新所有动态内容 */
function relang(){
  applyLang();
  render();
  renderSettings();
  if (lastBattery) updateBattery(lastBattery);
  var tb = document.getElementById('tuneBody');
  if (tb && tb.innerHTML) renderTune();
  if (document.getElementById('millet').classList.contains('show')) renderMillet();
  updateStat();
}

function openSettings(){
  renderSettings();
  document.getElementById('settings').classList.add('show');
}

/* 关闭时只摘掉 show：位移动画由 CSS transition 负责，左滑退出与点 × 同一条路径 */
function closeSettings(){
  document.getElementById('settings').classList.remove('show');
}

function saveKeep(){ localStorage.setItem('keep', JSON.stringify(Array.from(keep))); }
function toast(msg, ms){
  var t = document.getElementById('toast');
  t.textContent = msg; t.classList.add('show');
  clearTimeout(t._h);
  t._h = setTimeout(function(){ t.classList.remove('show'); }, ms || 2200);
}

function askConfirm(msg, onOk, opt){
  var m = document.getElementById('modal');
  document.getElementById('modal-msg').textContent = msg;
  var cancel = document.getElementById('modal-cancel');
  var ok = document.getElementById('modal-ok');
  var box = document.getElementById('modal-opt');
  var cb = document.getElementById('modal-opt-cb');
  // opt = {text, key}：带「下次不再提示」复选框
  if (opt){
    document.getElementById('modal-opt-tx').textContent = opt.text;
    box.style.display = 'flex';
    cb.checked = false;
  } else {
    box.style.display = 'none';
  }
  cancel.style.display = '';
  ok.textContent = T('confirm');
  m.classList.add('show');
  ok.onclick = function(){
    m.classList.remove('show');
    if (opt && cb.checked) localStorage.setItem(opt.key, '1');
    onOk();
  };
  cancel.onclick = function(){
    m.classList.remove('show');
  };
}

/* 只读信息弹窗：内容支持 HTML，只有「知道了」一个按钮 */
function askInfo(html){
  var m = document.getElementById('modal');
  document.getElementById('modal-msg').innerHTML = html;
  document.getElementById('modal-cancel').style.display = 'none';
  var ok = document.getElementById('modal-ok');
  ok.textContent = T('ok');
  m.classList.add('show');
  ok.onclick = function(){ m.classList.remove('show'); };
}

/* 首次使用 / 调试未开启时的操作指引 */
function showGuide(){
  askInfo(
    '<div class="info-title">' + T('guideTitle') + '</div>'
    + '<div class="info-step"><b>1</b>' + T('guide1') + '</div>'
    + '<div class="info-step"><b>2</b>' + T('guide2') + '</div>'
    + '<div class="info-step"><b>3</b>' + T('guide3') + '<br><code>adb tcpip 5555</code></div>'
    + '<div class="info-step"><b>4</b>' + T('guide4') + ' <b>↻</b></div>'
    + '<div class="info-step"><b>5</b>' + T('guide5') + '</div>'
    + '<div class="info-note">' + T('guideNote') + '</div>'
  );
}

/* 排序规则：主列表与白名单列表共用 */
function applySort(arr, mode){
  if (mode === 'mem') {
    arr.sort(function(a, b){ return (b.pss || 0) - (a.pss || 0); });
  } else if (mode === 'idle') {
    // 时间戳越小 = 越久没被前台打开 = 排越前；从未用过（0）排最前
    arr.sort(function(a, b){
      var la = (idleMap && idleMap[a.pkg]) || 0;
      var lb = (idleMap && idleMap[b.pkg]) || 0;
      return la - lb;
    });
  } else if (mode === 'name') {
    arr.sort(function(a, b){ return a.label.localeCompare(b.label, 'zh'); });
  }
}

/* 距上次前台打开的时长：15 s / 5 m / 3 h / 1 d */
function fmtAgo(ts){
  if (!ts) return '';
  var s = Math.round((Date.now() - ts) / 1000);
  if (s < 0) s = 0;
  if (s < 60) return s + ' s';
  var m = Math.floor(s / 60);
  if (m < 60) return m + ' m';
  var h = Math.floor(m / 60);
  if (h < 24) return h + ' h';
  return Math.floor(h / 24) + ' d';
}

/* 副行：运存占用 + 上次打开时间（主列表与白名单共用） */
function subline(a){
  var mb = Math.round((a.pss || 0) / 1024);
  var h = '';
  if (mb > 0){
    h = mb + ' MB';
    var pct = memTotal ? (a.pss * 100.0 / memTotal) : 0;
    if (pct >= 0.1) h += ' · ' + pct.toFixed(1) + '%';
  }
  if (idleMap){
    var ago = fmtAgo(idleMap[a.pkg]) || T('agoNever');
    h += (h ? ' · ' : '') + '<span class="ago">' + ago + '</span>';
  }
  if (!h) return '';
  // 绿点 = 真正还在活动：有内存占用、未被归档、未被限制后台，且系统里还有
  // cached/empty 之外的进程（只被缓存着的应用不点绿点，避免满屏都是点）。
  // 绝对定位到左侧空隙，不占文本流 —— 数字与上方应用名左对齐。
  var live = mb > 0 && a.active && !a.frozen && !a.restricted;
  return '<div class="mem">' + (live ? '<i class="dot-live"></i>' : '')
       + '<span class="mem-t">' + h + '</span></div>';
}

/* 系统标记：小号，紧跟应用名，不占右下角 */
function sysTag(a){
  return a.system ? '<span class="tag sys">' + T('tagSystem') + '</span>' : '';
}

/* 状态胶囊：固定放右下角，只有 正常 / 已限制 / 已归档 三种 */
function stateTag(a){
  return a.frozen ? '<span class="tag f">' + T('tagArchived') + '</span>'
      : (a.restricted ? '<span class="tag w">' + T('tagRestricted') + '</span>'
      : '<span class="tag">' + T('tagNormal') + '</span>');
}

/* 单行模板 */
function rowHtml(a){
  var on = keep.has(a.pkg);
  var cls = 'li' + (a.frozen ? ' frozen' : (a.restricted ? ' restricted' : ''))
          + (on ? ' on' : '');
  var aotOn = aotDone.has(a.pkg);
  return '<li class="' + cls + '" data-pkg="' + a.pkg + '">'
       +  '<span class="chk">' + (on ? '✓' : '') + '</span>'
       +  '<div class="meta"><div class="name"><span class="nm">' + a.label + '</span>'
       +    sysTag(a) + '</div>'
       +  '<div class="pkg">' + a.pkg + '</div>' + subline(a) + '</div>'
       +  '<div class="rt"><div class="rt-top">'
       +    (a.pss > 0 ? '<span class="kill-x" data-kill="' + a.pkg + '">×</span>' : '')
       +    '<span class="aot-lb">AOT</span>'
       +    '<span class="aot-sw' + (aotOn ? ' on' : '') + '" data-pkg="' + a.pkg
       +      '" data-aot="' + (aotOn ? '1' : '0') + '"><i class="fill"></i><b class="pct"></b></span>'
       +  '</div>' + stateTag(a) + '</div></li>';
}

/* 增量渲染：一次只铺 CHUNK 行，滚到底部再接着铺。
   以前是一次性 innerHTML 598 行 —— 那一下要排版一张 4 万像素高的长表，
   Chromium 的栅格化内存会瞬间冲到 400MB 以上（实测启动峰值 442MB）。
   分批之后初始只铺几十行，峰值大幅下降，滚动体验也更好。 */
var shownList = [];
var renderedCount = 0;
var CHUNK = 40;

function appendMore(){
  if (renderedCount >= shownList.length) return;
  var list = document.getElementById('list');
  var end = Math.min(renderedCount + CHUNK, shownList.length);
  var html = '';
  for (; renderedCount < end; renderedCount++) html += rowHtml(shownList[renderedCount]);
  list.insertAdjacentHTML('beforeend', html);
}

function render(){
  var q = document.getElementById('q').value.trim().toLowerCase();
  var list = document.getElementById('list');
  shownList = apps.filter(function(a){
    return !q || a.pkg.toLowerCase().indexOf(q) >= 0 || a.label.toLowerCase().indexOf(q) >= 0;
  });
  applySort(shownList, sortMode);

  // 重绘要保持原来的滚动位置：勾选/取消勾选也会走 render()，
  // 每次都弹回顶部的话，往下翻着选会很崩溃
  var prevTop = list.scrollTop;
  renderedCount = 0;
  list.innerHTML = '';
  appendMore();
  if (prevTop > 0){
    // 先补铺到足够高，再把滚动位置还原
    var guard = 0;
    while (renderedCount < shownList.length
           && list.scrollHeight < prevTop + list.clientHeight + 200 && guard++ < 100){
      appendMore();
    }
    list.scrollTop = prevTop;
  }
  document.getElementById('empty').style.display = (shownList.length || !adbOk) ? 'none' : 'block';
  updateInfo();
  updateActions();
}

/* 快滚到底部时补下一批（留 800px 提前量，避免看到空白） */
document.getElementById('list').addEventListener('scroll', function(){
  if (this.scrollTop + this.clientHeight > this.scrollHeight - 800) appendMore();
}, {passive:true});

/* 上次打开时间：首次用到时拉一次，可强制刷新 */
var idleBusy = false, idleCbs = [];
function ensureIdle(cb, force){
  if (idleMap && !force){ if (cb) cb(); return; }
  if (cb) idleCbs.push(cb);
  if (idleBusy) return;
  idleBusy = true;
  fetch('/api/usage', {method:'POST'}).then(function(r){ return r.json(); })
    .then(function(d){
      idleMap = d || {};
    }).catch(function(){
      idleMap = idleMap || {};
    }).then(function(){
      idleBusy = false;
      var cbs = idleCbs; idleCbs = [];
      for (var i = 0; i < cbs.length; i++){ try { cbs[i](); } catch(e){} }
    });
}

var HEALTH_KEY = {1:'hUnknown', 2:'hGood', 3:'hOverheat', 4:'hDead', 5:'hOverVolt', 6:'hFailure', 7:'hCold'};
function healthName(code){ return T(HEALTH_KEY[code] || 'hUnknown'); }

function updateBattery(b){
  var el = document.getElementById('battery');
  if (!el) return;
  lastBattery = b;
  var s = T('battery');
  if (b.health) {
    // 2 = 良好，其余（未知/过热/损坏/过压/故障/过冷）都标红
    s += (b.health === 2)
      ? ' ' + healthName(b.health)
      : ' <span style="color:#a32d2d;font-weight:600">' + healthName(b.health) + '</span>';
  }
  if (b.health_pct > 0) s += ' · ' + T('health') + b.health_pct + '%';
  if (b.temp > 0) s += ' · ' + b.temp + T('temp');
  el.innerHTML = s + ' <span class="tip-i" style="color:var(--ink3)">ⓘ</span>';
}

function showBatteryInfo(){
  var b = lastBattery;
  if (!b) return;
  var rows = '';
  function kv(k, v, tip){
    if (!v) return;
    rows += '<div class="info-kv"><span>' + k + '<span class="info-sub">' + tip
          + '</span></span><b>' + v + ' mAh</b></div>';
  }
  kv('Max', b.learned_max, T('battMax'));
  kv('Last', b.learned_last, T('battLast'));
  kv('Min', b.learned_min, T('battMin'));
  askInfo(
    '<div class="info-title">' + T2('battTitle', {p: (b.health_pct > 0 ? b.health_pct + '%' : '—')}) + '</div>'
    + '<div class="info-t">' + T('battLevel') + ' <b>' + b.level + '%</b>'
    + (b.health ? ' · ' + T('battSys') + ' <b>' + healthName(b.health) + '</b>' : '')
    + (b.temp > 0 ? ' · ' + T('temp').replace('℃','') + ' <b>' + b.temp + '°C</b>' : '') + '</div>'
    + rows
    + '<div class="info-note">' + T('battNote') + '</div>'
  );
}

function updateInfo(){
  var s = T2('infoSel', {n: keep.size, t: apps.length});
  var mem = '';
  if (memTotal) {
    var ug = ((memTotal - memAvail) / 1048576).toFixed(1);
    var tg = (memTotal / 1048576).toFixed(1);
    var up = ((memTotal - memAvail) * 100.0 / memTotal).toFixed(1);
    mem = T2('infoMem', {u: ug, g: tg, p: up});
  }
  document.getElementById('info').innerHTML =
      '<span class="i-sel">' + s
    + (keep.size > 0 ? ' <span id="clearSel">' + T('clearSel') + '</span>' : '')
    + '</span><span class="i-mem">' + mem + '</span>';
}

/* 底部按钮可用性：按「勾选了哪些应用」判断，把没意义的操作置灰。
     归档   —— 勾选非空，且都不是已归档（已归档的再归档没意义）
     还原   —— 勾选非空，且全部已归档
     限制   —— 勾选非空，且都不是已归档、也都不是已限制
     解限   —— 勾选非空，且都不是已归档、且全部已限制
   空选 / 断连 / 忙 一律全灰。 */
function updateActions(){
  var F = document.getElementById('btnFreezeSel'),
      R = document.getElementById('btnUnfreeze'),
      S = document.getElementById('btnRestrict'),
      U = document.getElementById('btnUnrestrict');
  if (!F) return;
  if (busy || !adbOk){
    F.disabled = R.disabled = S.disabled = U.disabled = true;
    return;
  }
  var sel = apps.filter(function(a){ return keep.has(a.pkg); });
  var n = sel.length;
  var canArchive = n > 0, canRelease = n > 0, canRestrict = n > 0, canUnrestrict = n > 0;
  for (var i = 0; i < n; i++){
    var a = sel[i];
    if (a.frozen) canArchive = false;
    if (!a.frozen) canRelease = false;
    if (a.frozen || a.restricted) canRestrict = false;
    if (a.frozen || !a.restricted) canUnrestrict = false;
  }
  F.disabled = !canArchive;
  R.disabled = !canRelease;
  S.disabled = !canRestrict;
  U.disabled = !canUnrestrict;
}

function updateBanner(d){
  var el = document.getElementById('banner');
  if (!el) return;
  adbOk = d.adb_ok !== false;
  updateActions();
  if (adbOk){ el.style.display = 'none'; el.innerHTML = ''; return; }
  var st = d.adb_state || '';
  var title = T('bannerOff');
  var hint = T('bannerOffHint');
  if (st === 'unauthorized'){
    title = T('bannerUnauth');
    hint = T('bannerUnauthHint');
  } else if (st === 'offline'){
    title = T('bannerOffline');
    hint = T('bannerOfflineHint');
  } else if (st === 'error'){
    title = T('bannerErr');
    hint = T('bannerErrHint');
  }
  el.innerHTML = '<b>' + title + '</b><span class="fix">' + hint + '</span>'
    + '<span class="guide" id="bannerGuide">' + T('bannerGuide') + '</span>';
  el.style.display = 'flex';
  var g = document.getElementById('bannerGuide');
  if (g) g.onclick = showGuide;
}

/* ---------------- 列表缓存 ----------------
   App 被杀掉再进来时（换图标也会把进程带走），1 分钟内直接用上次的快照，
   不再自动重拉一次列表；手动点刷新永远重新拉。 */
var CACHE_KEY = 'listCache', CACHE_TTL = 60 * 1000;

function applyData(d){
  updateBanner(d);
  // 用户希望保持性能模式：一旦发现被系统回退，立即重新施加
  if (perfWanted && d.perf === false){
    fetch('/api/perf', {method:'POST', headers:{'Content-Type':'application/json'},
                        body: JSON.stringify({on: true})});
  }
  document.getElementById('perfChk').checked = (d.perf === true) || perfWanted;
  apps = d.apps || [];
  if (d.with_pss) {
    hasPss = true;
    memTotal = d.mem_total || 0;
    memPct = d.mem_pct || 0;
    memAvail = d.mem_avail || 0;
  }
  if (d.battery) updateBattery(d.battery);
  else document.getElementById('battery').textContent = '电量 —';
  // 右上角固定显示归档/限制数量（内存总量已在底部汇总里）
  lastFrozen = d.frozen || 0;
  lastRestricted = d.restricted_count || 0;
  updateStat();
  render();
  // 每次刷新重算「上次打开时间」，拿到后再重绘一次
  ensureIdle(render, true);
}

function saveCache(d){
  if (!d || d.adb_ok === false) return;      // 失败/没授权的空列表不能写进缓存
  try {
    localStorage.setItem(CACHE_KEY, JSON.stringify({
      t: Date.now(), apps: d.apps || [], with_pss: !!d.with_pss,
      mem_total: d.mem_total, mem_pct: d.mem_pct, mem_avail: d.mem_avail,
      battery: d.battery || null, frozen: d.frozen || 0,
      restricted_count: d.restricted_count || 0, perf: d.perf
    }));
  } catch (e) { /* 配额超了就算了，不影响主流程 */ }
}

/* 返回 0=没有缓存 / 1=缓存新鲜（可直接用，不必再拉）/ 2=缓存已过期（先画出来再后台刷新）
   allowStale=true 时即使过期也会先渲染 —— 冷启动等授权的那几秒不应该是一片空白 */
function restoreCache(allowStale){
  try {
    var raw = localStorage.getItem(CACHE_KEY);
    if (!raw) return 0;
    var d = JSON.parse(raw);
    if (!d || !d.t) return 0;
    var fresh = (Date.now() - d.t) <= CACHE_TTL;
    if (!fresh && !allowStale) return 0;
    applyData(d);
    return fresh ? 1 : 2;
  } catch (e) { return 0; }
}

/* 刷新图标的旋转：用 rAF 自己累加角度，停下来时一定停在整圈上。
   用 CSS animation 的话，remove 那一刻会停在任意角度，看起来像卡了一下。 */
var spinDeg = 0, spinOn = false, spinStop = false, spinLimit = -1, spinLast = 0, spinEl = null;
function spinFrame(ts){
  if (!spinOn) return;
  if (!spinEl) spinEl = document.getElementById('btnRefresh');
  if (!spinLast) spinLast = ts;
  var dt = Math.min(ts - spinLast, 64);   // 掉帧时别一下跳太远
  spinLast = ts;
  var step = dt * 360 / 780;              // 780ms 一圈
  if (spinStop && spinLimit < 0) spinLimit = Math.ceil(spinDeg / 360) * 360;
  spinDeg += step;
  if (spinLimit >= 0 && spinDeg >= spinLimit){
    spinDeg = spinLimit;
    spinOn = false;
    if (spinEl){ spinEl.style.transform = ''; spinEl.classList.remove('spinning'); }
    return;
  }
  if (spinEl) spinEl.style.transform = 'rotate(' + spinDeg + 'deg)';
  requestAnimationFrame(spinFrame);
}
function spinStart(){
  if (spinOn) return;
  spinOn = true; spinStop = false; spinLimit = -1; spinLast = 0;
  spinEl = document.getElementById('btnRefresh');
  if (spinEl) spinEl.classList.add('spinning');
  requestAnimationFrame(spinFrame);
}
function spinFinish(){ if (spinOn) spinStop = true; }

function load(force){
  // force=true 表示手动刷新或刚做过操作，必须拿新数据
  var state = force ? 0 : restoreCache(true);
  // 1 分钟内的缓存直接用它，不必再拉；过期的缓存也已经画出来了，继续后台刷新
  if (state === 1) return;

  var btn = document.getElementById('btnRefresh');
  if (btn) btn.disabled = true;
  spinStart();

  // 已经有内容（旧缓存）时不要用「加载中」把摘要行顶掉，转圈本身就是提示
  var el = document.getElementById('stat');
  if (state === 0 && el) el.textContent = T('loading');

  // 首次连接可能要在手机上点授权，几秒没回来就先提示用户去看手机
  var authTimer = setTimeout(function(){
    if (state === 0 && el && el.textContent === T('loading')) el.textContent = T('waitAuth');
  }, 4000);

  fetch('/api/list?pss=1').then(function(r){ return r.json(); }).then(function(d){
    clearTimeout(authTimer);
    saveCache(d);
    applyData(d);
    if (btn) btn.disabled = false;
    spinFinish();
  }).catch(function(e){
    clearTimeout(authTimer);
    if (state === 0 && el) el.textContent = T('connFail');
    if (btn) btn.disabled = false;
    spinFinish();
    toast(T2('tConnFail', {m: e}));
  });
}

document.getElementById('list').addEventListener('click', function(e){
  if (e.target.closest('.aot-sw')) return;   // AOT 开关走单独的处理
  if (e.target.closest('.kill-x')) return;   // 清理按钮也走单独的处理
  var li = e.target.closest('li');
  if (!li || busy) return;
  var pkg = li.getAttribute('data-pkg');
  if (keep.has(pkg)) keep.delete(pkg); else keep.add(pkg);
  saveKeep(); render();
});

/* 单应用清理：am kill 只杀后台进程，不动自启动，也不改启用状态 */
var killNoAsk = 'killNoAsk';
function doKill(pkg, nm, pss){
  // 把列表里显示的那个 PSS 一起带上，后端就不用再跑一次全量 meminfo
  fetch('/api/kill', {method:'POST', headers:{'Content-Type':'application/json'},
                      body: JSON.stringify({pkg: pkg, pss: pss || 0})})
    .then(function(r){ return r.json(); })
    .then(function(d){
      if (!d.ok){ toast(T2('tKillFail', {m: d.msg || T('tUnknown')})); return; }
      var freed = Math.round((d.freed_kb || 0) / 1024);
      // 文案比默认长，多留一会儿
      toast(freed > 0 ? T2('tKilled', {n: nm, m: freed}) : T2('tKilledZero', {n: nm}), 3200);
      // 稍等一下再刷新，让系统的运存统计跟上
      setTimeout(function(){ load(true); }, 800);
    })
    .catch(function(){ toast(T2('tKillFail', {m: T('tUnknown')})); });
}
document.getElementById('list').addEventListener('click', function(e){
  var kx = e.target.closest('.kill-x');
  if (!kx) return;
  if (!adbOk){ toast(T('tAdbOff')); return; }
  var pkg = kx.getAttribute('data-kill');
  var one = null;
  for (var i = 0; i < apps.length; i++) if (apps[i].pkg === pkg){ one = apps[i]; break; }
  var nm = one ? one.label : pkg;
  // 运存为 0 = 当前没有后台进程，am kill 什么也杀不到，别假装清理成功
  if (one && one.pss <= 0){ toast(T2('tKillIdle', {n: nm})); return; }
  var pss = one ? one.pss : 0;
  if (localStorage.getItem(killNoAsk) === '1'){ doKill(pkg, nm, pss); return; }
  askConfirm(T2('cfmKill', {n: nm}),
             function(){ doKill(pkg, nm, pss); },
             {text: T('killDontAsk'), key: killNoAsk});
});

/* AOT 预编译：点开关 → 轨道变成进度条（秒数同步显示在标签上），完成后就地刷新 */
var aotBusy = false;
document.getElementById('list').addEventListener('click', function(e){
  var sw = e.target.closest('.aot-sw');
  if (!sw) return;
  if (aotBusy){ toast(T('tCompiling')); return; }
  if (!adbOk){ toast(T('tAdbOff')); return; }
  var pkg = sw.getAttribute('data-pkg');
  var wantOn = sw.getAttribute('data-aot') !== '1';
  aotBusy = true;

  // 进度条：轨道清空、滑块隐藏，绿色从左侧推进，百分比实时显示在左侧标签上。
  // 真实耗时不可知（微信 speed 编译要 6 分钟+），用渐近曲线：起步快、后段缓慢逼近 99%
  sw.classList.remove('on');
  sw.classList.add('busy');
  var fill = sw.querySelector('.fill');
  var pctEl = sw.querySelector('.pct');
  var t0 = Date.now();
  // 显示已用时而不是假百分比：大应用 speed 编译实测要 10 分钟，
  // 伪进度条会在 99% 停七八分钟，看起来像卡死。真实耗时不可知，报时间最诚实。
  function tick(){
    var t = (Date.now() - t0) / 1000;
    var pct = Math.min(99, 99 * (1 - Math.pow(0.5, (t + 3) / 20)));
    if (fill) fill.style.width = pct.toFixed(1) + '%';
    if (pctEl){
      var sec = Math.floor(t);
      pctEl.textContent = sec < 60 ? (sec + 's')
                        : (Math.floor(sec / 60) + ':' + ('0' + (sec % 60)).slice(-2));
    }
  }
  tick();
  var timer = setInterval(tick, 200);

  function settle(ok){
    // 就地复位，不整表重绘 —— 否则列表会跳回顶部
    clearInterval(timer);
    aotBusy = false;
    sw.classList.remove('busy');
    if (fill) fill.style.width = '0%';
    if (pctEl) pctEl.textContent = '';
    if (ok){
      if (wantOn) sw.classList.add('on');
      sw.setAttribute('data-aot', wantOn ? '1' : '0');
    }
  }

  fetch('/api/aot', {method:'POST', headers:{'Content-Type':'application/json'},
                     body: JSON.stringify({pkg: pkg, on: wantOn})})
    .then(function(r){ return r.json(); })
    .then(function(d){
      if (!d.ok){
        settle(false);
        // busy = 后台已经有一个编译在跑（Java 侧的单飞保护），不是失败
        if (d.busy) toast(T('tCompiling'));
        else if (d.lowmem) toast(T2('tLowMem', {n: d.avail_mb}));
        else toast(T2('tFail', {m: d.msg || T('tUnknown')}));
        return;
      }
      settle(true);
      if (wantOn) aotDone.add(pkg); else aotDone.delete(pkg);
      saveAot();
      var secs = Math.round((Date.now() - t0) / 1000);
      if (d.skipped) toast(T('tAlready'));
      else toast(wantOn ? T2('tCompiled', {n: secs}) : T2('tReverted', {n: secs}));
    })
    .catch(function(){ settle(false); });
});

document.getElementById('q').addEventListener('input', render);

document.getElementById('btnFreezeSel').addEventListener('click', function(){
  if (busy) { toast(T('tBusy')); return; }
  var sel = apps.filter(function(a){ return keep.has(a.pkg) && !a.frozen; });
  if (!sel.length){ toast(T('tNoArchivable')); return; }
  askConfirm(T2('cfmArchive', {n: sel.length}), function(){
    run('/api/freeze', sel.map(function(a){ return a.pkg; }), T('archive'));
  });
});

document.getElementById('btnRestrict').addEventListener('click', function(){
  if (busy) { toast(T('tBusy')); return; }
  var sel = apps.filter(function(a){ return keep.has(a.pkg) && !a.frozen; });
  if (!sel.length){ toast(T('tPickRestrict')); return; }
  askConfirm(T2('cfmRestrict', {n: sel.length}), function(){
    run('/api/restrict', sel.map(function(a){ return a.pkg; }), T('restrict'));
  });
});

document.getElementById('btnUnrestrict').addEventListener('click', function(){
  if (busy) { toast(T('tBusy')); return; }
  var sel = apps.filter(function(a){ return keep.has(a.pkg) && !a.frozen; });
  if (!sel.length){ toast(T('tPickUnrestrict')); return; }
  askConfirm(T2('cfmUnrestrict', {n: sel.length}), function(){
    run('/api/unrestrict', sel.map(function(a){ return a.pkg; }), T('unrestrict'));
  });
});

document.getElementById('btnUnfreeze').addEventListener('click', function(){
  if (busy) { toast(T('tBusy')); return; }
  var sel = apps.filter(function(a){ return keep.has(a.pkg) && a.frozen; });
  if (!sel.length){ toast(T('tNoArchived')); return; }
  askConfirm(T2('cfmRelease', {n: sel.length}), function(){
    run('/api/unfreeze', sel.map(function(a){ return a.pkg; }), T('release'));
  });
});

function run(url, pkgs, word){
  busy = true;
  setBusy(true);
  document.getElementById('stat').textContent = T2('running', {n: pkgs.length});
  fetch(url, {method:'POST', headers:{'Content-Type':'application/json'},
              body: JSON.stringify({pkgs: pkgs})})
    .then(function(r){ return r.json(); })
    .then(function(d){
      busy = false; setBusy(false);
      // 操作成功的应用自动取消勾选，避免「已选」计数残留
      var failed = d.fail || [];
      for (var i = 0; i < pkgs.length; i++){
        if (failed.indexOf(pkgs[i]) < 0) keep.delete(pkgs[i]);
      }
      saveKeep();
      if (d.adb_ok === false){
        // 真实原因是调试通道断了，不能报成「失败 N 个」
        toast(T('tAdbNoRun'));
        load(true);
        return;
      }
      var msg = T2('tDone', {w: word, ok: d.ok});
      if (d.fail && d.fail.length) msg += T2('tDoneFail', {n: d.fail.length});
      toast(msg);
      load(true);
    })
    .catch(function(e){
      busy = false; setBusy(false);
      toast(T2('tRunFail', {w: word, m: e}));
    });
}

function setBusy(b){
  // 刷新按钮始终可用（断了也要能重试）；操作按钮按勾选内容重新判定
  updateActions();
  var r = document.getElementById('btnRefresh');
  if (r) r.disabled = b;
}

document.querySelectorAll('#sortbar .seg').forEach(function(btn){
  btn.classList.toggle('on', btn.getAttribute('data-sort') === sortMode);
  btn.addEventListener('click', function(){
    var m = btn.getAttribute('data-sort');
    if (m === sortMode) return;
    sortMode = m;
    localStorage.setItem('sortMode', m);
    document.querySelectorAll('#sortbar .seg').forEach(function(b){ b.classList.remove('on'); });
    btn.classList.add('on');
    if (m === 'idle' && !idleMap) { ensureIdle(render); return; }
    if (m === 'mem' && !hasPss) { load(true); } else { render(); }
  });
});

document.getElementById('info').addEventListener('click', function(e){
  if (e.target && e.target.id === 'clearSel') {
    keep.clear();
    saveKeep();
    render();
    toast(T('tCleared'));
  }
});

document.getElementById('btnRefresh').addEventListener('click', function(){
  if (busy) { toast('正在处理，请稍候'); return; }
  load(true);
});

/* 点电池信息看明细 */
document.getElementById('battery').addEventListener('click', showBatteryInfo);

/* 性能模式开关：状态存本地，每次刷新都会尝试重新施加，
   因为 MIUI 会在某些时机把它回退成均衡模式。 */
var perfWanted = localStorage.getItem('perf') === '1';
document.getElementById('perfChk').checked = perfWanted;

document.getElementById('perfChk').addEventListener('change', function(){
  perfWanted = this.checked;
  localStorage.setItem('perf', perfWanted ? '1' : '0');
  fetch('/api/perf', {method:'POST', headers:{'Content-Type':'application/json'},
                      body: JSON.stringify({on: perfWanted})})
    .then(function(r){ return r.json(); })
    .then(function(d){
      if (d.adb_ok === false){ toast(T('tPerfNoAdb')); return; }
      toast(d.on ? T('tPerfOn') : T('tPerfOff'));
    });
});

/* ---------- 性能调优面板 ---------- */
var tuneData = null;

function tunePost(k){
  return fetch('/api/tune', {method:'POST', headers:{'Content-Type':'application/json'},
                             body: JSON.stringify({k: k || ''})}).then(function(r){ return r.json(); });
}

function trowSwitch(key, title, sub, on){
  return '<div class="trow"><div style="flex:1"><div class="tl">' + title + '</div>'
       + (sub ? '<div class="ts">' + sub + '</div>' : '') + '</div>'
       + '<label class="switch"><input type="checkbox" data-tune="' + key + '"'
       + (on ? ' checked' : '') + '><span class="slider"></span></label></div>';
}

function trowValue(title, sub, value){
  return '<div class="trow disabled"><div style="flex:1"><div class="tl">' + title + '</div>'
       + (sub ? '<div class="ts">' + sub + '</div>' : '') + '</div>'
       + '<span class="tv">' + (value || '—') + '</span></div>';
}

function renderTune(){
  var d = tuneData || {};
  var r = d.root || {};
  var h = '';
  h += trowSwitch('perf', T('tunePerf'), T('tunePerfSub'), d.perf);
  h += trowSwitch('save', T('tuneSave'), T('tuneSaveSub'), d.save);
  h += trowSwitch('balanced', T('tuneBal'), T('tuneBalSub'), d.balanced);
  h += trowSwitch('gpu', T('tuneGpu'), 'GPUTUNER_SWITCH', d.gpu);
  h += trowSwitch('game', T('tuneGame'), 'cloud_periodic_game_enable', d.game);
  h += '<div class="trow"><div style="flex:1"><div class="tl">' + T('milletTitle') + '</div>'
     + '<div class="ts">' + T2('milletSub', {n: (d.millet_count || 0)}) + '</div></div>'
     + '<button class="edit" id="milletOpen">' + T('edit') + '</button></div>';
  h += '<div class="tsect">' + T('rootNote') + '</div>';
  h += trowValue(T('rtName'), 'persist.sys.enable_rtmode', r.rtmode);
  h += trowValue(T('tempLimit'), 'rt_templimit_bottom ~ ceiling', r.templimit);
  h += trowValue(T('bootMode'), 'persist.sys.power.default.powermode', r.powermode);
  h += trowValue(T('governor'), 'scaling_governor', r.governor);
  document.getElementById('tuneBody').innerHTML = h;
  var mo = document.getElementById('milletOpen');
  if (mo) mo.onclick = openMillet;
}

function openTune(){
  document.getElementById('tune').classList.add('show');
  tunePost('').then(function(d){ tuneData = d; renderTune(); });
}

/* 用事件委托绑定，避免元素尚未就绪时绑定失效 */
document.addEventListener('click', function(e){
  if (e.target && e.target.closest && e.target.closest('#btnTune')) openTune();
});
console.log('tune-ready');
document.getElementById('tuneClose').onclick = function(){
  document.getElementById('tune').classList.remove('show');
};

document.getElementById('tuneBody').addEventListener('change', function(e){
  var cb = e.target.closest('[data-tune]');
  if (!cb) return;
  var key = cb.getAttribute('data-tune');
  cb.disabled = true;
  tunePost(key + '=' + (cb.checked ? '1' : '0')).then(function(d){
    tuneData = d;
    renderTune();
    if (key === 'perf'){
      perfWanted = !!d.perf;
      localStorage.setItem('perf', perfWanted ? '1' : '0');
      document.getElementById('perfChk').checked = perfWanted;
    }
  });
});

/* ---------- Millet 白名单编辑 ---------- */
var milletSet = new Set();
var milletSortMode = 'default';

function openMillet(){
  fetch('/api/millet', {method:'POST', headers:{'Content-Type':'application/json'},
                        body: JSON.stringify({})})
    .then(function(r){ return r.json(); })
    .then(function(d){
      milletSet = new Set((d.list || '').split(',')
        .map(function(s){ return s.trim(); }).filter(function(s){ return s.length; }));
      document.getElementById('mq').value = '';
      document.getElementById('millet').classList.add('show');
      renderMillet();
      ensureIdle(renderMillet);
    });
}

function renderMillet(){
  var q = (document.getElementById('mq').value || '').trim().toLowerCase();
  var onlyPicked = (milletSortMode === 'picked');
  var arr = apps.filter(function(a){
    if (onlyPicked && !milletSet.has(a.pkg)) return false;
    return !q || a.pkg.toLowerCase().indexOf(q) >= 0 || a.label.toLowerCase().indexOf(q) >= 0;
  });
  if (!onlyPicked) applySort(arr, milletSortMode);
  var html = '';
  for (var i = 0; i < arr.length; i++){
    var a = arr[i];
    var on = milletSet.has(a.pkg);
    var cls = 'li' + (a.frozen ? ' frozen' : (a.restricted ? ' restricted' : ''))
            + (on ? ' on' : '');
    html += '<li class="' + cls + '" data-mpkg="' + a.pkg + '">'
         +  '<span class="chk">' + (on ? '✓' : '') + '</span>'
         +  '<div class="meta"><div class="name"><span class="nm">' + a.label + '</span>'
         +    sysTag(a) + '</div>'
         +  '<div class="pkg">' + a.pkg + '</div>' + subline(a) + '</div>'
         +  stateTag(a) + '</li>';
  }
  if (!html) html = '<div class="empty">' + T('empty') + '</div>';
  document.getElementById('milletList').innerHTML = html;
  updateMilletInfo();
}

function updateMilletInfo(){
  var el = document.getElementById('milletSel');
  if (el) el.innerHTML = T2('infoSel', {n: milletSet.size, t: apps.length});
  var c = document.getElementById('milletClear');
  if (c) c.disabled = (milletSet.size === 0);
  // 已勾选应用占用的内存合计，贴在最右侧
  var m = document.getElementById('milletMem');
  if (!m) return;
  var sum = 0;
  for (var i = 0; i < apps.length; i++)
    if (milletSet.has(apps[i].pkg)) sum += (apps[i].pss || 0);
  if (sum > 0 && memTotal) {
    m.innerHTML = T2('memSel', {
      u: (sum / 1048576).toFixed(2),
      p: (sum * 100.0 / memTotal).toFixed(1)
    });
  } else {
    m.textContent = '';
  }
}

document.getElementById('mq').addEventListener('input', renderMillet);

document.getElementById('milletClose').onclick = function(){
  document.getElementById('millet').classList.remove('show');
};

document.getElementById('milletClear').onclick = function(){
  if (!milletSet.size) return;
  milletSet.clear();
  renderMillet();
};

document.getElementById('milletList').addEventListener('click', function(e){
  var li = e.target.closest('li');
  if (!li) return;
  var p = li.getAttribute('data-mpkg');
  if (milletSet.has(p)) milletSet.delete(p); else milletSet.add(p);
  renderMillet();
});

document.getElementById('milletSort').addEventListener('click', function(e){
  var b = e.target.closest('[data-msort]');
  if (!b) return;
  milletSortMode = b.getAttribute('data-msort');
  document.querySelectorAll('#milletSort .seg').forEach(function(x){ x.classList.remove('on'); });
  b.classList.add('on');
  if (milletSortMode === 'idle' && !idleMap){
    ensureIdle(renderMillet);
    return;
  }
  renderMillet();
});

document.getElementById('milletSave').onclick = function(){
  var csv = Array.from(milletSet).join(',');
  fetch('/api/millet', {method:'POST', headers:{'Content-Type':'application/json'},
                        body: JSON.stringify({csv: csv})})
    .then(function(r){ return r.json(); })
    .then(function(){
      toast(T2('tSaved', {n: milletSet.size}));
      document.getElementById('millet').classList.remove('show');
      if (tuneData) tuneData.millet_count = milletSet.size;
    });
};

/* ---------- 设置页：名称 / 主题 ---------- */
document.getElementById('appTitle').addEventListener('click', openSettings);

document.getElementById('setClose').onclick = closeSettings;

document.getElementById('nameBar').addEventListener('click', function(e){
  var b = e.target.closest('[data-name]');
  if (!b) return;
  var v = b.getAttribute('data-name');
  if (v === appName) return;
  appName = v;
  localStorage.setItem('appName', v);
  applyName();
  renderSettings();
  fetch('/api/name', {method:'POST', headers:{'Content-Type':'application/json'},
                      body: JSON.stringify({name: v})})
    .then(function(r){ return r.json(); })
    .then(function(d){
      toast(d && d.ok ? T('tNameOk') : T('tNameFail'));
    })
    .catch(function(){ toast(T('tNameFail')); });
});

document.getElementById('themeBar').addEventListener('click', function(e){
  var b = e.target.closest('[data-theme]');
  if (!b) return;
  themeMode = b.getAttribute('data-theme');
  localStorage.setItem('theme', themeMode);
  applyTheme();
  renderSettings();
});

/* 启动风险提示：只能通过「同意并使用」关闭，点遮罩无效 */
document.getElementById('riskOk').addEventListener('click', function(){
  document.getElementById('risk').classList.remove('show');
});
document.getElementById('riskQuit').addEventListener('click', function(){
  // 有 Java 桥就真的退出；Termux 版没有桥，退回关闭窗口
  if (window.Bridge && window.Bridge.exitApp) { window.Bridge.exitApp(); return; }
  window.close();
});

document.getElementById('tipBar').addEventListener('click', function(e){
  var b = e.target.closest('[data-tip]');
  if (!b) return;
  showTip = b.getAttribute('data-tip') === '1';
  localStorage.setItem('showTip', showTip ? '1' : '0');
  // 重新打开「界面提示」时，把「清理不再提示」也一起复位 ——
  // 否则用户勾过一次之后，就再也没有入口能把它改回来了
  if (showTip) localStorage.removeItem('killNoAsk');
  applyTip();
  renderSettings();
});

document.getElementById('iconGrid').addEventListener('click', function(e){
  var c = e.target.closest('[data-icon]');
  if (!c) return;
  var k = c.getAttribute('data-icon');
  if (k === iconKey) return;
  iconKey = k;
  localStorage.setItem('iconKey', k);
  renderIcons();
  fetch('/api/icon', {method:'POST', headers:{'Content-Type':'application/json'},
                      body: JSON.stringify({key: k})})
    .then(function(r){ return r.json(); })
    .then(function(d){ toast(d && d.ok ? T('tIconOk') : T('tIconFail')); })
    .catch(function(){ toast(T('tIconFail')); });
});

document.getElementById('langBar').addEventListener('click', function(e){
  var b = e.target.closest('[data-lang]');
  if (!b) return;
  var v = b.getAttribute('data-lang');
  if (v === langMode) return;
  langMode = v;
  localStorage.setItem('lang', v);
  relang();
});

/* 主界面「从左往右滑」进入设置，设置页「从右往左滑」退回主界面。
   注意两点：
   1. 只靠 touchend 不可靠 —— 短距离拖动会被 WebView 当成滚动手势并改发
      touchcancel，touchend 根本不触发。所以在 touchmove 里即时判定。
   2. 阈值是 CSS px（不是设备像素），屏宽约 360，45px ≈ 1/8 屏宽。 */
var _tx = 0, _ty = 0, _tsw = false;
document.addEventListener('touchstart', function(e){
  if (e.touches.length !== 1) { _tsw = false; return; }
  var tag = e.target && e.target.tagName;
  if (tag === 'INPUT' || tag === 'TEXTAREA') { _tsw = false; return; }  // 输入框里允许选择文字
  _tx = e.touches[0].clientX; _ty = e.touches[0].clientY; _tsw = true;
}, {passive:true});
document.addEventListener('touchcancel', function(){ _tsw = false; }, {passive:true});

function _maybeSwipe(x, y){
  var dx = x - _tx, dy = y - _ty;
  if (Math.abs(dx) < 45 || Math.abs(dy) > 70 || Math.abs(dx) < Math.abs(dy) * 1.4) return;
  _tsw = false;
  var st = document.getElementById('settings');
  if (st && st.classList.contains('show')){
    if (dx < 0) closeSettings();          // 从右往左 → 退回主界面
    return;
  }
  if (document.querySelector('.sheet.show')) return;   // 其它弹层里不处理
  if (dx > 0) openSettings();             // 从左往右 → 进入设置
}
document.addEventListener('touchmove', function(e){
  if (!_tsw) return;
  var t = e.touches[0];
  _maybeSwipe(t.clientX, t.clientY);
}, {passive:true});
document.addEventListener('touchend', function(e){
  if (!_tsw) return;
  _tsw = false;
  var t = e.changedTouches[0];
  _maybeSwipe(t.clientX, t.clientY);
}, {passive:true});

applyName();
applyTheme();
applyTip();
applyLang();
/* Termux 版没有 Java 桥，图标选择器无从落地，直接藏掉整段 */
if (!HAS_BRIDGE) {
  var ig = document.getElementById('iconGrid');
  if (ig) ig.style.display = 'none';
}
load();
</script>
</body>
</html>
'''


class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        sys.stderr.write("%s - %s\n" % (self.address_string(), fmt % args))

    def _send(self, code, body, ctype="application/json; charset=utf-8"):
        if isinstance(body, str):
            body = body.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0")
        self.send_header("Pragma", "no-cache")
        self.send_header("Expires", "0")
        self.end_headers()
        try:
            self.wfile.write(body)
        except Exception:
            pass

    def do_GET(self):
        path = urllib.parse.urlparse(self.path).path
        if path == "/" or path == "/index.html":
            self._send(200, HTML, "text/html; charset=utf-8")
            return
        if path == "/api/list":
            qs = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
            with_pss = (qs.get("pss", [""])[0] == "1"
                        or qs.get("sort", [""])[0] == "mem")
            adb_ok, adb_state_name, adb_msg = adb_state()
            if not adb_ok:
                # 提前返回：否则 get_apps/get_pss/get_battery 会各跑一遍失败命令
                self._send(200, json.dumps({
                    "apps": [], "active": 0, "total": 0, "frozen": 0,
                    "restricted_count": 0, "with_pss": with_pss,
                    "battery": None,
                    "adb_ok": False, "adb_state": adb_state_name,
                    "adb_msg": adb_msg,
                }, ensure_ascii=False))
                return
            apps, n_active, n_all = get_apps(with_pss)
            frozen = sum(1 for a in apps if a["frozen"])
            restricted_count = sum(1 for a in apps if a["restricted"])
            payload = {
                "apps": apps, "active": n_active, "total": n_all,
                "frozen": frozen, "restricted_count": restricted_count,
                "with_pss": with_pss,
                "battery": get_battery(),
                "adb_ok": True, "adb_state": "ok", "adb_msg": "",
            }
            if with_pss:
                mt, ma = get_mem_info()
                payload["mem_total"] = mt
                payload["mem_avail"] = ma
                payload["mem_pct"] = round((mt - ma) * 100.0 / mt, 1) if mt else 0
            self._send(200, json.dumps(payload, ensure_ascii=False))
            return
        self._send(404, json.dumps({"error": "not found"}))

    def do_POST(self):
        path = urllib.parse.urlparse(self.path).path
        try:
            length = int(self.headers.get("Content-Length") or 0)
            raw = self.rfile.read(length) if length else b"{}"
            data = json.loads(raw.decode("utf-8"))
        except Exception:
            self._send(400, json.dumps({"error": "bad request"}))
            return

        items = data.get("pkgs") or []
        if not isinstance(items, list) or not items:
            self._send(400, json.dumps({"error": "empty pkgs"}))
            return

        adb_ok, adb_state_name, adb_msg = adb_state()
        if not adb_ok:
            # 不要报「失败 N 个」——真实原因是调试通道断了，不是操作本身失败
            self._send(200, json.dumps({
                "ok": 0, "fail": items,
                "adb_ok": False, "adb_state": adb_state_name, "adb_msg": adb_msg,
            }, ensure_ascii=False))
            return

        if path == "/api/freeze":
            ok, fail = do_batch(items, "freeze")
        elif path == "/api/unfreeze":
            ok, fail = do_batch(items, "unfreeze")
        elif path == "/api/restrict":
            ok, fail = do_batch(items, "restrict")
        elif path == "/api/unrestrict":
            ok, fail = do_batch(items, "unrestrict")
        else:
            self._send(404, json.dumps({"error": "not found"}))
            return

        self._send(200, json.dumps({"ok": ok, "fail": fail}, ensure_ascii=False))


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


def main():
    out = adb(["devices"])
    if "127.0.0.1:5555" not in out:
        print("警告：adb 未连接到 127.0.0.1:5555")
        print("先运行：adb connect 127.0.0.1:5555")
        print()

    print("应用管理器已启动：http://127.0.0.1:%d" % PORT)
    print("在手机浏览器打开上面的地址，或添加到主屏幕。")
    print("按 Ctrl+C 停止。")
    print()

    try:
        subprocess.Popen(["am", "start", "-a", "android.intent.action.VIEW",
                          "-d", "http://127.0.0.1:%d" % PORT],
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    except Exception:
        pass

    with Server(("127.0.0.1", PORT), Handler) as httpd:
        try:
            httpd.serve_forever()
        except KeyboardInterrupt:
            print("\n已停止")


if __name__ == "__main__":
    main()
