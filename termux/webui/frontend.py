#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
应用管理器 · 前端源码（唯一真源）

本文件不直接运行，只作为构建输入。`appmanager/tools/gen_assets.py` 从这里抽两样东西：

  1. HTML 块        → 注入 fetch → Java 桥的 shim，打包成 assets/index.html
  2. APP_NAMES 映射 → 生成 AppNames.java（包名 → 中文名）

历史上这里还有一个独立的 HTTP 服务端（Termux 免 root 形态），
该分发形态已废弃，服务端代码于 2026-10-31 删除，只保留前端源码本身。
"""

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
/* 断连时的安抚说明：跟 hint 同一行距，但不走等宽字体，读起来是「人话」不是命令 */
.banner .safe{font-size:11.5px;color:var(--ink2);line-height:1.5}
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
.info-step>span{flex:1;min-width:0}
.info-step code{white-space:nowrap;display:inline-block;background:var(--bg);padding:1px 6px;border-radius:5px;
font-family:ui-monospace,monospace;font-size:12px;color:var(--ink)}
/* 🔴 所有说明性灰字统一 11.5px：以前 .info-note 是 12px、.idx-note 是 11.5px，
   同一屏里两种字号并排，肉眼能看出「这句比那句大一号」。 */
.info-note{font-size:11.5px;color:var(--ink3);line-height:1.65;margin-top:12px;
padding-top:10px;border-top:0.5px solid var(--line)}
/* 自定义端口：默认只露一行「…点此设置」，点开才展开输入框 ——
   旧用户不点就永远看不到，避免打扰 */
.info-port{font-size:12.5px;color:var(--ink3);margin-top:10px;line-height:1.6}
.info-port a{color:#185fa5;text-decoration:underline}
#portBox{margin-top:8px;display:flex;flex-wrap:wrap;gap:8px;align-items:center}
#portIn{width:96px;height:32px;padding:0 9px;font-size:14px;border-radius:8px;
border:0.5px solid var(--line);background:var(--card);color:var(--ink);
outline:none;-webkit-appearance:none}
/* 🔴 必须显式覆盖 min-height / font-size / font-weight / padding：
   全局 button 是 min-height:52px、font-size:16px、padding:14px 20px（给大按钮用的），
   只写 padding 和字号会让按钮被撑到 52px 高，跟 32px 的输入框严重不协调 */
#portSave{height:32px;min-height:32px;padding:0 14px;font-size:13px;font-weight:500;
line-height:1;border-radius:8px;border:0.5px solid var(--line);
background:var(--card);color:var(--ink);cursor:pointer}
#portSave:active{background:var(--bg)}
.port-range{flex:1 0 100%;font-size:11.5px;color:var(--ink3);line-height:1.55}
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
/* 操作历史列表 */
.his-item{padding:10px 0;border-bottom:0.5px solid var(--line)}
.his-item:last-child{border-bottom:0}
.his-l1{display:flex;align-items:center;gap:10px}
.his-name{font-size:14.5px;color:var(--ink);flex:1;min-width:0;overflow:hidden;
text-overflow:ellipsis;white-space:nowrap}
.his-op{flex:none;font-size:11px;padding:1.5px 8px;border-radius:8px}
.op-freeze{color:var(--red);background:var(--redbg)}
.op-unfreeze{color:var(--green);background:var(--greenbg)}
.op-restrict{color:#854f0b;background:#faeeda}
.op-unrestrict{color:var(--green);background:var(--greenbg)}
/* 深色模式跟随「已限制」胶囊（.tag.w）的暗色配色 */
html.dark .op-restrict{background:#3a2a12;color:#fac775}
.his-l2{font-size:11.5px;color:var(--ink3);margin-top:3px;overflow:hidden;
text-overflow:ellipsis;white-space:nowrap}
.his-empty{font-size:13.5px;color:var(--ink3);text-align:center;padding:30px 0}
.hd-save{margin-left:auto;min-height:34px;padding:0 15px;border-radius:9px;font-size:15px;
font-weight:600;color:#fff;background:#0f6e56}
.hd-save:active{opacity:.85}
#milletClose{margin-left:4px}
/* 标题旁的 ⓘ 点开才展开说明，不占常驻空间 */
.sheet-hd h2{display:flex;align-items:center;gap:5px}
.sheet-hd h2 .i-dot{cursor:pointer;padding:2px 0}
.panel-loading{padding:48px 16px;text-align:center;font-size:13px;color:var(--ink3);letter-spacing:.3px}
.millet-note{font-size:11.5px;color:var(--ink3);line-height:1.6;background:var(--bg);
margin:8px 16px 0;padding:9px 12px;border-radius:10px}
.sheet-body{flex:1;overflow-y:auto;padding:8px 16px calc(16px + env(safe-area-inset-bottom))}
/* 滚动条：Android WebView 默认几乎看不见，显式画一条细的 —— 拖动时能看出当前位置，
   也提示「这里还能往下滑」。主列表和弹窗共用同一套。 */
.sheet-body::-webkit-scrollbar,#list::-webkit-scrollbar{width:3px}
.sheet-body::-webkit-scrollbar-track,#list::-webkit-scrollbar-track{background:transparent}
.sheet-body::-webkit-scrollbar-thumb,#list::-webkit-scrollbar-thumb{background:var(--ink3);border-radius:2px}
.sheet-body::-webkit-scrollbar-thumb:active,#list::-webkit-scrollbar-thumb:active{background:var(--ink2)}
.trow{display:flex;align-items:center;gap:12px;padding:13px 0;border-bottom:0.5px solid var(--line)}
.trow .tl{font-size:15px;color:var(--ink)}
.trow .ts{font-size:11.5px;color:var(--ink3);margin-top:3px}
.trow.disabled{opacity:.45}
.trow .tv{font-size:13px;color:var(--ink3);font-family:ui-monospace,monospace}
.tsect{font-size:12px;color:var(--ink3);margin:18px 0 2px}
/* 所有按钮一套尺寸：字号 15px、高 28px。 */
.edit{flex:none;font-size:15px;min-height:28px;padding:0 14px;border-radius:8px;
background:var(--card);border:0.5px solid var(--line);color:var(--ink2)}
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
/* 文本单独包一层：省略号只在左边文字上做，右侧「N 进程 ›」永远完整显示 */
.mem-t{display:flex;align-items:center;min-width:0}
.mem-x{min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.mem .ago{color:var(--ink3);font-family:ui-monospace,monospace}
/* 多进程标记：蓝色可点，点开看进程明细。
   用 inline-flex 让文字和箭头自动垂直居中 —— 之前靠 vertical-align 微调，
   不同字号下总会差一点；flex 的 align-items:center 是真正的中线对齐 */
.mem .proc-btn{display:inline-flex;align-items:center;flex:none;white-space:nowrap;
color:var(--blue);font-family:ui-monospace,monospace;cursor:pointer}
.mem .proc-btn .chev{width:12px;height:12px;margin-left:2px;flex:none;
transition:transform .15s}
.mem .sep{flex:none;margin:0 4px;color:var(--ink3)}
.mem .proc-btn.open{color:var(--ink3)}
.mem .proc-btn.open .chev{transform:rotate(90deg)}
/* 进程展开区：必须 display:block —— 全局 li 是 flex，不覆盖的话
   几行进程会被横向并排，超出部分直接看不见。
   min-height:0 同理：全局 li 有 min-height:68px，一行进程也会撑出 68px 的大空白 */
.proc-wrap{display:block;list-style:none;background:var(--card);min-height:0;
padding:4px 14px 8px 44px;position:relative}
/* 用 grid 定列：名称吃剩余宽度，时间/内存/oom 按内容自适应 ——
   max-content 会取该列所有行里最宽的内容，所以多行之间照样对齐，
   又不会像固定宽度那样留出一大块空白 */
.proc-row{display:grid;grid-template-columns:6px minmax(0,1fr) max-content max-content max-content;
gap:10px;align-items:center;padding:5px 0;font-size:12px;
font-family:ui-monospace,monospace;color:var(--ink3)}
/* 点默认透明：不活跃的进程不放灰点，但保留这一列的宽度，多行名称才对齐 */
.proc-dot{width:6px;height:6px;border-radius:50%;background:transparent}
.proc-dot.live{background:#12b76a;box-shadow:0 0 0 2px rgba(18,183,106,.16);
animation:dotPulse 2.2s ease-in-out infinite}
.proc-nm{min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;color:var(--ink2)}
.proc-cpu{text-align:right;color:var(--ink3)}
.proc-mem{text-align:right;color:var(--ink3)}
.proc-oom{text-align:right;color:var(--ink3)}
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
.ext{color:#0f6e56;text-decoration:none;padding:6px 2px;border-radius:6px}
.ext:active{background:var(--track)}
.ext-sep{color:var(--ink3);font-size:12px}
.ext-note{font-size:11.5px;color:var(--ink3);line-height:1.55;padding:0 0 6px}
/* 版本号：构建时由 gen_assets.py 把 __APP_VERSION__ 换成 manifest 里的 versionName */
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
.mclear{flex:none;min-height:34px;padding:0 14px;font-size:14px;font-weight:500;
border-radius:10px;background:var(--bg);color:var(--ink);border:0.5px solid var(--line)}
button{border:0;border-radius:12px;padding:14px 20px;font-size:16px;font-weight:500;min-height:52px;
font-family:inherit;cursor:pointer;white-space:nowrap}
button:disabled{opacity:.45}
.primary{background:#0f6e56;color:#fff}
.ghost{background:var(--bg);color:var(--ink);border:0.5px solid var(--line)}
/* z-index 必须高于设置页(sheet=50)和弹窗(modal=90)：
   以前是 40，在设置页里点「开始」时提示条被整页盖住，一条都看不见 */
#toast{position:fixed;left:50%;bottom:116px;transform:translateX(-50%) translateY(10px);
/* 跟随主题：浅色主题用浅底深字，深色主题用深底浅字。
   以前写死 #2c2c2a 黑底，浅色主题下很突兀，也和系统 Toast 不一致 */
background:var(--card);color:var(--ink);border:0.5px solid var(--line);
box-shadow:0 6px 24px rgba(0,0,0,.14);
padding:12px 18px;border-radius:16px;font-size:14px;opacity:0;
transition:opacity .2s,transform .2s;pointer-events:none;z-index:95;
width:70vw;max-width:70vw;box-sizing:border-box;text-align:center}
html.dark #toast{box-shadow:0 6px 24px rgba(0,0,0,.5)}
#toast.show{opacity:1;transform:translateX(-50%) translateY(0)}
/* 长文案（失败原因这类）：单行居中读不动，而且窄框会把它顶成一条竖长条 */
#toast.long{width:88vw;max-width:88vw;text-align:left;
line-height:1.55;font-size:13.5px}
/* 索引引导条：没有索引 / 索引没建完时挂在列表上方，点一下进设置页去构建 */
.idxhint{flex:none;margin:8px 16px 2px;padding:10px 12px;border-radius:11px;
background:var(--bluebg);color:var(--ink);border:0.5px solid var(--line);
font-size:13px;line-height:1.5;cursor:pointer}
.idxhint:active{opacity:.7}
.idxhint b{font-weight:600;color:var(--blue)}
/* 「不再提示」：蓝字、与正文同基线、右对齐，不抢主文案的注意力 */
.idxhint .hint-off{display:inline-block;margin-left:8px;color:var(--blue);
text-decoration:underline;font-size:12px;white-space:nowrap}
.empty{padding:60px 20px;text-align:center;color:var(--ink3);font-size:16px}
.sortbar{display:flex;gap:8px;margin-top:8px}
.sec{font-size:13px;color:var(--ink2);font-weight:500;margin:16px 0 5px}
/* 设置页：标签放左边、按钮放右边，一行搞定，比「标题一行 + 按钮一行」省一半竖向空间 */
.setrow{display:flex;align-items:center;gap:36px;margin:17px 0}
.setrow-lb{flex:none;width:84px;font-size:13px;color:var(--ink2);font-weight:500;line-height:1.3;white-space:nowrap;display:flex;align-items:center;gap:1px}
.setrow .sortbar{flex:1;margin-top:0;gap:10px}
.setrow-unit{flex:none;font-size:12px;color:var(--ink3)}
.upd-auto{flex:1;min-width:0;margin-left:8px;text-align:right;font-size:12px;color:var(--ink2);overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
/* 版本号与开源/打赏链接同行：左边版本、右边链接 */
.ver-row{display:flex;align-items:center;gap:10px;margin-top:14px;font-size:11.5px;color:var(--ink3)}
.ver-links{margin-left:auto;flex:none;display:flex;align-items:center;gap:6px}
.ver-links .ext{font-size:11.5px;padding:2px 0}
.info-note a{color:#0f6e56;text-decoration:none}
/* 更新按钮兼作进度条：用背景渐变填充，不改 DOM 结构 */
.edit.prog{background-image:linear-gradient(to right,#cfe8dc var(--p,0%),transparent var(--p,0%));
  transition:background-image .2s linear}
#appTitle{cursor:pointer;display:flex;align-items:center;gap:5px;min-width:0}
#appNameTx{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
/* 三处 ⓘ 提示统一用同一个字符字形，保证视觉大小完全一致（11.5px = 电池行/性能模式行字号） */
.idx-note{font-size:11.5px;color:var(--ink3);line-height:1.55;margin-top:5px}
.choice-btns{display:flex;flex-direction:column;gap:8px;padding:0 4px 4px}
.choice-btn{width:100%;padding:11px 12px;border-radius:10px;border:0.5px solid var(--line);background:var(--card);color:var(--ink);font-size:14px;cursor:pointer}
.choice-btn.primary{background:#0f6e56;border-color:#0f6e56;color:#fff}
.choice-btn:active{opacity:.85}
@keyframes pulseBtn{0%,100%{transform:scale(1);box-shadow:0 0 0 0 rgba(15,110,86,.45)}50%{transform:scale(1.14);box-shadow:0 0 0 9px rgba(15,110,86,0)}}
.pulse{animation:pulseBtn 1s ease-in-out 4}
.loc-btn{flex:none;margin-left:6px;display:inline-flex;align-items:center;justify-content:center;width:26px;height:26px;padding:0;border-radius:50%;background:var(--card);border:0.5px solid var(--line);color:var(--ink2);cursor:pointer;-webkit-tap-highlight-color:transparent}
.loc-btn:active{background:var(--bg)}
.loc-btn svg{width:14px;height:14px;fill:none;stroke:currentColor;stroke-width:1.8;stroke-linecap:round;stroke-linejoin:round}
.i-dot{flex:none;font-style:normal;font-size:11.5px;line-height:1;color:var(--ink3);
transition:color .12s}
#appTitle:active .i-dot{color:var(--ink2)}
.tip-i{font-style:normal}
body.no-info .tip-i{display:none}
/* 白名单面板标题上的 ⓘ 是查看说明的唯一入口，不能跟着界面提示一起隐藏 */
body.no-info #milletInfo{display:inline-flex}
/* 设置页的图标选择器 */
.icon-grid{display:grid;grid-template-columns:repeat(3,1fr);gap:7px;margin-top:6px}
.icon-cell{position:relative;display:flex;flex-direction:column;align-items:center;gap:3px;
padding:6px 4px 5px;border-radius:10px;background:var(--card);border:1.5px solid var(--line)}
.icon-cell img{width:42px;height:42px;border-radius:10px;display:block;
box-shadow:0 0 0 0.5px rgba(0,0,0,.12)}
.icon-cell span{font-size:11px;color:var(--ink3);line-height:1}
.icon-cell.on{border-color:#0f6e56}
.icon-cell.on span{color:#0f6e56;font-weight:600}
/* 分段按钮与 .edit 完全对齐：同字号(15px)、同高(28px)、同字重(500)。
   🔴 字重以前是 400、选中态 500，而「开始/检查」那类 .edit 继承全局 button 的 500，
   于是同一屏里有的粗有的细，同一行里选中和未选中也不一样粗。现在统一 500。 */
.seg{flex:1;background:var(--card);border:0.5px solid var(--line);border-radius:8px;
padding:0;font-size:15px;color:var(--ink2);min-height:28px;font-weight:500}
.seg.on{background:#0f6e56;color:#fff;border-color:#0f6e56}
.tag.w{background:#faeeda;color:#854f0b}
/* 白名单：排在「已限制」上方，单独一个胶囊 */
.tag.b{background:#e3f0eb;color:#0f6e56}
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
    <button class="refresh" id="btnRefresh" aria-label="刷新"><svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true"><path fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" d="M20 12a8 8 0 1 1-8-8"/><path fill="currentColor" d="M15.6 4 11.2 1.4v5.2z"/></svg></button></div>
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
<div class="idxhint" id="idxHint" style="display:none"></div>
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
<div id="alert" class="sheet">
  <div class="sheet-hd"><h2 id="alertTitle">提示</h2>
    <button class="close" id="alertClose">×</button></div>
  <div class="sheet-body" id="alertBody"></div>
</div>
<div id="tune" class="sheet">
  <div class="sheet-hd"><h2 data-t="tuneTitle">性能调优</h2><button class="close" id="tuneClose">×</button></div>
  <div class="sheet-body" id="tuneBody"></div>
</div>
<div id="millet" class="sheet">
  <div class="sheet-hd"><h2><span data-t="milletTitle">Millet 白名单</span><i class="i-dot tip-i" id="milletInfo" role="button" aria-label="info">ⓘ</i></h2>
    <button class="hd-save" id="milletSave" data-t="save">保存</button>
    <button class="close" id="milletClose">×</button></div>
  <div class="millet-note" id="milletNote" style="display:none" data-t="milletNote">把应用加进系统的 doze / standby 白名单（MILLET_NO_RESTRICT_APP），系统不再按「长时间未使用」压制它的后台活动。注意：这不改变 MIUI 界面里的「省电策略」—— 那个开关是 powerkeeper 自己维护的另一套状态，需要到应用信息页单独设成「无限制」。</div>
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
  <div class="sheet-hd"><h2 data-t="settings">设置</h2>
    <button class="close" id="setHistory" aria-label="history" style="margin-left:auto;padding:0;display:flex;align-items:center;justify-content:center">
      <svg viewBox="0 0 24 24" width="17" height="17" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="12" cy="12" r="9"/><path d="M12 7v5l3.5 2"/></svg>
    </button>
    <button class="close" id="setClose" style="margin-left:16px">×</button></div>
  <div class="sheet-body">
    <div class="setrow">
      <span class="setrow-lb" data-t="secName">应用名称</span>
      <div class="sortbar" id="nameBar">
        <button class="seg" data-name="mine">Mine App</button>
        <button class="seg" data-name="manager">应用管理器</button>
      </div>
    </div>
    <div class="setrow">
      <span class="setrow-lb" data-t="secTheme">主题</span>
      <div class="sortbar" id="themeBar">
        <button class="seg" data-theme="auto" data-t="themeAuto">自动</button>
        <button class="seg" data-theme="light" data-t="themeLight">浅色</button>
        <button class="seg" data-theme="dark" data-t="themeDark">深色</button>
      </div>
    </div>
    <div class="setrow">
      <span class="setrow-lb" data-t="secLang">语言</span>
      <div class="sortbar" id="langBar">
        <button class="seg" data-lang="auto" data-t="langAuto">自动</button>
        <button class="seg" data-lang="zh" data-t="langZh">中文</button>
        <button class="seg" data-lang="en" data-t="langEn">English</button>
      </div>
    </div>
    <div class="sec" id="iconSec" style="display:flex;align-items:center;margin-bottom:12px">
      <span data-t="secIcon">应用图标</span>
      <a href="#" id="iconToggle" style="margin-left:auto;color:#0f6e56;font-size:12px;
         text-decoration:none;cursor:pointer">展开</a>
    </div>
    <div class="icon-grid" id="iconGrid"></div>
    <div class="setrow">
      <span class="setrow-lb" data-t="battDesign">电池设计容量</span>
      <div style="flex:1;display:flex;align-items:center;gap:8px">
        <input id="battDesignIn" type="number" inputmode="numeric" min="1000" max="30000" step="100"
               placeholder="5000"
               style="flex:1;min-width:0;text-align:right;padding:8px 12px;font-size:14px;
                      border:0.5px solid var(--line);border-radius:10px;
                      background:var(--card);color:var(--ink);outline:none;-webkit-appearance:none">
        <span class="setrow-unit">mAh</span>
      </div>
    </div>
    <div class="info-note" style="border-top:0;padding-top:0;margin-top:2px">
      <span data-t="battDesignNote" data-html="1">部分机型无法读取，建议&nbsp;&nbsp;<a href="#" id="lnkBattSet">查看</a>&nbsp;&nbsp;后手动填写，默认值 5000。</span>
    </div>
    <div class="setrow" style="margin-bottom:0;gap:8px">
      <span class="setrow-lb" data-t="keepCard">显示后台卡片</span>
      <label class="switch">
        <input type="checkbox" id="showCardChk"><span class="slider"></span>
      </label>
      <span class="setrow-lb" style="width:auto;margin-left:auto;white-space:nowrap">
        <span data-t="secTip">显示界面提示</span><i class="i-dot" id="tipHelp" role="button">(ⓘ)</i>
      </span>
      <label class="switch">
        <input type="checkbox" id="tipChk"><span class="slider"></span>
      </label>
    </div>
    <div class="info-note" style="border-top:0;padding-top:0;margin-top:4px;margin-bottom:12px">
      <span data-t="keepNote" data-html="1">显示后台卡片易导致被误杀，默认不显示，可按需开启。后台只做少量 shell 查询，能耗极低，可放心常驻。建议手动允许本 App 的&nbsp;&nbsp;<a href="#" id="lnkNotif">通知</a>&nbsp;&nbsp;和&nbsp;&nbsp;<a href="#" id="lnkAuto">自启动</a>&nbsp;&nbsp;，及&nbsp;&nbsp;<a href="#" id="lnkPower">电源管理设置无限制</a>&nbsp;&nbsp;，可进一步防止被误杀。</span>
    </div>

    <div class="setrow" style="gap:12px;margin:12px 0 0;align-items:center">
      <div class="setrow-lb" data-t="idxTitle" style="width:auto;flex:1;min-width:0;white-space:normal;line-height:1.4">初始化应用查找功能</div>
      <button class="edit" id="idxBtn" data-t="idxStart" style="padding:0 14px;flex:none">开始</button>
    </div>
    <div class="idx-note" data-t="idxNote" data-html="1" style="margin:4px 0 0">遍历桌面建立应用位置索引，用于应用查找，可随时打断、续建。推荐无障碍路径，速度更快。</div>


    <div class="setrow" id="chanRow" style="gap:8px;margin:14px 0 0;align-items:center;display:none">
      <div class="setrow-lb" data-t="chanTitle" style="width:auto;flex:none;white-space:nowrap;line-height:1.4">执行通道</div>
      <span id="chanState" style="flex:none;font-size:13px;color:var(--ink3)">—</span>
      <!-- Shizuku 授权状态并到本行右端：已授权绿、未授权灰，未授权时才出现「授权」按钮 -->
      <span id="szState" style="margin-left:auto;flex:none;font-size:12.5px;color:var(--ink3);white-space:nowrap"></span>
      <button class="edit" id="szBtn" data-t="szAsk" style="padding:0 12px;flex:none;margin-left:6px;display:none">授权</button>
    </div>
    <div id="chanBtns" style="display:none;gap:8px;margin:8px 0 0;flex-wrap:wrap"></div>

    <div class="setrow" style="gap:8px;margin:8px 0 2px">
      <span class="setrow-lb" id="updLabel" data-ver="__APP_VERSION__">v —</span>
      <button class="edit" id="updBtn" data-t="updCheck" style="padding:0 12px">检查</button>
      <span class="upd-auto" data-t="updAuto">每 24 小时自动检查</span>
      <label class="switch"><input type="checkbox" id="updAutoChk"><span class="slider"></span></label>
    </div>
    <div class="ver-row">
      <span data-t="updPermNote">更新可自动继承 adb 授权。</span>
      <span class="ver-links">
        <a class="ext" id="lnkRepo" href="https://github.com/henry-97/MineApp">GitHub GPL-3.0 开源</a>
        <span class="ext-sep">|</span>
        <a class="ext" id="lnkFund" href="https://github.com/henry-97/MineApp#sponsor">打赏</a>
      </span>
    </div>
    <div class="ext-note" style="margin-top:9px" data-t="extLicense">本项目(GPL-3.0)禁止自行闭源分发，如需商务合作，可提 Issue。</div>
  </div>
</div>
<!-- 操作历史：从设置页右上角的时钟按钮打开 -->
<div id="history" class="sheet">
  <div class="sheet-hd">
    <h2 data-t="hisTitle">操作历史</h2>
    <button id="hisClear" data-t="hisClear"
            style="margin-left:12px;padding:0 2px;background:none;border:0;font-size:13.5px;color:var(--ink3)">清空</button>
    <button class="close" id="hisClose" style="margin-left:auto">×</button>
  </div>
  <div class="sheet-body">
    <div class="setrow" style="margin-top:0;gap:8px">
      <span class="setrow-lb" data-t="hisLimitLb" style="width:auto;flex:none">保存条数</span>
      <input id="hisLimit" type="text" inputmode="numeric" autocomplete="off" value="200"
             style="width:60px;height:30px;flex:none;padding:0 7px;text-align:right;font-size:14px;border-radius:8px;border:0.5px solid var(--line);background:var(--card);color:var(--ink);-webkit-appearance:none">
      <span data-t="hisLimitNote"
            style="font-size:11.5px;color:var(--ink3);white-space:nowrap">0=不保存，最大值10000</span>
    </div>
    <div id="hisList" style="margin-top:12px"></div>
  </div>
</div>
<div id="risk" class="risk">
  <div class="risk-box">
    <h3 data-t="riskTitle">使用前请留意</h3>
    <p data-t="riskBody">本软件通过 ADB 直接调用系统命令，能力很强。请勿随意冻结或限制系统必要组件（时钟、桌面启动器、输入法等），否则可能导致闹钟不响、桌面异常、收不到来电等。</p>
    <a class="risk-link" id="lnkTerms" href="https://github.com/henry-97/MineApp/blob/main/docs/TERMS.md" data-t="riskLink">查看完整「使用须知」→</a>
    <p class="risk-foot" data-t="riskFoot">继续使用本软件，即表示自行承担因使用不当造成的风险。</p>
    <div class="risk-btns">
      <button class="ghost" id="riskQuit" data-t="riskQuit">退出</button>
      <button class="primary" id="riskOk" data-t="riskAgree">同意并使用</button>
    </div>
  </div>
</div>
<script>
/* 每次安装（versionCode 变化）只提示并确认一次。
   按 versionCode 判断而不是布尔标记：覆盖安装时 localStorage 会保留，
   只认"同意过"就再也不会弹；只认布尔 false 又每次都弹。 */
/* 只在「全新安装」时提示：标记落 localStorage（覆盖安装保留、卸载清空）。
   老版本用的是按 versionCode 记的 riskOkVer，这里做一次兼容迁移。 */
/* 只在「全新安装」时提示：标记同时落 localStorage 与 SharedPreferences。
   localStorage 会被 WebView 在存储紧张时清掉（实测「过段时间又弹一次」），
   所以 SharedPreferences 那份才是权威，localStorage 只是让首屏不必等异步查询。 */
function riskDecide(showIfNone){
  if (localStorage.getItem('riskOk') === '1' || localStorage.getItem('riskOkVer')) {
    localStorage.setItem('riskOk', '1');
    return;                                  // 本地记着，直接放行
  }
  // 本地没了 → 问后端要权威答案，避免每次都重弹
  fetch('/api/riskok', {method:'POST'})
    .then(function(r){ return r.json(); })
    .then(function(d){
      if (d && d.ok){
        localStorage.setItem('riskOk', '1'); // 补回本地，下次不用再问
        return;
      }
      if (showIfNone) document.getElementById('risk').classList.add('show');
    })
    .catch(function(){
      if (showIfNone) document.getElementById('risk').classList.add('show');
    });
}
riskDecide(true);

</script>
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

/* 从设备批量拉回 AOT 状态，补进 localStorage 记录。
   重装 / pm clear 后本地记录会清空，但设备上的编译结果还在 —— 不同步就会全显示「未开启」。 */
var aotSynced = false;
function syncAot(){
  fetch('/api/aotsync', {method:'POST'})
    .then(function(r){ return r.json(); })
    .then(function(m){
      if (!m || typeof m !== 'object') return;
      var changed = false;
      for (var i = 0; i < apps.length; i++){
        var a = apps[i];
        if (m[a.pkg] === 'speed'){            // speed = 全量 AOT；speed-profile 只是按 profile
          var k = aotKey(a);
          if (!aotDone.has(k)){ aotDone.add(k); changed = true; }
        }
      }
      if (changed){ saveAot(); render(); }
    })
    .catch(function(){});
}
/* AOT 记录按「包名@versionCode」存：应用一更新，versionCode 变了，记录自动失效 ——
   因为 dexopt 产物和 APK 内容绑定，更新后系统会退回 speed-profile，
   继续显示「已编译」就是骗人。 */
function aotKey(a){ return a.pkg + '@' + (a.ver || 0); }
/* 全量编译撞过 570 秒上限的应用（按 pkg@versionCode 记）。
   大应用点一下要白等 10 分钟，还必然失败 —— 再点之前先提醒。 */
var aotTimeout = new Set(JSON.parse(localStorage.getItem('aotTimeout') || '[]'));
function saveAotTimeout(){ localStorage.setItem('aotTimeout', JSON.stringify(Array.from(aotTimeout))); }
var busy = false;
/* 列表刷新中：这期间点定位按钮会落空（列表正在重建 DOM），
   所以先把请求排起来，刷新结束立刻补执行，并给用户一句明确提示。 */
var refreshing = false;
var pendingLoc = null;
function flushPendingLoc(){
  if (!pendingLoc) return;
  var p = pendingLoc;
  pendingLoc = null;
  doLocate(p.pkg, p.label, p.force, p.c, p.now);
}
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
    tagArchived: '已归档', tagRestricted: '已限制', tagWhite: '白名单', tagSystem: '系统', tagNormal: '正常',
    milletNow: '当前名单 {n} 个',
    milletNote: '把应用加进系统的 doze / standby 白名单（MILLET_NO_RESTRICT_APP），系统不再按「长时间未使用」压制它的后台活动。注意：这不改变 MIUI 界面里的「省电策略」—— 那个开关是 powerkeeper 自己维护的另一套状态，需要到应用信息页单独设成「无限制」。',
    cfmMillet: '保存后白名单会从 {a} 个变成 {b} 个（整体覆盖），确定吗？',
    agoNever: '>24h',
    procs: '进程',
    procMain: '主进程',
    procMulti: '多开',
    procLoading: '加载中…',
    procNone: '没有进程',
    bannerOff: '等待调试通道连接后即可刷新应用列表', bannerOffHint: '手机重启后需在电脑上执行：adb tcpip {p}',
    bannerOffSafe: '已设置的归档与限制依然生效，待方便时再授权即可',
    bannerUnauth: '确认授权中', bannerUnauthHint: '解锁手机，勾选「一律允许使用这台计算机进行调试」再点「允许」',
    bannerOffline: '设备离线', bannerOfflineHint: '重新执行一次：adb tcpip {p}',
    bannerErr: '调试通道异常', bannerErrHint: '请按下方指引重新开启',
    bannerGuide: '查看操作指引 ›',
    guideTitle: '开启调试通道',
    guide1: '设置 → 关于手机 → 连点「版本号」7 次，开启开发者选项',
    guide2: '设置 → 更多设置 → 开发者选项 → 打开「USB 调试」',
    guide3: '用数据线连接电脑，在电脑上执行：',
    guide4: '回到本应用，点右上角刷新',
    guide5: '手机弹出「允许 USB 调试吗」时，勾选「一律允许」再点确定',
    portAsk: '自定义其他 adb tcp 端口？', portSet: '点此设置', portSave: '保存',
    portRange: '范围 1024–65535。改完请在电脑上执行上面对应的命令，端口要与这里一致。',
    portBad: '请填 1024–65535 之间的端口',
    portSaved: '端口已改为 {p}，请在电脑上执行：adb tcpip {p}',
    guideNote: '<b>一次限制，永久生效。</b>重启后即使暂未授权，已设置的归档与限制依然有效；不授权只是暂时读不到应用列表、不能新设限制，待方便时再授权（即执行步骤 3、5）即可。<br>软件更新（覆盖安装）不会丢授权 —— adb 的授权记录在系统侧，与安装包无关。',
    ok: '知道了', cancel: '取消', confirm: '确定',
    battTitle: '电池健康度 {p}',
    battLevel: '当前电量', battSys: '系统状态',
    battMax: '历史最高', battLast: '最近一次学习', battMin: '历史最低（采用值）',
    battDesign: '电池设计容量',
    battDesignNote: '部分机型无法读取，建议&nbsp;&nbsp;<a href="#" id="lnkBattSet">查看</a>&nbsp;&nbsp;后手动填写，默认值 5000。',
    battDesignSaved: '已保存：{n} mAh',
    battDesignCleared: '已清空，回到自动估算',
    battDesignBad: '请填 1000~30000 之间的数值',
    battDesignLow: '检测到设计容量（{d} mAh）比实测学习容量（{m} mAh）还小，健康度已按 100% 封顶。建议核对机型参数后重新填写。',
    /* 🔴 这四个键是**动态拼接**用的（'battDesign' + 'User'），
       代码里不会出现带引号的 'battDesignUser'，按「未被引用」删掉会让界面
       直接显示成 battDesignUser 这种原始键名（实测踩到）。别删。 */
    battDesignAuto: '系统读取',
    battDesignDefault: '系统读不到，估算',
    battDesignDevice: '按机型表匹配',
    battDesignUser: '手动设置',
    battNote: '系统在每次完整充电后，会用实际充入的电量反推电池当时的真实满电容量，记为一个学习值。<br>健康度 = 学习容量 ÷ 设计容量。<br>因为电池健康只会下降不会回升，这里取<b>历史最低值 Min</b> 作为依据，避免个别偏高的学习值把结果拉高。<br>这个口径偏保守，结果可能略低于实际值，但偏差在可控范围内。'
      + '<br><br><b>关于数值波动</b>：首次检测结果可能滞后或有波动，这是系统的学习机制决定的 —— 它需要若干个完整的充放电周期才能收敛。'
      + '<b>日常使用一周左右</b>即可得到比较稳定的值；刚装好时看到的数字仅供参考，不必在意一两天的跳动。',
    tSaved: '已保存 {n} 个应用', tCompiling: '正在编译，请稍候',
    reverting: '还原中',
    tFail: '操作失败：{m}', tCompiled: '预编译完成 {n}s', tReverted: '已取消预编译 {n}s',
    tAlready: '已经是 AOT 状态，无需重复编译',
    tNotAot: '本来就没有全量编译，无需取消',
    cfmAotTimeout: '「{n}」上次全量编译超时了（系统上限 9.5 分钟，这个应用太大编不完）。\n系统默认的 speed-profile 已经在生效，不影响日常使用。\n再试一次吗？',
    tLowMem: '可用内存只剩 {n}M，全量编译几乎一定失败，请先清理后台',
    cfmKill: '清理「{n}」的后台进程？仅清理后台，不影响应用自启动。',
    killDontAsk: '下次不再提示',
    tKilled: '已清理「{n}」，释放 {m}M · 稍后列表自动刷新',
    tKilledZero: '已清理「{n}」，未释放运存 · 稍后列表自动刷新',
    tKillIdle: '「{n}」当前没有后台进程',
    tKillFail: '清理失败：{m}',
    tCleared: '已清空选择', tConnFail: '无法连接服务：{m}', tAdbOff: '调试未连接',
    tPerfNoAdb: '调试未连接，性能模式未生效',
    tPerfOn: '性能模式已开启', tPerfOff: '性能模式已关闭',
    tNameOk: '名称已更新，桌面图标稍后刷新', tNameFail: '已保存，桌面名称未能修改',
    tuneTitle: '性能调优',
    tunePerf: '性能模式', tunePerfSub: '关闭省电与均衡，优先性能',
    tuneSave: '省电模式', tuneSaveSub: '限制后台活动以延长续航',
    tuneBal: '均衡模式', tuneBalSub: '默认的功耗与性能折中',
    tuneGpu: 'GPU 调优', tuneGame: '游戏周期优化',
    tuneGpuSub: '系统默认已开启，不建议手动修改',
    tuneGameSub: '系统默认已开启，不建议手动修改',
    milletTitle: 'Millet 白名单', milletSub: '豁免后台限制的应用，当前 {n} 个',
    edit: '编辑', save: '保存',
    rootNote: '以下项目需要 root，当前仅显示',
    rtName: '实时调度 RT', tempLimit: '温度限制阈值', bootMode: '开机默认模式', governor: 'CPU 调度器',
    settings: '设置', secName: '应用名称', secTheme: '主题', secLang: '语言',
    hisTitle: '操作历史', hisLimitLb: '保存条数',
    hisLimitNote: '0=不保存，最大值10000',
    hisEmpty: '还没有操作记录', hisClear: '清空',
    hisClearAsk: '确定清空全部操作记录？此操作不可撤销。',
    op_freeze: '归档', op_unfreeze: '还原', op_restrict: '限制', op_unrestrict: '解限',
    themeAuto: '自动', themeLight: '浅色', themeDark: '深色',
    langAuto: '自动', langZh: '中文', langEn: 'English',
    perfTip: '性能模式',
    perfNote: '「性能模式」不是费电开关 —— 它只是<b>解除主频上限</b>，并不会让 CPU 一直满血跑。前台活干得快，几秒就回落到低频，比被压着主频慢慢磨更省。<br>真正吃待机的是后台偷偷唤醒、联网、刷新。「省电 / 均衡」压主频，前台同样的事要跑更久、后台该醒还是醒，反而<b>又卡又费电</b>。<br>推荐组合：<b>性能模式 + 限制用不着的应用</b>。前台快、后台静，两头都省。',
    secTip: '显示界面提示', tipHelp: '控制界面上那些 ⓘ 提示要不要显示。', tipShow: '显示', tipHide: '隐藏',
    keepCard: '显示后台卡片',
    keepNote: '本App实测后台能耗低到测不出，而显示卡片易被误杀，按需开启。建议允许&nbsp;&nbsp;<a href="#" id="lnkNotif">通知</a>&nbsp;&nbsp;和&nbsp;&nbsp;<a href="#" id="lnkAuto">自启动</a>&nbsp;&nbsp;，并将&nbsp;&nbsp;<a href="#" id="lnkPower">电源管理设为无限制</a>&nbsp;&nbsp;，以免被杀。',
    keepHidden: '已隐藏后台卡片', keepShown: '已显示后台卡片',
    keepFail: '系统没接受这个设置',
    extRepo: 'GitHub GPL-3.0 开源', extFund: '打赏',
    updCheck: '检查', updChecking: '检查中…',
    updCurrent: 'v {v}', updLatest: '已是最新版本 {v}',
    updLatestShort: '已是最新', updNewShort: '发现 v {v}',
    updFailShort: '检查失败', updCheckingShort: '检查中', updRestart: '将自动重启',
    updNew: '发现新版本 {v}', updOpen: '去下载', updFail: '检查失败，请检查网络',
    updPermNote: '更新可自动继承 adb 授权。',
    updInstall: '更新', updInstalling: '更新中', updFinishing: '完成', updDone: '完成',
    updInstallDone: '安装完成，应用即将重启', updInstallFail: '应用内安装失败，已改为浏览器下载',
    updAuto: '每 24 小时自动检查并更新',
    updAutoOn: '已开启自动检查更新', updAutoOff: '已关闭自动检查更新',
    extLicense: '本项目(GPL-3.0)禁止自行闭源分发；需商务合作请提 Issue。',
    riskTitle: '使用前请留意',
    riskBody: '本软件通过 ADB 直接调用系统命令，能力很强。请勿随意冻结或限制系统必要组件（时钟、桌面启动器、输入法等），否则可能导致闹钟不响、桌面异常、收不到来电等。',
    riskLink: '查看完整「使用须知」→',
    riskFoot: '继续使用本软件，即表示自行承担因使用不当造成的风险。',
    riskAgree: '同意并使用', riskQuit: '退出',
    secIcon: '应用图标', iconExpand: '展开', iconCollapse: '折叠', loading: '读取中…',
    secAgo: ' 秒前', minAgo: ' 分钟前', locating: '正在寻找应用（碰屏幕可停止）',
    locFound: '已找到 {n}', locNotFound: '没找到 {n}',
    idxTitle: '初始化应用查找功能', idxStart: '开始', idxRunning: '遍历中…', cancel: '取消',
    chanTitle: '执行通道',
    szTitle: 'Shizuku 授权', szAsk: '授权', szOk: 'Shizuku 已授权', szOff: 'Shizuku 未运行', szNoPerm: 'Shizuku 未授权',
    szNotRunning: 'Shizuku 没在运行：请先打开 Shizuku App，用「无线调试启动」或「root 启动」把它跑起来',
    szAskFail: '申请授权失败，请确认 Shizuku 已启动后重试',
    szGranted: '已获得 Shizuku 授权', szWait: '请在 Shizuku 的弹框里点「允许」',
    idxHintNone: '还没有建立应用索引 —— 查找应用会很慢。<b>点此构建</b>',
    idxHintOff: '不再提示',
    idxUnsupported: '检测到应用查找暂不支持本系统，建议反馈 issue，以便持续更新',
    idxNeedA11y: '请先开启无障碍：设置 → 已下载的应用 → MineApp应用查找（即将带你过去）',
    idxBridgeErr: '接口未注册：{m}（应用装的是旧版或构建异常，请反馈 issue）',
    idxA11yTitle: '未开启无障碍读取',
    idxA11yBody: '无障碍是最快的查找方式：直接读取屏幕控件，一次读取从 3 秒降到 0.3 秒。也可以先用经典方式（模拟操作 + 截图解析）建索引，速度会慢不少。',
    idxClassic: '经典查找',
    idxOpenA11y: '去开启无障碍',
    idxClassicOff: '经典查找不可用：当前系统不允许模拟点击',
    injectTitle: '开启「USB 调试（安全设置）」',
    injectText: '允许通过 USB 调试修改权限或模拟点击',
    injectBody: '系统拦截了模拟点击，应用查找无法继续。请到「开发者选项」打开「USB 调试（安全设置）」—— 它下面写着「允许通过 USB 调试修改权限或模拟点击」，不要开成上面那个「USB 调试」。打开后重启手机（这个开关必须重启才生效），再回来点「开始」。',
    idxHintPartial: '索引还没建完 —— 部分应用没有定位按钮。<b>点此继续</b>',
    locAskTitle: '还没初始化查找索引',
    locAskBody: '直接查找要一页一页翻桌面、逐个开文件夹，可能需要几十秒；初始化会把所有应用的位置记下来，之后查找几乎秒出。\n初始化约需几分钟，期间可以随时操作手机打断，下次接着来。建议先初始化。',
    locAskInit: '先初始化（推荐）', locAskDirect: '直接查找',
    locStaleTitle: '查找索引已过期',
    locStaleBody: '桌面变动之后，之前建的位置索引可能已经不准，定位会退化成逐页翻找，可能要几十秒。\n建议重建一次（几分钟），之后查找几秒内就能出结果。',
    locSlowTip: '没有可用索引，正在逐页查找，可能要几十秒。建议先到设置页「初始化应用查找功能」',
    locWaitRefresh: '正在刷新列表，完成后自动定位',
    idxNote: '遍历桌面建立应用位置索引，用于应用查找，可随时打断、续建。推荐无障碍路径，速度更快。 <b id="idxAt" style="color:var(--ink2);font-weight:600"></b>',
    idxAgeMin: '上次完成：{n} 分钟前', idxAgeHour: '上次完成：{n} 小时前', idxAgeDay: '上次完成：{n} 天前',
    idxDone: '索引完成，共 {n} 个应用', idxPaused: '已暂停，下次点「开始」接着来', idxFail: '索引构建失败，建议反馈 issue（附机型与桌面信息），以便持续更新',
    perfPausedTitle: '系统频繁关闭性能模式',
    perfPausedBody: '最近 1 分钟内被系统关闭了 {n} 次（{when}）。\n'
      + '为避免和系统反复拉锯，已暂停自动维持性能模式。\n'
      + '建议先到系统「省电与电池」里检查电源管理设置，或确认是否处于低电量 / 高温保护状态。\n'
      + '再次手动打开性能模式即可恢复自动维持。',
    icSq: '方块', icCl: '四叶草', icG: '墨绿', icY: '金色', icK: '黑色',
    tIconOk: '图标已切换，桌面可能需要几秒刷新', tIconFail: '图标切换失败',
    tBusy: '正在处理，请稍候',
    tNoArchivable: '所选应用中没有可归档的',
    tNoArchived: '所选应用中没有已归档的',
    tPickRestrict: '请先勾选要限制的应用',
    tPickUnrestrict: '请先勾选要解除限制的应用',
    cfmArchive: '归档所选的 {n} 个应用？\n\n普通应用只做禁用，数据完整保留；\n系统应用无法禁用，会卸载当前用户的安装。系统分区里的副本仍在，恢复后相当于重新安装 —— 应用能回来，但数据会丢。',
    cfmRestrict: '将限制 {n} 个应用的后台运行。\n\n效果：切到后台后系统会限制其 CPU、网络与唤醒，但这些应用可能因此收不到消息通知。',
    cfmUnrestrict: '恢复 {n} 个应用的后台运行权限？',
    cfmRelease: '还原 {n} 个应用？',
    running: '正在处理 {n} 个…',
    tDone: '{w}完成：成功 {ok} 个',
    tDoneFail: '，失败 {n} 个',
    tRunFail: '{w}失败：{m}',
    tUnknown: '未知错误',
    tAdbNoRun: '调试未连接，操作未执行',
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
    tagArchived: 'Archived', tagRestricted: 'Restricted', tagWhite: 'Allowlisted', tagSystem: 'System', tagNormal: 'Normal',
    milletNow: 'currently {n}',
    milletNote: 'Adds the app to the system doze / standby allowlist (MILLET_NO_RESTRICT_APP) so the OS stops throttling it as "unused". Note: this does NOT change the battery policy in MIUI\'s UI — that toggle is separate state kept by powerkeeper and has to be set to "No restrictions" on the app-info page.',
    cfmMillet: 'Saving replaces the allowlist: {a} → {b}. Continue?',
    agoNever: '>24h',
    procs: 'procs',
    procMain: 'main',
    procMulti: 'dual',
    procLoading: 'Loading…',
    procNone: 'No processes',
    bannerOff: 'Waiting for the ADB channel — the list refreshes once connected', bannerOffHint: 'After reboot, run on a PC: adb tcpip {p}',
    bannerOffSafe: 'Your archives and restrictions stay in effect — re-authorise whenever convenient',
    bannerUnauth: 'Authorizing…', bannerUnauthHint: 'Unlock the phone, tick "Always allow from this computer" then tap Allow',
    bannerOffline: 'Device offline', bannerOfflineHint: 'Run again: adb tcpip {p}',
    bannerErr: 'ADB channel error', bannerErrHint: 'Follow the guide below to re-enable',
    bannerGuide: 'Setup guide ›',
    guideTitle: 'Enable the ADB channel',
    guide1: 'Settings → About phone → tap "Version" 7 times to unlock Developer options',
    guide2: 'Settings → Additional settings → Developer options → turn on "USB debugging"',
    guide3: 'Connect to a PC with a cable, then run:',
    guide4: 'Return to this app and tap refresh at the top right',
    guide5: 'When "Allow USB debugging?" appears, tick "Always allow" and confirm',
    portAsk: 'Use a different adb tcp port?', portSet: 'Set it', portSave: 'Save',
    portRange: 'Range 1024–65535. After saving, run the command above on your PC with the same port.',
    portBad: 'Enter a port between 1024 and 65535',
    portSaved: 'Port set to {p} — run on your PC: adb tcpip {p}',
    guideNote: '<b>Restrict once, permanent effect.</b> After a reboot your archives and restrictions stay in force even before you re-authorise — without authorisation you simply cannot read the app list or add new restrictions, so re-authorise whenever convenient (i.e. redo steps 3 and 5).<br>A covering install does not lose the authorisation — the adb grant lives in system state, not in the APK.',
    ok: 'Got it', cancel: 'Cancel', confirm: 'OK',
    battTitle: 'Battery health {p}',
    battLevel: 'Level', battSys: 'System status',
    battMax: 'All-time high', battLast: 'Latest learned', battMin: 'All-time low (used)',
    battDesign: 'Design capacity',
    battDesignNote: 'Some models cannot read it — <a href="#" id="lnkBattSet">check here</a> and enter it manually; default 5000.',
    battDesignSaved: 'Saved: {n} mAh',
    battDesignCleared: 'Cleared — back to auto',
    battDesignBad: 'Enter a value between 1000 and 30000',
    battDesignLow: 'Detected: design capacity ({d} mAh) is below the measured learned capacity ({m} mAh), so health is capped at 100%. Double-check the spec and re-enter it.',
    battDesignAuto: 'from system',
    battDesignDefault: 'unreadable, estimated',
    battDesignDevice: 'matched by model',
    battDesignUser: 'manual',
    battNote: 'After each full charge the system infers the real full capacity from the charge actually delivered, and records it as a learned value.<br>Health = learned capacity ÷ design capacity.<br>Battery health only decreases, so the <b>all-time low (Min)</b> is used to keep occasional high readings from inflating the result.<br>This is deliberately conservative: the figure may sit slightly below the true capacity, but the deviation stays within a small, predictable range.'
      + '<br><br><b>About fluctuations</b>: the first reading may lag or bounce around. That is how the system learns — it needs a few complete charge cycles to converge. '
      + 'Expect a stable figure after <b>about a week of normal use</b>; right after installing, treat the number as a rough reference and do not worry about day-to-day jitter.',
    tSaved: 'Saved {n} apps', tCompiling: 'Compiling, please wait',
    reverting: 'Reverting',
    tFail: 'Failed: {m}', tCompiled: 'Compiled in {n}s', tReverted: 'Reverted in {n}s',
    tAlready: 'Already AOT-compiled, nothing to do',
    tNotAot: 'Not AOT-compiled, nothing to undo',
    cfmAotTimeout: '"{n}" timed out last time (the system cap is 9.5 min; this app is too big).\nThe default speed-profile is already active, so nothing is broken.\nTry again anyway?',
    tLowMem: 'Only {n}M free — full compilation will almost certainly fail. Close some apps first.',
    cfmKill: 'Clear background processes of "{n}"? Background only — auto-start is unaffected.',
    killDontAsk: "Don't ask again",
    tKilled: 'Cleared "{n}", freed {m}M · list refreshes shortly',
    tKilledZero: 'Cleared "{n}", no memory freed · list refreshes shortly',
    tKillIdle: '"{n}" has no background processes right now',
    tKillFail: 'Failed: {m}',
    tCleared: 'Selection cleared', tConnFail: 'Cannot reach service: {m}', tAdbOff: 'ADB not connected',
    tPerfNoAdb: 'ADB not connected, performance mode not applied',
    tPerfOn: 'Performance mode on', tPerfOff: 'Performance mode off',
    tNameOk: 'Name updated, launcher label refreshes shortly', tNameFail: 'Saved, but launcher label could not change',
    tuneTitle: 'Performance tuning',
    tunePerf: 'Performance mode', tunePerfSub: 'Disable battery saver and balanced mode',
    tuneSave: 'Battery saver', tuneSaveSub: 'Limit background activity to extend battery life',
    tuneBal: 'Balanced mode', tuneBalSub: 'Default power/performance trade-off',
    tuneGpu: 'GPU tuning', tuneGame: 'Game periodic tuning',
    tuneGpuSub: 'On by default — best left untouched',
    tuneGameSub: 'On by default — best left untouched',
    milletTitle: 'Millet allowlist', milletSub: 'Apps exempt from background limits · {n}',
    edit: 'Edit', save: 'Save',
    rootNote: 'The following need root and are read-only',
    rtName: 'Real-time scheduling', tempLimit: 'Temperature limits', bootMode: 'Default boot mode', governor: 'CPU governor',
    settings: 'Settings', secName: 'App name', secTheme: 'Theme', secLang: 'Language',
    hisTitle: 'Operation history', hisLimitLb: 'Keep entries',
    hisLimitNote: '0=off, max 10000',
    hisEmpty: 'No operations recorded yet', hisClear: 'Clear',
    hisClearAsk: 'Clear all operation history? This cannot be undone.',
    op_freeze: 'Archived', op_unfreeze: 'Restored', op_restrict: 'Restricted', op_unrestrict: 'Unrestricted',
    themeAuto: 'Auto', themeLight: 'Light', themeDark: 'Dark',
    langAuto: 'Auto', langZh: '中文', langEn: 'English',
    perfTip: 'Performance',
    perfNote: "'Performance' sounds like a battery hog, but it isn't — it only <b>lifts the CPU's peak-frequency cap</b>; it does not pin the CPU at full tilt. Foreground work finishes in seconds and the CPU drops back down, which costs less than grinding along under a cap.<br>What actually drains a phone on standby is background apps waking, going online and refreshing. Battery-saver / balanced modes cap the CPU, so the foreground takes longer while the background keeps waking — <b>slower and just as thirsty</b>.<br>Our recommendation: <b>Performance + restrict the apps you don't need</b>. Fast foreground, quiet background — you save on both ends.",
    secTip: 'Show hints', tipHelp: 'Controls whether the little ⓘ hints are shown in the UI.', tipShow: 'Show', tipHide: 'Hide',
    keepCard: 'Recents card',
    keepNote: 'Measured background power use is too low to register, while the recents card makes the app easier to kill — enable it only if needed. We recommend allowing <a href="#" id="lnkNotif">notifications</a> and <a href="#" id="lnkAuto">autostart</a>, and setting <a href="#" id="lnkPower">battery saver to "no restrictions"</a> so the app is not killed.',
    keepHidden: 'Recents card hidden', keepShown: 'Recents card shown',
    keepFail: 'The system refused this setting',
    extRepo: 'GitHub · GPL-3.0', extFund: 'Sponsor',
    updCheck: 'Check', updChecking: 'Checking…',
    updCurrent: 'v {v}', updLatest: 'Up to date ({v})',
    updLatestShort: 'Up to date', updNewShort: 'v {v} found',
    updFailShort: 'Check failed', updCheckingShort: 'Checking', updRestart: 'Will restart',
    updNew: 'New version {v} available', updOpen: 'Get it',
    updPermNote: 'Updates inherit the ADB grant.',
    updInstall: 'Update', updInstalling: 'Updating', updFinishing: 'Done', updDone: 'Done',
    updInstallDone: 'Installed — the app will restart', updInstallFail: 'In-app install failed — opening the browser instead',
    updFail: 'Check failed — network problem?',
    updAuto: 'Auto-check daily',
    updAutoOn: 'Auto-check enabled', updAutoOff: 'Auto-check disabled',
    extLicense: 'GPL-3.0 — closed-source redistribution is prohibited. For commercial licensing, open an issue.',
    riskTitle: 'Before you start',
    riskBody: 'This app drives system commands directly through ADB and is genuinely powerful. Do not freeze or restrict components the system depends on (Clock, launcher, keyboard, …) — alarms, the home screen and incoming calls can break, among other things.',
    riskLink: 'Read the full usage notes →',
    riskFoot: 'Continued use means accepting responsibility for any risk caused by misuse.',
    riskAgree: 'Agree and continue', riskQuit: 'Quit',
    secIcon: 'App icon', iconExpand: 'More', iconCollapse: 'Less', loading: 'Loading…',
    secAgo: 's ago', minAgo: 'm ago', locating: 'Looking for app (tap to stop)',
    locFound: 'Found {n}', locNotFound: 'Not found: {n}',
    idxTitle: 'Build app location index', idxStart: 'Start', idxRunning: 'Scanning…', cancel: 'Cancel',
    chanTitle: 'Execution channel',
    szTitle: 'Shizuku permission', szAsk: 'Grant', szOk: 'Shizuku granted', szOff: 'Shizuku not running', szNoPerm: 'Shizuku not granted',
    szNotRunning: 'Shizuku is not running — open the Shizuku app and start it via wireless debugging or root',
    szAskFail: 'Permission request failed; make sure Shizuku is running and retry',
    szGranted: 'Shizuku permission granted', szWait: 'Tap Allow in the Shizuku dialog',
    idxHintNone: 'No app index yet — lookups will be slow. <b>Tap to build</b>',
    idxHintOff: 'Don\'t show again',
    idxUnsupported: 'App lookup is not supported on this launcher yet — please open an issue so we can add it',
    idxNeedA11y: 'Enable accessibility first: Settings → Downloaded apps → MineApp应用查找 (taking you there)',
    idxBridgeErr: 'Bridge method not exposed: {m} (stale or broken build — please open an issue)',
    idxA11yTitle: 'Accessibility is off',
    idxA11yBody: 'Accessibility is the fast path: it reads the view tree directly, cutting each read from ~3 s to 0.3 s. You can also build the index the classic way (synthetic input + dump parsing), which is much slower.',
    idxClassic: 'Classic mode',
    idxOpenA11y: 'Enable accessibility',
    idxClassicOff: 'Classic mode unavailable: this system blocks synthetic input',
    injectTitle: 'Enable "USB debugging (Security settings)"',
    injectText: 'Allow modifying permissions or simulating taps over USB',
    injectBody: 'The system blocked simulated taps, so app lookup cannot continue. In Developer options, enable "USB debugging (Security settings)" — its description reads "allow modifying permissions or simulating taps over USB"; do not confuse it with the plain "USB debugging" above it. Reboot the phone (the switch only takes effect after a reboot), then tap Start again.',
    idxHintPartial: 'Index is incomplete — some apps have no locate button. <b>Tap to resume</b>',
    locAskTitle: 'No location index yet',
    locAskBody: 'A direct lookup walks every page and opens every folder — it can take tens of seconds. Building the index first records where every app lives, so later lookups are near-instant.\nIndexing takes a few minutes; you can interrupt it any time and resume later. Building it first is recommended.',
    locAskInit: 'Build index first', locAskDirect: 'Look up directly',
    locStaleTitle: 'Location index is stale',
    locStaleBody: 'The desktop changed since the index was built, so a lookup may fall back to walking every page — that can take tens of seconds.\nRebuilding takes a few minutes and makes later lookups near-instant.',
    locSlowTip: 'No usable index — walking every page, this may take tens of seconds. Build the index in Settings for faster lookups.',
    locWaitRefresh: 'Refreshing the list — locating right after',
    idxNote: 'Indexes where each app lives on your home screen for app lookup — interrupt any time and resume later. The accessibility path is faster. <b id="idxAt" style="color:var(--ink2);font-weight:600"></b>',
    idxAgeMin: 'Last built {n} min ago', idxAgeHour: 'Last built {n} h ago', idxAgeDay: 'Last built {n} days ago',
    idxDone: 'Index complete — {n} apps', idxPaused: 'Paused — tap Start to resume', idxFail: 'Failed to build the index — please open an issue with your device and launcher',
    perfPausedTitle: 'The system keeps turning performance mode off',
    perfPausedBody: 'It was switched off {n} times in the last minute ({when}).\n'
      + 'Auto-maintenance is paused so the app stops fighting the system.\n'
      + 'Check the battery settings in Settings, or whether the phone is on low battery / '
      + 'thermal protection.\n'
      + 'Turning performance mode on again resumes auto-maintenance.',
    icSq: 'Blocks', icCl: 'Clover', icG: 'Green', icY: 'Gold', icK: 'Black',
    tIconOk: 'Icon changed; the launcher may take a few seconds', tIconFail: 'Failed to change icon',
    tBusy: 'Working, please wait',
    tNoArchivable: 'None of the selected apps can be archived',
    tNoArchived: 'None of the selected apps is archived',
    tPickRestrict: 'Select apps to restrict first',
    tPickUnrestrict: 'Select apps to unrestrict first',
    cfmArchive: 'Archive {n} selected apps?\n\nNormal apps are only disabled; their data is kept.\nSystem apps cannot be disabled, so the current user\'s install is uninstalled. The copy on the system partition remains, so restoring reinstalls it — the app comes back, but its data is gone.',
    cfmRestrict: 'Restrict background activity for {n} apps?\n\nThe system will limit their CPU, network and wakeups in the background, so they may stop receiving notifications.',
    cfmUnrestrict: 'Restore background permission for {n} apps?',
    cfmRelease: 'Restore {n} apps?',
    running: 'Working on {n}…',
    tDone: '{w}: {ok} succeeded',
    tDoneFail: ', {n} failed',
    tRunFail: '{w} failed: {m}',
    tUnknown: 'unknown error',
    tAdbNoRun: 'ADB not connected, nothing was executed',
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
  for (var i = 0; i < els.length; i++) {
    // 默认用 textContent（安全，不会被当成 HTML）；需要换行/加粗的文案标 data-html
    var k = els[i].getAttribute('data-t');
    if (els[i].hasAttribute('data-html')) els[i].innerHTML = T(k);
    else els[i].textContent = T(k);
  }
  var bt = document.getElementById('btnTune');
  if (bt) bt.innerHTML = T('perfTip') + ' <i class="tip-i">ⓘ</i>';
  var ph = document.querySelectorAll('[data-tp]');
  for (var j = 0; j < ph.length; j++) ph[j].setAttribute('placeholder', T(ph[j].getAttribute('data-tp')));
  // 说明文字是 innerHTML 重写的，里面的「上次完成」占位会被清空 —— 补回去
  if (typeof updateIdxAt === 'function') updateIdxAt();
  applyExtLinks();
  // 「当前版本 / 已是最新 / 发现新版本」是动态文案，data-t 覆盖不到，需要单独重绘
  if (typeof renderUpdState === 'function') renderUpdState();
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

/* 图标网格默认全部折叠（一个都不铺），点「展开」才列出 6 个。
   早先默认露 3 个，设置页光图标就占掉两行，而绝大多数人装完就不会再改。
   注意：不要按「当前选中的排在后面」自动展开 —— 那会让每次进来都是展开态，
   与「默认折叠」矛盾。 */
var ICON_FOLD = 0;
var iconExpanded = false;

function renderIcons(){
  var g = document.getElementById('iconGrid');
  if (!g) return;
  if (!HAS_BRIDGE) { g.style.display = 'none'; return; }   // Termux 版没有 Java 桥
  var n = iconExpanded ? ICONS.length : Math.min(ICON_FOLD, ICONS.length);
  var h = '';
  for (var i = 0; i < n; i++){
    var k = ICONS[i];
    h += '<div class="icon-cell' + (k === iconKey ? ' on' : '') + '" data-icon="' + k + '">'
       + '<img src="icon-' + k + '.png" alt="">'
       + '<span>' + iconLabel(k) + '</span></div>';
  }
  g.innerHTML = h;
  // 折叠时连空容器的高度一起省掉（CSS 默认是 grid，展开时必须还原成空串）
  g.style.display = n ? '' : 'none';
  var t = document.getElementById('iconToggle');
  if (t) t.textContent = iconExpanded ? T('iconCollapse') : T('iconExpand');
}

var _mqDark = (window.matchMedia ? window.matchMedia('(prefers-color-scheme: dark)') : null);

function applyTheme(){
  var dark = themeMode === 'dark'
    || (themeMode === 'auto' && _mqDark && _mqDark.matches);
  document.documentElement.classList.toggle('dark', dark);
  // 系统状态栏 / 手势条跟着一起翻。只改页面不管栏的话，深色界面顶上会
  // 一直顶一条浅色状态栏，沉浸感全无（两个版本以前都是这样）。
  try {
    fetch('/api/setnight', {method:'POST', headers:{'Content-Type':'application/json'},
                            body: JSON.stringify({dark: dark ? 1 : 0})})
      .catch(function(){});
  } catch (e) { }
}

/* 「跟随系统」时用户在系统设置里切了深色，页面和状态栏要立刻跟上 */
if (_mqDark) {
  var _onSchemeChange = function(){ if (themeMode === 'auto') applyTheme(); };
  if (_mqDark.addEventListener) _mqDark.addEventListener('change', _onSchemeChange);
  else if (_mqDark.addListener) _mqDark.addListener(_onSchemeChange);
}

/* 无障碍读取通道：只显示状态 + 一个跳转按钮，开关本身必须用户去系统设置里打开 */
function renderChan(){
  var row = document.getElementById('chanRow');
  if (!row) return;
  fetch('/api/chanstate', {method:'POST'})
    .then(function(r){ return r.json(); })
    .then(function(d){
      if (!d || !d.multi) { row.style.display = 'none'; return; }
      row.style.display = 'flex';
      var box = document.getElementById('chanBtns');
      if (!box) return;
      box.style.display = 'flex';
      box.innerHTML = '';
      // 去掉「自动」：它的语义含糊（到底走哪条？），而且默认值让人以为已经能用了。
      // 改成默认 ADB，想用 Shizuku 自己选，界面上一眼就清楚当前走的是什么。
      var cur = d.chan || 'adb';
      var name = {root: 'Root', shizuku: 'Shizuku', adb: 'ADB'};
      var label = document.getElementById('chanState');
      if (label) label.textContent = name[cur] || cur;
      ['adb', 'shizuku', 'root'].forEach(function(k){
        var b = document.createElement('button');
        b.className = 'edit';
        b.textContent = name[k];
        b.style.padding = '0 12px';
        if (k === cur) {
          b.style.background = '#0f6e56';
          b.style.color = '#fff';
          b.style.borderColor = '#0f6e56';
        }
        b.onclick = function(){
          fetch('/api/setchan', {method:'POST', headers:{'Content-Type':'application/json'},
                                 body: JSON.stringify({chan: k})})
            .then(function(r){ return r.json(); })
            .then(function(){
              renderChan();
              // 切完通道主界面的「调试未连接」必须立刻重判 —— 不然要杀进程重启
              // App 才会变成已连接（用户反馈过这个坑）
              load(true);
            });
        };
        box.appendChild(b);
      });
      renderShizuku();
    })
    .catch(function(){});
}

/* Shizuku 状态：仅多通道版显示。服务未运行 / 未授权 / 已授权 三态。
   返回 Promise，授权轮询链靠它判断「是不是已经拿到了」。 */
function renderShizuku(){
  var st = document.getElementById('szState');
  var btn = document.getElementById('szBtn');
  if (!st) return Promise.resolve(null);
  return fetch('/api/shizukustate', {method:'POST'})
    .then(function(r){ return r.json(); })
    .then(function(d){
      // 标准版没有 Shizuku 通道：清空状态、藏掉按钮即可，不再单独占一行
      if (!d || !d.ok || d.multi === false){
        st.textContent = '';
        if (btn) btn.style.display = 'none';
        return null;
      }
      var granted = !!d.granted;
      st.textContent = granted ? T('szOk') : T('szNoPerm');
      st.style.color = granted ? 'var(--green)' : 'var(--ink3)';
      if (btn) btn.style.display = granted ? 'none' : '';
      return d;
    })
    .catch(function(){});
}

function askShizuku(){
  fetch('/api/shizukuask', {method:'POST'})
    .then(function(r){ return r.json(); })
    .then(function(d){
      if (!d || !d.ok){
        toast(d && d.msg === 'not-running' ? T('szNotRunning') : T('szAskFail'), 5000);
        return;
      }
      if (d.granted){ toast(T('szGranted')); renderShizuku(); return; }
      toast(T('szWait'), 5000);
      // 授权是在 Shizuku 的 Activity 里完成的，用户点「允许」的时机不定。
      // 2/5/8/11 秒各查一次；任一次查到已授权就终止整条链，后面不再发请求。
      // 稀疏散点而不是密集轮询 —— 最多 4 次请求，且提前成功就提前结束。
      shizukuCheckChain([2000, 5000, 8000, 11000], 0);
    })
    .catch(function(){ toast(T('szAskFail')); });
}

/* 依次在给定的绝对时间点（毫秒）检查授权；查到即停，并顺手刷新主界面撤横幅。 */
function shizukuCheckChain(times, i){
  if (i >= times.length) return;
  var delay = (i === 0) ? times[0] : (times[i] - times[i - 1]);
  setTimeout(function(){
    renderShizuku().then(function(d){
      if (d && d.granted){
        load(true);          // 已授权：撤横幅 + 重拉列表，链条到此结束
        return;
      }
      shizukuCheckChain(times, i + 1);
    });
  }, delay);
}

function renderSettings(){
  var din = document.getElementById('battDesignIn');
  if (din && lastBattery && lastBattery.design_mah > 0){
    // 只有「用户手动设的」才回填到输入框；自动读到/兜底值只做 placeholder，
    // 否则会让人以为自己设过
    var isUser = lastBattery.design_src === 'user';
    din.value = isUser ? String(lastBattery.design_mah) : '';
    din.placeholder = String(lastBattery.design_mah);
  }
  var n = document.querySelectorAll('#nameBar .seg');
  for (var i = 0; i < n.length; i++)
    n[i].classList.toggle('on', n[i].getAttribute('data-name') === appName);
  var t = document.querySelectorAll('#themeBar .seg');
  for (var j = 0; j < t.length; j++)
    t[j].classList.toggle('on', t[j].getAttribute('data-theme') === themeMode);
  var g = document.querySelectorAll('#langBar .seg');
  for (var m = 0; m < g.length; m++)
    g[m].classList.toggle('on', g[m].getAttribute('data-lang') === langMode);
  var tc = document.getElementById('tipChk');
  if (tc) tc.checked = showTip;
  renderIcons();
  renderChan();
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
  updateIdxAt();                // 先用缓存值填上，别等列表刷新
  iconExpanded = false;         // 图标网格每次进来都是收起的
  renderSettings();
  document.getElementById('settings').classList.add('show');
}

/* 关闭时只摘掉 show：位移动画由 CSS transition 负责，左滑退出与点 × 同一条路径 */
function closeSettings(){
  document.getElementById('settings').classList.remove('show');
}

/* ---------- 操作历史 ---------- */

function openHistory(){
  document.getElementById('history').classList.add('show');
  loadHistory();
}

function closeHistory(){
  document.getElementById('history').classList.remove('show');
}

function loadHistory(){
  fetch('/api/history', {method:'POST'})
    .then(function(r){ return r.json(); })
    .then(function(d){
      var inp = document.getElementById('hisLimit');
      if (inp && d && typeof d.limit === 'number') inp.value = d.limit;
      renderHistory((d && d.items) || []);
    })
    .catch(function(){
      renderHistory([]);
    });
}

/* 应用名用本地已经拿到的 apps 补：后端只存包名，逐条查 shell 太慢 */
function appLabelOf(pkg){
  for (var i = 0; i < apps.length; i++){
    if (apps[i].package === pkg) return apps[i].label || pkg;
  }
  return pkg;
}

function fmtHisTime(ms){
  var d = new Date(ms);
  if (isNaN(d.getTime())) return '';
  function p(n){ return n < 10 ? '0' + n : '' + n; }
  return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate())
       + ' ' + p(d.getHours()) + ':' + p(d.getMinutes());
}

function renderHistory(items){
  var box = document.getElementById('hisList');
  if (!box) return;
  if (!items.length){
    box.innerHTML = '<p class="his-empty">' + T('hisEmpty') + '</p>';
    return;
  }
  var html = '';
  for (var i = 0; i < items.length; i++){
    var it = items[i] || {};
    var pkg = it.p || '';
    var act = it.a || '';
    var opKey = 'op_' + act;
    var opName = T(opKey);
    if (opName === opKey) opName = act;          // 没配到翻译就退回原始动作名
    html += '<div class="his-item">'
          +   '<div class="his-l1">'
          +     '<span class="his-name">' + escHtml(appLabelOf(pkg)) + '</span>'
          +     '<span class="his-op op-' + escHtml(act) + '">' + escHtml(opName) + '</span>'
          +   '</div>'
          +   '<div class="his-l2">' + escHtml(pkg) + ' · ' + escHtml(fmtHisTime(it.t)) + '</div>'
          + '</div>';
  }
  box.innerHTML = html;
}

function escHtml(s){
  return String(s == null ? '' : s)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

function saveKeep(){ localStorage.setItem('keep', JSON.stringify(Array.from(keep))); }
function toast(msg, ms){
  var t = document.getElementById('toast');
  var len = (msg || '').length;
  t.textContent = msg;
  // 超过 40 字换成更宽的左对齐样式，否则窄框会把长句顶成一条竖长条
  t.classList.toggle('long', len > 40);
  t.classList.add('show');
  clearTimeout(t._h);
  // 停留时间随字数走：20 字以内保持 2.2s，每多 1 字加 80ms，最长 5s
  // （编译失败那种 70+ 字的解释，2.2s 根本读不完）
  var dur = ms || Math.min(5000, 2200 + Math.max(0, len - 20) * 80);
  _toastTaps = 0;   // 新提示重新计数
  t._h = setTimeout(function(){ t.classList.remove('show'); }, dur);
}

/* 提示挡着屏幕时，点两下就关掉（累计两次，单次点击不打断操作） */
var _toastTaps = 0;
document.addEventListener('touchstart', function(){
  var t = document.getElementById('toast');
  if (!t || !t.classList.contains('show')) return;
  if (++_toastTaps >= 2){
    _toastTaps = 0;
    clearTimeout(t._h);
    t.classList.remove('show');
  }
}, {passive:true});

function askConfirm(msg, onOk, opt){
  var m = document.getElementById('modal');
  document.getElementById('modal-msg').textContent = msg;
  var cancel = document.getElementById('modal-cancel');
  var ok = document.getElementById('modal-ok');
  var box = document.getElementById('modal-opt');
  var cb = document.getElementById('modal-opt-cb');
  // askA11yChoice 会把取消键临时改成「经典查找」，这里复位，避免串到别的弹窗
  cancel.textContent = T('cancel');
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
  document.getElementById('modal-cancel').textContent = T('cancel');
  var ok = document.getElementById('modal-ok');
  ok.textContent = T('ok');
  m.classList.add('show');
  ok.onclick = function(){ m.classList.remove('show'); };
}

/* 首次使用 / 调试未开启时的操作指引 */
function showGuide(){
  askInfo(
    '<div class="info-title">' + T('guideTitle') + '</div>'
    + '<div class="info-step"><b>1</b><span>' + T('guide1') + '</span></div>'
    + '<div class="info-step"><b>2</b><span>' + T('guide2') + '</span></div>'
    + '<div class="info-step"><b>3</b><span>' + T('guide3') + '<br><code>adb tcpip ' + adbPort + '</code></span></div>'
    + '<div class="info-step"><b>4</b><span>' + T('guide4') + ' ↻</span></div>'
    + '<div class="info-step"><b>5</b><span>' + T('guide5') + '</span></div>'
    + '<div class="info-note">' + T('guideNote') + '</div>'
    + '<div class="info-port">'
    +   '<span>' + T('portAsk') + '</span> <a href="#" id="lnkPort">' + T('portSet') + '</a>'
    +   '<div id="portBox" style="display:none">'
    +     '<input id="portIn" type="number" inputmode="numeric" min="1024" max="65535" value="' + adbPort + '">'
    +     '<button id="portSave">' + T('portSave') + '</button>'
    +     '<div class="port-range">' + T('portRange') + '</div>'
    +   '</div>'
    + '</div>'
  );
  var lk = document.getElementById('lnkPort');
  if (lk) lk.onclick = function(e){
    if (e) e.preventDefault();
    var box = document.getElementById('portBox');
    var inp = document.getElementById('portIn');
    if (box) box.style.display = 'flex';
    if (inp) { inp.value = adbPort; inp.focus(); inp.select(); }
    return false;
  };
  var sv = document.getElementById('portSave');
  if (sv) sv.onclick = savePort;
}

/* 保存自定义端口。成功后就地重绘引导弹窗 —— 第 3 步的命令要跟着变，
   用户照着新命令去电脑上执行，两边端口才一致。 */
function savePort(){
  var inp = document.getElementById('portIn');
  var v = parseInt(inp ? inp.value : '', 10);
  if (!(v >= 1024 && v <= 65535)){ toast(T('portBad')); return; }
  fetch('/api/setport', {method:'POST', headers:{'Content-Type':'application/json'},
                         body: JSON.stringify({port: v})})
    .then(function(r){ return r.json(); })
    .then(function(d){
      if (!d || !d.ok){ toast(T('portBad')); return; }
      adbPort = d.port || v;
      toast(T2('portSaved', {p: adbPort}));
      showGuide();
      load(true);
    })
    .catch(function(){ toast(T('portBad')); });
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

/* 白名单置顶：只在 Millet 白名单页用，主界面不参与
   （sort 稳定，非白名单之间保持原顺序） */
function whiteFirst(arr){
  arr.sort(function(a, b){ return (b.white ? 1 : 0) - (a.white ? 1 : 0); });
}

/* 距上次前台打开的时长：15 s / 5 m / 3 h / 1 d */
function fmtAgo(ts){
  if (!ts) return '';
  var s = Math.round((Date.now() - ts) / 1000);
  if (s < 0) s = 0;
  if (s < 60) return s + 's';
  var m = Math.floor(s / 60);
  if (m < 60) return m + 'm';
  var h = Math.floor(m / 60);
  if (h < 24) return h + 'h';
  return '>24h';
}

/* 副行：运存占用 + 上次打开时间（主列表与白名单共用） */
function subline(a){
  var mb = Math.round((a.pss || 0) / 1024);
  var h = '';
  if (mb > 0){
    h = mb + 'M';
    var pct = memTotal ? (a.pss * 100.0 / memTotal) : 0;
    if (pct >= 0.1) h += ' · ' + pct.toFixed(1) + '%';
  }
  if (idleMap){
    var ago = fmtAgo(idleMap[a.pkg]) || T('agoNever');
    h += (h ? ' · ' : '') + '<span class="ago">' + ago + '</span>';
  }
  // 有几条进程就标几条（1 条也标），点开能看是哪条、占多少内存
  var btn = '';
  if (a.procs > 0){
    btn = (h ? '<span class="sep">·</span>' : '') + '<span class="proc-btn" data-procbtn="' + a.pkg + '">'
        + a.procs + T('procs')
        + '<svg class="chev" viewBox="0 0 24 24" fill="none" stroke="currentColor"'
        +   ' stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">'
        +   '<path d="M9 5l7 7-7 7"/></svg></span>';
  }
  if (!h && !btn) return '';
  // 绿点 = 真正还在活动：有内存占用、未被归档，且系统里还有 cached/empty
  // 之外的进程（只被缓存着的应用不点绿点，避免满屏都是点）。
  // 🔴 不再排除「已限制」的应用：限制只是压后台，它现在有进程在跑就是活动，
  //    不亮会出现「进程有绿点、包名没绿点」的矛盾。
  var live = mb > 0 && a.active && !a.frozen;
  return '<div class="mem">' + (live ? '<i class="dot-live"></i>' : '')
       + '<span class="mem-t"><span class="mem-x">' + h + '</span>' + btn + '</span></div>';
}

/* ---- 进程明细：点「N 进程 ›」才查，后台不主动刷 ---- */
function fmtProcMem(b){
  if (!b) return '—';
  var mb = b / 1048576;
  return (mb >= 100 ? Math.round(mb) : mb.toFixed(1)) + 'M';
}

/* CPU 累计时间：按分钟展示。< 1m / 46m / 1h2m / >24h */
function fmtCpu(s){
  if (!s) return '—';
  var p = String(s).split(':');
  var sec = -1;
  if (p.length === 3) sec = (+p[0]) * 3600 + (+p[1]) * 60 + (+p[2]);
  else if (p.length === 2) sec = (+p[0]) * 60 + (+p[1]);
  if (sec < 0 || isNaN(sec)) return s;
  if (sec >= 86400) return '>24h';
  if (sec < 60) return '<1m';
  var h = Math.floor(sec / 3600), m = Math.floor((sec % 3600) / 60);
  if (h > 0) return h + 'h' + m + 'm';
  return m + 'm';
}

function procsHtml(list){
  var h = '';
  for (var i = 0; i < list.length; i++){
    var p = list[i];
    // 双开 / 手机分身的进程同名，标一个「·多开」才分得清
    var nm = (p.name || T('procMain')) + (p.multi ? '·' + T('procMulti') : '');
    h += '<div class="proc-row">'
       + '<i class="proc-dot' + (p.active ? ' live' : '') + '"></i>'
       + '<span class="proc-nm">' + nm + '</span>'
       + '<span class="proc-cpu">' + fmtCpu(p.cpu) + '</span>'
       + '<span class="proc-mem">' + fmtProcMem(p.mem) + '</span>'
       + '<span class="proc-oom">oom' + (p.oom == null ? '—' : p.oom) + '</span>'
       + '</div>';
  }
  return h;
}

function toggleProcs(pkg, btn){
  var li = btn.closest('li');
  if (!li) return;
  var nxt = li.nextElementSibling;
  if (nxt && nxt.classList.contains('proc-wrap')){   // 已展开 → 收起
    nxt.parentNode.removeChild(nxt);
    btn.classList.remove('open');
    return;
  }
  if (!adbOk){ toast(T('tAdbOff')); return; }
  btn.classList.add('open');
  var wrap = document.createElement('li');
  wrap.className = 'proc-wrap';
  wrap.innerHTML = '<div class="proc-row"><span class="proc-nm">' + T('procLoading') + '</span></div>';
  li.parentNode.insertBefore(wrap, li.nextSibling);
  fetch('/api/procs', {method:'POST', headers:{'Content-Type':'application/json'},
        body: JSON.stringify({pkg: pkg})})
    .then(function(r){ return r.json(); })
    .then(function(d){
      if (!d || !d.ok || !d.procs || !d.procs.length){
        wrap.innerHTML = '<div class="proc-row"><span class="proc-nm">' + T('procNone') + '</span></div>';
        return;
      }
      wrap.innerHTML = procsHtml(d.procs);
    })
    .catch(function(){
      wrap.innerHTML = '<div class="proc-row"><span class="proc-nm">' + T('tUnknown') + '</span></div>';
    });
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
  var aotOn = aotDone.has(aotKey(a));
  // 只有桌面能放图标的应用才给「定位」按钮；纯服务类应用没有启动入口。
  // 索引建好之后再加一条：索引里没有这个应用名 = 桌面上没有它的图标
  // （装了但没拖到桌面的，比如 阿里巴巴/1688），定位必然失败 —— 直接不显示按钮，
  // 比让用户点一个注定找不到的按钮好。索引没建时仍然显示，点了会引导去建索引。
  var canLocate = a.launcher && (a.idx_ready ? !!a.in_index : true);
  var locBtn = canLocate
      ? '<span class="loc-btn" data-loc="' + a.pkg + '" role="button" aria-label="locate">'
      +   '<svg viewBox="0 0 24 24" aria-hidden="true">'
      +     '<path d="M12 21.5c0 0-7-6.2-7-11a7 7 0 1 1 14 0c0 4.8-7 11-7 11z"/>'
      +   '</svg>'
      + '</span>'
      : '';
  return '<li class="' + cls + '" data-pkg="' + a.pkg + '">'
       +  '<span class="chk">' + (on ? '✓' : '') + '</span>'
       +  '<div class="meta"><div class="name"><span class="nm">' + a.label + '</span>'
       +    locBtn
       +    sysTag(a)
       +  '</div>'
       +  '<div class="pkg">' + a.pkg + '</div>' + subline(a) + '</div>'
       +  '<div class="rt"><div class="rt-top">'
       +    (a.pss > 0 ? '<span class="kill-x" data-kill="' + a.pkg + '">×</span>' : '')
       +    '<span class="aot-lb">AOT</span>'
       +    '<span class="aot-sw' + (aotOn ? ' on' : '')
       +      ((aotBusy && aotPkg === a.pkg) ? ' busy' : '') + '" data-pkg="' + a.pkg
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
  if (aotBusy) aotTick();     // 重绘后把编译进度补回去
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
  if (b.design_mah > 0){
    var srcKey = 'battDesign' + (b.design_src === 'auto' ? 'Auto'
                               : b.design_src === 'user' ? 'User'
                               : b.design_src === 'device' ? 'Device' : 'Default');
    kv(T('battDesign'), b.design_mah, T2(srcKey, {n: b.design_mah}));
  }
  askInfo(
    '<div class="info-title">' + T2('battTitle', {p: (b.health_pct > 0 ? b.health_pct + '%' : '—')}) + '</div>'
    + '<div class="info-t">' + T('battLevel') + ' <b>' + b.level + '%</b>'
    + (b.health ? ' · ' + T('battSys') + ' <b>' + healthName(b.health) + '</b>' : '')
    + (b.temp > 0 ? ' · ' + T('temp').replace('℃','') + ' <b>' + b.temp + '°C</b>' : '') + '</div>'
    + rows
    + (b.design_low ? '<div class="info-note" style="color:var(--red)">'
        + T2('battDesignLow', {d: b.design_mah, m: b.learned_min}) + '</div>' : '')
    + '<div class="info-note">' + T('battNote') + '</div>'
  );
}

function updateInfo(){
  var s = T2('infoSel', {n: keep.size, t: apps.length});
  document.getElementById('info').innerHTML =
      '<span class="i-sel">' + s
    + (keep.size > 0 ? ' <span id="clearSel">' + T('clearSel') + '</span>' : '')
    + '</span><span class="i-mem" id="infoMem"></span>';
  updateMemOnly();
}

/* 只重画内存那一小段，不动「已选 N」和清除按钮 ——
   否则每 3 秒重建一次 #info，用户正在点的按钮会被换掉。 */
function updateMemOnly(){
  var el = document.getElementById('infoMem');
  if (!el) return;
  if (!memTotal){ el.innerHTML = ''; return; }
  var ug = ((memTotal - memAvail) / 1048576).toFixed(1);
  var tg = (memTotal / 1048576).toFixed(1);
  var up = ((memTotal - memAvail) * 100.0 / memTotal).toFixed(1);
  el.innerHTML = T2('infoMem', {u: ug, g: tg, p: up});
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
  var hint = T2('bannerOffHint', {p: adbPort});
  if (st === 'unauthorized'){
    title = T('bannerUnauth');
    hint = T('bannerUnauthHint');
  } else if (st === 'offline'){
    title = T('bannerOffline');
    hint = T2('bannerOfflineHint', {p: adbPort});
  } else if (st === 'error'){
    title = T('bannerErr');
    hint = T('bannerErrHint');
  }
  // 「已设置的归档与限制依然生效」这句必须一直显示 ——
  // 以前 unauthorized / error 时被条件排除了，而「刚装未授权」正好是 unauthorized，
  // 用户最需要知道「不授权也不影响已经设好的东西」，却恰恰看不到这句。
  el.innerHTML = '<b>' + title + '</b><span class="fix">' + hint + '</span>'
    + '<span class="safe">' + T('bannerOffSafe') + '</span>'
    + '<span class="guide" id="bannerGuide">' + T('bannerGuide') + '</span>';
  el.style.display = 'flex';
  var g = document.getElementById('bannerGuide');
  if (g) g.onclick = showGuide;
}

/* ---------------- 列表缓存 ----------------
   App 被杀掉再进来时（换图标也会把进程带走），1 分钟内直接用上次的快照，
   不再自动重拉一次列表；手动点刷新永远重新拉。 */
var CACHE_KEY = 'listCache', CACHE_TTL = 60 * 1000;

/* 多选一弹窗：buttons = [{label, cb}, ...]，第一个高亮 */
function showChoice(title, body, buttons){
  document.getElementById('alertTitle').textContent = title;
  var h = '<div class="panel-loading" style="padding:6px 4px 16px;text-align:left">'
        + body.replace(/\n/g, '<br>') + '</div><div class="choice-btns">';
  for (var i = 0; i < buttons.length; i++){
    h += '<button data-ci="' + i + '" class="choice-btn'
       + (i === 0 ? ' primary' : '') + '">' + buttons[i].label + '</button>';
  }
  h += '</div>';
  var be = document.getElementById('alertBody');
  be.innerHTML = h;
  var bs = be.querySelectorAll('[data-ci]');
  for (var k = 0; k < bs.length; k++){
    bs[k].onclick = (function(idx){
      return function(){
        document.getElementById('alert').classList.remove('show');
        if (buttons[idx].cb) buttons[idx].cb();
      };
    })(k);
  }
  document.getElementById('alert').classList.add('show');
}

/* 通用说明面板 */
function showAlert(title, body){
  document.getElementById('alertTitle').textContent = title;
  document.getElementById('alertBody').innerHTML = '<div class="panel-loading" style="padding:8px 4px 0">'
    + body.replace(/\n/g, '<br>') + '</div>';
  document.getElementById('alert').classList.add('show');
}

function applyData(d){
  updateBanner(d);
  // 用户希望保持性能模式：一旦发现被系统回退，立即重新施加
  if (perfWanted && d.perf === false){
    fetch('/api/perf', {method:'POST', headers:{'Content-Type':'application/json'},
                        body: JSON.stringify({on: true})});
  }
  document.getElementById('perfChk').checked = (d.perf === true) || perfWanted;
  apps = d.apps || [];
  if (apps.length) idxReady = !!apps[0].idx_ready;
  idxPartial = !!(d && d.idx_partial);
  adbPort = (d && d.adb_port) || 5555;
  idxAt = (d && d.idx_at) || 0;
  try { localStorage.setItem('idxAt', String(idxAt)); } catch (e) {}
  updateIdxAt();
  updateIdxHint();
  // 索引刚跑完 → 自动回到设置页，让用户直接看到「上次完成：刚刚」。
  // 🔴 不能只靠 Java 注入 __idxDone：索引要跑 1 分钟，这期间 App 常被系统
  //    回收，WebView 重建后 window.__idxDone 根本不存在，注入静默失败，
  //    结果就是「只回到了 App，停在列表页」。这里按「服务器端 idx_at」
  //    判断，页面重载也能补上（idxAck 记住已提示过的那个时间点）。
  if (idxAt > 0 && Date.now() - idxAt < 5 * 60 * 1000){
    var ack = 0;
    try { ack = parseInt(localStorage.getItem('idxAck') || '0', 10) || 0; } catch (e) {}
    if (ack !== idxAt){
      try { localStorage.setItem('idxAck', String(idxAt)); } catch (e) {}
      openSettings();
    }
  }
  if (!aotSynced){ aotSynced = true; syncAot(); }
  // 系统反复取消性能模式 → 后台已暂停维持，这里说明一次（同一个时间点只弹一次）
  if (d.perf_paused){
    var sig = 'perfPausedShown:' + (d.perf_reverts || '');
    if (localStorage.getItem(sig) !== '1'){
      localStorage.setItem(sig, '1');
      var parts = (d.perf_reverts || '').split(',').filter(function(x){ return x; });
      var now = Date.now(), ago = [];
      for (var i = parts.length - 1; i >= 0; i--){
        var s2 = Math.round((now - parseInt(parts[i], 10)) / 1000);
        ago.push(s2 < 60 ? s2 + T('secAgo') : Math.round(s2 / 60) + T('minAgo'));
      }
      showAlert(T('perfPausedTitle'),
                T2('perfPausedBody', {n: parts.length, when: ago.join('、')}));
    }
  }
  if (d.with_pss) {
    hasPss = true;
    memTotal = d.mem_total || 0;
    memPct = d.mem_pct || 0;
    memAvail = d.mem_avail || 0;
  }
  if (d.battery) updateBattery(d.battery);
  // 第一次拿到数据说明 ADB 通了，这时才做保活初始化（加白名单要执行 shell）
  if (!keepInited){ keepInited = true; keepAliveInit(); }
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
      restricted_count: d.restricted_count || 0, perf: d.perf,
      // 🔴 索引状态必须一起缓存：漏了这两个字段，走缓存渲染时
      //    「上次完成」和索引引导条都会消失（实测「打断索引后看不到引导条」）
      idx_at: d.idx_at || 0, idx_partial: !!d.idx_partial
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

/* 🔴 回到前台要刷新一次：索引是在后台跑的，用户碰屏幕打断后回到 App，
   页面还停留在离开前的旧列表 —— 引导条不会出现、定位按钮也不会更新
   （实测「打断了索引但没看到引导条」，其实是没刷新，手动下拉刷新就出来了）。
   15 秒内不重复拉，避免来回切应用时空转。 */
var lastLoadAt = 0;
document.addEventListener('visibilitychange', function(){
  if (document.hidden) return;
  // 通道不可用（横幅亮着）时不走 15 秒节流：用户很可能刚去 Shizuku App 或
  // 系统弹窗里点了允许，切回来必须立刻核对一次，否则要等 15 秒或手动刷新。
  // 这是用户主动切回触发的，不是常驻轮询，没有额外功耗。
  if (!adbOk){ load(true); return; }
  if (Date.now() - lastLoadAt < 15000) return;
  load(true);
});

var loadRetry = 0;

function load(force){
  lastLoadAt = Date.now();
  // force=true 表示手动刷新或刚做过操作，必须拿新数据
  var state = force ? 0 : restoreCache(true);
  // 1 分钟内的缓存直接用它，不必再拉；过期的缓存也已经画出来了，继续后台刷新
  if (state === 1) return;

  refreshing = true;
  var btn = document.getElementById('btnRefresh');
  if (btn) btn.disabled = true;
  spinStart();

  // 已经有内容（旧缓存）时不要用「加载中」把摘要行顶掉，转圈本身就是提示
  var el = document.getElementById('stat');
  if (state === 0 && el) el.textContent = T('loading');

  // 首次连接可能要在手机上点授权，几秒没回来就先提示用户去看手机
  // 10 秒还没回来才提示「确认授权中」：
  // 4 秒太急，正常拉一次列表（采 PSS）也可能超过 4 秒，会误报成卡在授权上。
  var authTimer = setTimeout(function(){
    if (state === 0 && el && el.textContent === T('loading')) el.textContent = T('bannerUnauth');
  }, 10000);

  fetch('/api/list?pss=1').then(function(r){ return r.json(); }).then(function(d){
    clearTimeout(authTimer);
    saveCache(d);
    applyData(d);
    // Shizuku 通道下，冷启动时 binder 可能晚到几百毫秒 → 首屏会被判成「未连接」。
    // 这里自动重试两次，避免用户看到一片空白还要手动刷新。
    if (d && d.adb_ok === false && loadRetry < 2){
      loadRetry++;
      setTimeout(function(){ load(true); }, 1800);
    } else if (d && d.adb_ok !== false){
      loadRetry = 0;
    }
    if (btn) btn.disabled = false;
    spinFinish();
    refreshing = false;
    flushPendingLoc();
  }).catch(function(e){
    clearTimeout(authTimer);
    if (state === 0 && el) el.textContent = T('connFail');
    if (btn) btn.disabled = false;
    spinFinish();
    refreshing = false;
    flushPendingLoc();
    toast(T2('tConnFail', {m: e}));
  });
}

document.getElementById('list').addEventListener('click', function(e){
  if (e.target.closest('.aot-sw')) return;   // AOT 开关走单独的处理
  if (e.target.closest('.kill-x')) return;   // 清理按钮也走单独的处理
  if (e.target.closest('.loc-btn')) return;  // 定位按钮：只定位，不勾选这个应用
  if (e.target.closest('.proc-btn')) return; // 展开进程明细：不勾选
  if (e.target.closest('.proc-wrap')) return;// 进程面板内部的点击不勾选
  var li = e.target.closest('li');
  if (!li || busy) return;
  var pkg = li.getAttribute('data-pkg');
  if (keep.has(pkg)) keep.delete(pkg); else keep.add(pkg);
  saveKeep(); render();
});

/* 进程明细：展开 / 收起。面板里的 × 已移除 —— 无 root 时杀不了单个子进程，
   留着会让人以为「只关这一个」，实际是应用级的。 */
document.getElementById('list').addEventListener('click', function(e){
  var b = e.target.closest('.proc-btn');
  if (!b) return;
  toggleProcs(b.getAttribute('data-procbtn'), b);
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
  // 两段式反馈：
  //   ① 立刻把「N 进程 ›」标注摘掉（后台已经 force-stop，不必等列表刷新）
  //   ② 0.8 秒后整行滑出列表 —— 留这一下让用户看清是哪一行被处理了
  // 下次刷新时后端如实返回（该应用没进程了就不会再带 procs 字段）。
  var li = kx.closest('li');
  if (li){
    var pb = li.querySelector('.proc-btn');
    if (pb){
      var sp = pb.previousElementSibling;
      if (sp && sp.className === 'sep') sp.parentNode.removeChild(sp);
      pb.parentNode.removeChild(pb);
    }
    // 展开着的进程面板一起收起：进程都没了，还挂着一堆明细没意义
    var nx = li.nextElementSibling;
    if (nx && nx.classList.contains('proc-wrap')) nx.parentNode.removeChild(nx);
  }
  for (var i = 0; i < apps.length; i++){
    if (apps[i].pkg === pkg){ apps[i].procs = 0; break; }
  }
  setTimeout(function(){
    if (li && li.parentNode) li.parentNode.removeChild(li);
    for (var j = 0; j < apps.length; j++){
      if (apps[j].pkg === pkg){ apps.splice(j, 1); break; }
    }
  }, 800);
  fetch('/api/kill', {method:'POST', headers:{'Content-Type':'application/json'},
        body: JSON.stringify({pkg: pkg, pss: -1})}).catch(function(){});
});

/* AOT 预编译：点开关 → 轨道变成进度条（秒数同步显示在标签上），完成后就地刷新 */
var aotBusy = false;
/* 编译进度必须是全局状态：列表刷新会重绘整行，闭包里抓着的旧节点一旦脱离文档，
   进度条就"消失"了，而 aotBusy 还是 true —— 再点只会提示「编译中」。
   现在每帧都按 data-pkg 重新找节点，重绘多少次都不丢。 */
var aotPkg = null, aotT0 = 0, aotWantOn = false, aotTimer = null;
/* 已经确认过「就算超时也要再试一次」的包，避免确认框点确定后又弹一次 */
var aotForcePkg = null;

/* 画一帧编译进度；节点可能被 render() 换掉，所以每次重新查 */
function aotTick(){
  if (!aotBusy || !aotPkg) return;
  var el = document.querySelector('#list .aot-sw[data-pkg="' + aotPkg + '"]');
  if (!el) return;                       // 被搜索/排序筛出视图了
  el.classList.remove('on');
  el.classList.add('busy');
  var fill = el.querySelector('.fill'), pctEl = el.querySelector('.pct');
  var t = (Date.now() - aotT0) / 1000;
  var pct = Math.min(99, 99 * (1 - Math.pow(0.5, (t + 3) / 20)));
  if (fill) fill.style.width = pct.toFixed(1) + '%';
  if (pctEl){
    var sec = Math.floor(t);
    pctEl.textContent = sec < 60 ? (sec + 's')
                      : (Math.floor(sec / 60) + ':' + ('0' + (sec % 60)).slice(-2));
  }
}
document.getElementById('list').addEventListener('click', function(e){
  var sw = e.target.closest('.aot-sw');
  if (!sw) return;
  if (aotBusy){ toast(T('tCompiling')); return; }
  if (!adbOk){ toast(T('tAdbOff')); return; }
  var pkg = sw.getAttribute('data-pkg');
  var wantOn = sw.getAttribute('data-aot') !== '1';

  var cur = null;
  for (var ic = 0; ic < apps.length; ic++) if (apps[ic].pkg === pkg) { cur = apps[ic]; break; }

  // 上次这个应用全量编译超时过：先提醒，别让人白等 10 分钟又失败
  if (wantOn && cur && aotTimeout.has(aotKey(cur)) && aotForcePkg !== pkg){
    askConfirm(T2('cfmAotTimeout', {n: cur.label}), function(){
      aotForcePkg = pkg;
      // 重新取一次节点：确认框弹出期间列表可能已重绘，旧节点已经脱离文档
      setTimeout(function(){
        var el = document.querySelector('#list .aot-sw[data-pkg="' + pkg + '"]');
        if (el) el.click();
        aotForcePkg = null;
      }, 60);
    });
    return;
  }

  aotBusy = true;
  aotPkg = pkg;
  aotWantOn = wantOn;
  var t0 = Date.now();
  aotT0 = t0;
  // 真实耗时不可知（微信 speed 编译要 6 分钟+），用渐近曲线：起步快、后段缓慢逼近 99%，
  // 左侧标签显示真实已用时
  aotTick();
  if (aotTimer) clearInterval(aotTimer);
  aotTimer = setInterval(aotTick, 200);
  function settle(ok){
    if (aotTimer){ clearInterval(aotTimer); aotTimer = null; }
    var el = document.querySelector('#list .aot-sw[data-pkg="' + pkg + '"]');
    if (el){
      el.classList.remove('busy');
      var f = el.querySelector('.fill'); if (f) f.style.width = '0%';
      var pe = el.querySelector('.pct'); if (pe) pe.textContent = '';
      el.classList.toggle('on', !!ok && wantOn);
      if (ok) el.setAttribute('data-aot', wantOn ? '1' : '0');
    }
    aotBusy = false;
    aotPkg = null;
  }

  fetch('/api/aot', {method:'POST', headers:{'Content-Type':'application/json'},
                     body: JSON.stringify({pkg: pkg, on: wantOn})})
    .then(function(r){ return r.json(); })
    .then(function(d){
      if (!d.ok){
        settle(false);
        // 撞了 570 秒上限：记下来，下次点之前先提醒
        if (d.timeout && cur){ aotTimeout.add(aotKey(cur)); saveAotTimeout(); }
        // busy = 后台已经有一个编译在跑（Java 侧的单飞保护），不是失败
        if (d.busy) toast(T('tCompiling'));
        else if (d.lowmem) toast(T2('tLowMem', {n: d.avail_mb}));
        else toast(T2('tFail', {m: d.msg || T('tUnknown')}));
        return;
      }
      settle(true);
      var one = null;
      for (var i2 = 0; i2 < apps.length; i2++) if (apps[i2].pkg === pkg) { one = apps[i2]; break; }
      if (wantOn) aotDone.add(aotKey(one || {pkg: pkg})); else aotDone.delete(aotKey(one || {pkg: pkg}));
      saveAot();
      if (wantOn && one){ aotTimeout.delete(aotKey(one)); saveAotTimeout(); }   // 这次成了，清掉超时记录
      var secs = Math.round((Date.now() - t0) / 1000);
      if (d.skipped) toast(wantOn ? T('tAlready') : T('tNotAot'));
      else toast(wantOn ? T2('tCompiled', {n: secs}) : T2('tReverted', {n: secs}));
    })
    .catch(function(){ settle(false); });
});

/* 桌面定位：首次 dump 找坐标（约 2.3 秒），之后 2 分钟内走缓存；
   15 秒内重复点同一个应用 = 用户觉得位置不对，强制重查。 */
var locCache = {};
try { locCache = JSON.parse(localStorage.getItem('locCache') || '{}') || {}; } catch (e) { locCache = {}; }
var locLastPkg = '', locLastAt = 0;
var idxReady = false;
/* 索引没建完（上次遍历中途被打断）：部分应用没有定位按钮，要提示去补齐 */
var idxPartial = false;
/* adbd 的 TCP 端口。默认 5555；只有用户在引导弹窗里主动改过才会变，
   旧用户完全感知不到这个功能的存在 */
var adbPort = 5555;
var idxAt = parseInt(localStorage.getItem('idxAt') || '0', 10) || 0;
var LOC_TTL = 2 * 60 * 1000;
var LOC_FORCE = 15000;
/* 索引多久算过期。桌面经常变（装应用、拖图标、开关文件夹），
   超过这个时长就提示重建 —— 否则会一直用失效索引，定位慢得像没反应。 */
var IDX_STALE_MS = 24 * 3600 * 1000;

document.getElementById('list').addEventListener('click', function(e){
  var btn = e.target.closest('[data-loc]');
  if (!btn) return;
  var pkg = btn.getAttribute('data-loc');
  var one = null;
  for (var i = 0; i < apps.length; i++) if (apps[i].pkg === pkg) { one = apps[i]; break; }
  var label = one ? one.label : '';
  var now = Date.now();
  var force = (locLastPkg === pkg && now - locLastAt < LOC_FORCE);
  var c = locCache[pkg];
  locLastPkg = pkg; locLastAt = now;

  // 🔴 刷新中列表正在重建，这会儿点定位大概率落空（实测「点了没反应」）。
  //    记下意图，刷新一结束立刻执行，同时告诉用户已经收到点击。
  if (refreshing){
    pendingLoc = {pkg: pkg, label: label, force: force, c: c, now: now};
    toast(T('locWaitRefresh'));
    return;
  }

  // 没索引、或索引已过期：问一次要不要初始化。
  //
  // 🔴 以前用 localStorage['locIdxAsked'] === '1' 做永久标记，结果是
  //    用户第一次点了「直接查找」之后**永远不会再被提示**，索引一直没建，
  //    每次定位都退化成遍历整个桌面（实测 47 秒），看起来就像「点了没反应」。
  //    改成记时间戳：24 小时内不重复打扰，过期后重新提示。
  var askedAt = parseInt(localStorage.getItem('locIdxAskedAt') || '0', 10) || 0;
  var askedRecently = askedAt > 0 && (Date.now() - askedAt < IDX_STALE_MS);
  var idxStale = idxAt > 0 && (Date.now() - idxAt > IDX_STALE_MS);
  if ((!idxReady || idxStale) && !askedRecently){
    localStorage.setItem('locIdxAskedAt', String(Date.now()));
    showChoice(T(idxStale ? 'locStaleTitle' : 'locAskTitle'),
               T(idxStale ? 'locStaleBody' : 'locAskBody'), [
      {label: T('locAskInit'), cb: function(){ goInitIndex(); }},
      {label: T('locAskDirect'), cb: function(){ doLocate(pkg, label, force, c, now); }},
      {label: T('cancel'), cb: function(){}}
    ]);
    return;
  }
  doLocate(pkg, label, force, c, now);
});

/* 索引刚跑完：立刻把「上次完成」更新成现在，并刷新列表拿到新的 idx_ready。
   遍历是在后台跑的，App 回来时前端并不知道已经完成 —— 所以 Java 那边跑完会
   回到 App 并调用 window.__idxDone()，这里就是那个入口。 */
function markIdxDone(){
  idxAt = Date.now();
  try { localStorage.setItem('idxAt', String(idxAt)); } catch (e) {}
  idxReady = true;
  idxPartial = false;
  updateIdxAt();
  updateIdxHint();
  load(true);
  // 回到设置页：让用户直接看到「上次完成：刚刚」和新的索引状态
  openSettings();
  setTimeout(updateIdxAt, 300);
}
window.__idxDone = markIdxDone;

/* 显示索引的年龄：「上次完成：3 天前」 */
function updateIdxAt(){
  var el = document.getElementById('idxAt');
  if (!el) return;
  if (!idxAt){ el.textContent = ''; return; }
  var sec = Math.max(0, (Date.now() - idxAt) / 1000);
  var txt;
  if (sec < 3600) txt = T2('idxAgeMin', {n: Math.max(1, Math.round(sec / 60))});
  else if (sec < 86400) txt = T2('idxAgeHour', {n: Math.round(sec / 3600)});
  else txt = T2('idxAgeDay', {n: Math.floor(sec / 86400)});
  el.textContent = txt;
}

/* 索引引导条：没索引 / 索引没建完时挂在列表上方，点一下直接进设置页。
   🔴 以前这两种情况是「静默」的：应用没有定位按钮、或者点了定位要翻两分钟，
      用户完全不知道为什么（实测被问到「为什么有些应用没有定位按钮」）。 */
/* 注入被系统拒绝：把本地化文案交给 Java，同时弹系统 Toast + 发一条通知
   （通知点一下直接跳到开发者选项）。只弹 Toast 不够 —— 它一两秒就没了，
   用户去设置里开完开关、重启回来，早忘了说过什么。 */
function hintInjectBlocked(){
  // 🔴 用应用内提示条而不是系统 Toast：系统 Toast 只有两行，
  //    「允许通过 USB 调试修改权限或模拟点击」这句必然被截成「…修改修…」，
  //    用户根本看不出该开哪个开关（实测截图确认）。应用内这条能整段换行。
  toast(T('injectBody'), 9000);
  // 再发一条通知：用户去设置里开完开关、重启回来，通知还在，点一下直接跳开发者选项
  try {
    if (window.Bridge && window.Bridge.hintinject) {
      window.Bridge.hintinject(0, JSON.stringify(
        {title: T('injectTitle'), text: T('injectText'), body: T('injectBody')}));
    }
  } catch (e) {}
}

function updateIdxHint(){
  var el = document.getElementById('idxHint');
  if (!el) return;
  // 用户点过「不再提示」就永远不出现（存在本机，重装/清数据才会恢复）
  if (localStorage.getItem('idxHintOff') === '1'){
    el.style.display = 'none';
    el.onclick = null;
    return;
  }
  if (!idxReady){
    el.innerHTML = T('idxHintNone') + '<span class="hint-off">' + T('idxHintOff') + '</span>';
    el.style.display = '';
    bindIdxHint(el);
  } else if (idxPartial){
    el.innerHTML = T('idxHintPartial') + '<span class="hint-off">' + T('idxHintOff') + '</span>';
    el.style.display = '';
    bindIdxHint(el);
  } else {
    el.style.display = 'none';
    el.onclick = null;
  }
}

/* 整条点击 = 去设置页；点「不再提示」= 永久关掉，不触发跳转 */
function bindIdxHint(el){
  el.onclick = function(){ openSettings(); };
  var off = el.querySelector('.hint-off');
  if (off) off.onclick = function(e){
    if (e) { e.preventDefault(); e.stopPropagation(); }
    localStorage.setItem('idxHintOff', '1');
    el.style.display = 'none';
    return false;
  };
}

/* 跳到设置页并把「开始」按钮脉冲几下，引导点击 */
function goInitIndex(){
  openSettings();
  setTimeout(function(){
    var b = document.getElementById('idxBtn');
    if (!b) return;
    try { b.scrollIntoView({block: 'center', behavior: 'smooth'}); } catch (e) {}
    b.classList.remove('pulse');
    void b.offsetWidth;              // 强制重排，让动画能重放
    b.classList.add('pulse');
    setTimeout(function(){ b.classList.remove('pulse'); }, 4500);
  }, 350);
}

function doLocate(pkg, label, force, c, now){

  // 🔴 不再用「缓存坐标直接长按」抄近路。
  //    桌面一改（拖图标、装新应用、开关文件夹），旧坐标就落到空白处，
  //    表现是「定位偏了，长按变成了长按桌面」。坐标必须每次 dump 现取。
  //    缓存里的 page 只当页号提示传给后端，省一次索引读取。
  // 🔴 没有索引时定位要遍历整个桌面（实测 47 秒）。这期间界面上只有底部一个
  //    很小的「正在寻找应用 Ns」计时，用户很容易以为「点了没反应」。
  //    5 秒还没结果就补一句明确的说明，告诉他为什么慢、怎么变快。
  var slowTip = null;
  if (!idxReady){
    slowTip = setTimeout(function(){
      toast(T('locSlowTip'), 4200);
    }, 5000);
  }
  fetch('/api/locate', {method:'POST', headers:{'Content-Type':'application/json'},
                        body: JSON.stringify({label: label, hint: T('locating'),
                                              page: (c && c.page) || -1,
                                              found: T('locFound'), nf: T('locNotFound'),
                                              pkg: pkg || ''})})
    .then(function(r){ return r.json(); })
    .then(function(d){
      if (slowTip) clearTimeout(slowTip);
      if (d && d.needA11y){ toast(T('idxNeedA11y'), 5000);
        setTimeout(function(){ fetch('/api/a11ysettings',{method:'POST'}); }, 800); return; }
      if (d && d.bridgeError){ toast(T2('idxBridgeErr', {m: d.bridgeError}), 6000); return; }
      if (d && d.needInject){ hintInjectBlocked(); return; }
      if (d && d.found){
        locCache[pkg] = {page: d.page, t: Date.now()};
        try { localStorage.setItem('locCache', JSON.stringify(locCache)); } catch (e) {}
      } else {
        delete locCache[pkg];
        try { localStorage.setItem('locCache', JSON.stringify(locCache)); } catch (e) {}
      }
    })
    .catch(function(){ if (slowTip) clearTimeout(slowTip); });
}

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
  // 同时让常驻服务立刻补一次全量：电池健康 / 归档数 / 限制数平时 3 分钟才刷，
  // 用户手点了刷新就不该等 —— 后台跑一轮，通知栏那条也跟着更新
  try { fetch('/api/refreshstat', {method:'POST'}).catch(function(){}); } catch (e) { }
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
  // 说明紧跟「性能模式」：这个开关最容易被误解成费电，解释要贴着它
  h += '<div class="info-note" style="border-top:0;margin:2px 0 0;padding:0 0 12px;'
     + 'border-bottom:0.5px solid var(--line)">' + T('perfNote') + '</div>';
  h += trowSwitch('save', T('tuneSave'), T('tuneSaveSub'), d.save);
  h += trowSwitch('balanced', T('tuneBal'), T('tuneBalSub'), d.balanced);
  h += trowSwitch('gpu', T('tuneGpu'), T('tuneGpuSub'), d.gpu);
  h += trowSwitch('game', T('tuneGame'), T('tuneGameSub'), d.game);
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
  // 面板立刻弹出；数据没到之前先给个占位，别让用户以为点空了
  if (!tuneData){
    var tb = document.getElementById('tuneBody');
    if (tb) tb.innerHTML = '<div class="panel-loading">' + T('loading') + '</div>';
  }
  tunePost('').then(function(d){ tuneData = d; renderTune(); }).catch(function(){});
}

/* 用事件委托绑定，避免元素尚未就绪时绑定失效 */
document.addEventListener('click', function(e){
  if (e.target && e.target.closest && e.target.closest('#btnTune')) openTune();
});
console.log('tune-ready');
/* 初始化应用查找功能：全量遍历桌面建索引，可中断可续传 */
var idxRunning = false;

/* 无障碍未开启时的二选一：经典查找 / 去开启无障碍。
   经典 = 模拟操作 + 截图式解析，慢但兼容；无障碍 = 直读控件树，快很多。 */
function askA11yChoice(canClassic, onClassic){
  var m = document.getElementById('modal');
  document.getElementById('modal-msg').innerHTML =
      '<div class="info-title">' + T('idxA11yTitle') + '</div>'
    + '<div class="info-note">' + T('idxA11yBody') + '</div>'
    + (canClassic ? '' : '<div class="info-note" style="color:var(--red)">'
                      + T('idxClassicOff') + '</div>');
  document.getElementById('modal-opt').style.display = 'none';
  var cancel = document.getElementById('modal-cancel');
  var ok = document.getElementById('modal-ok');
  ok.textContent = T('idxOpenA11y');
  if (canClassic){
    cancel.style.display = '';
    cancel.textContent = T('idxClassic');
  } else {
    cancel.style.display = 'none';
  }
  m.classList.add('show');
  ok.onclick = function(){
    m.classList.remove('show');
    fetch('/api/a11ysettings', {method:'POST'});
  };
  cancel.onclick = function(){
    m.classList.remove('show');
    onClassic();
  };
}

/* classic=true 表示用户已经明确选了「经典查找」，后端不再弹无障碍提示 */
function runBuild(classic){
  if (idxRunning) return;
  idxRunning = true;
  var btn = document.getElementById('idxBtn');
  btn.disabled = true;
  btn.textContent = T('idxRunning');
  toast(T('idxRunning'));
  fetch('/api/buildindex', {method:'POST', headers:{'Content-Type':'application/json'},
                            body: JSON.stringify({classic: classic ? 1 : 0})})
    .then(function(r){ return r.json(); })
    .then(function(d){
      idxRunning = false;
      btn.disabled = false;
      btn.textContent = T('idxStart');
      if (d && d.bridgeError){ toast(T2('idxBridgeErr', {m: d.bridgeError}), 6000); return; }
      if (d && d.needA11y){
        askA11yChoice(d.canClassic !== false, function(){ runBuild(true); });
        return;
      }
      if (d && d.needInject){ hintInjectBlocked(); return; }
      if (d && d.unsupported){
        // 这台设备的桌面不支持 → 引导条再挂着也没意义，直接自动隐藏
        // （不等用户点「不再提示」，否则每次打开都提醒一次「点此构建」，点完还是失败）
        localStorage.setItem('idxHintOff', '1');
        updateIdxHint();
        // 用模态而不是 toast：Java 侧的 backToApp 是异步的，toast 在后台渲染，
        // 用户回到前台时早已过期（实测两轮都抓不到）。模态会一直挂着，不怕错过。
        setTimeout(function(){
          askInfo('<div class="info-title">' + T('idxUnsupported') + '</div>'
                  + '<div class="info-note">github.com/henry-97/MineApp/issues</div>');
        }, 1200);
        return;
      }
      if (!d || !d.ok){ toast(T('idxFail')); return; }
      if (d.done) markIdxDone();
      toast(d.done ? T2('idxDone', {n: d.count}) : T('idxPaused'));
    })
    .catch(function(){
      idxRunning = false;
      btn.disabled = false;
      btn.textContent = T('idxStart');
      toast(T('idxFail'));
    });
}

document.getElementById('idxBtn').onclick = function(){ runBuild(false); };

document.getElementById('alertClose').onclick = function(){
  document.getElementById('alert').classList.remove('show');
};
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
var milletSet = new Set();     // 本次勾选的（待保存）
var milletSaved = new Set();   // 服务端当前白名单，只用于展示胶囊，不再预勾选
var milletSortMode = 'default';

function openMillet(){
  // 🔴 先开面板再拉数据。
  // 桥的 worker 是单线程，刷新列表时 /api/list 会独占它几秒 ——
  // 原来这里等 fetch 回来才 show，那几秒里点「白名单」完全没反应，像是卡死。
  // 现在立刻用上一次的数据画出面板，拿到新数据再重绘。
  document.getElementById('mq').value = '';
  milletSet = new Set();
  document.getElementById('millet').classList.add('show');
  renderMillet();
  ensureIdle(renderMillet);

  fetch('/api/millet', {method:'POST', headers:{'Content-Type':'application/json'},
                        body: JSON.stringify({})})
    .then(function(r){ return r.json(); })
    .then(function(d){
      if (!d) return;
      milletSaved = new Set((d.list || '').split(',')
        .map(function(s){ return s.trim(); }).filter(function(s){ return s.length; }));
      // 不再预勾选：白名单状态由行内的「白名单」胶囊表达，勾选框只表示「本次要加入」
      renderMillet();
      ensureIdle(renderMillet);
    })
    .catch(function(){});
}

function renderMillet(){
  var q = (document.getElementById('mq').value || '').trim().toLowerCase();
  var onlyPicked = (milletSortMode === 'picked');
  var arr = apps.filter(function(a){
    if (onlyPicked && !milletSet.has(a.pkg)) return false;
    return !q || a.pkg.toLowerCase().indexOf(q) >= 0 || a.label.toLowerCase().indexOf(q) >= 0;
  });
  if (!onlyPicked){
    applySort(arr, milletSortMode);
    // 这个页面默认把白名单应用排在最前，一眼就能看出谁已经在名单里
    if (milletSortMode === 'default') whiteFirst(arr);
  }
  var html = '';
  for (var i = 0; i < arr.length; i++){
    var a = arr[i];
    var on = milletSet.has(a.pkg);
    var saved = milletSaved.has(a.pkg);
    var cls = 'li' + (a.frozen ? ' frozen' : (a.restricted ? ' restricted' : ''))
            + (on ? ' on' : '');
    html += '<li class="' + cls + '" data-mpkg="' + a.pkg + '">'
         +  '<span class="chk">' + (on ? '✓' : '') + '</span>'
         +  '<div class="meta"><div class="name"><span class="nm">' + a.label + '</span>'
         +    sysTag(a) + '</div>'
         +  '<div class="pkg">' + a.pkg + '</div>' + subline(a) + '</div>'
         +  '<div class="rt">'
         +    (saved ? '<span class="tag b">' + T('tagWhite') + '</span>' : '')
         +    stateTag(a) + '</div></li>';
  }
  if (!html) html = '<div class="empty">' + T('empty') + '</div>';
  document.getElementById('milletList').innerHTML = html;
  updateMilletInfo();
}

function updateMilletInfo(){
  var el = document.getElementById('milletSel');
  if (el) el.innerHTML = T2('infoSel', {n: milletSet.size, t: apps.length})
        + (milletSaved.size ? ' · ' + T2('milletNow', {n: milletSaved.size}) : '');
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

/* 标题旁的 ⓘ：点一下展开说明，再点收起 */
document.getElementById('milletInfo').addEventListener('click', function(){
  var n = document.getElementById('milletNote');
  n.style.display = (n.style.display === 'none' || !n.style.display) ? 'block' : 'none';
});

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
  function doSave(){
    fetch('/api/millet', {method:'POST', headers:{'Content-Type':'application/json'},
                          body: JSON.stringify({csv: csv})})
      .then(function(r){ return r.json(); })
      .then(function(){
        toast(T2('tSaved', {n: milletSet.size}));
        document.getElementById('millet').classList.remove('show');
        if (tuneData) tuneData.millet_count = milletSet.size;
        load(true);   // 主列表要重刷，否则「白名单」胶囊不会更新
      });
  }
  // 保存是整体覆盖：勾得比原来少就会删条目，先确认一次，别静默丢配置
  if (milletSaved.size > milletSet.size){
    askConfirm(T2('cfmMillet', {a: milletSaved.size, b: milletSet.size}), doSave);
    return;
  }
  doSave();
};

/* ---------- 设置页：名称 / 主题 ---------- */
document.getElementById('appTitle').addEventListener('click', openSettings);

document.getElementById('setClose').onclick = closeSettings;

/* 设置页右上角的时钟按钮 → 操作历史 */
document.getElementById('setHistory').onclick = openHistory;
document.getElementById('hisClose').onclick = closeHistory;
document.getElementById('hisClear').onclick = function(){
  askConfirm(T('hisClearAsk'), function(){
    fetch('/api/historyclear', {method:'POST'})
      .then(function(r){ return r.json(); })
      .then(function(){ loadHistory(); })
      .catch(function(){});
  });
};
/* 改条数：失焦或回车时提交。
   规则：纯数字才接受；超过 10000 截断成 10000；
   出现任何非数字（含正负号、字母、空）一律回落到默认值 200。 */
document.getElementById('hisLimit').addEventListener('change', function(){
  var raw = String(this.value == null ? '' : this.value).trim();
  var v;
  if (!/^\d+$/.test(raw)){
    v = 200;                       // 非纯数字 → 默认值（0 是纯数字，允许）
  } else {
    v = parseInt(raw, 10);
    if (v > 10000) v = 10000;      // 超上限截断
  }
  this.value = v;
  fetch('/api/historylimit', {
    method:'POST',
    headers: {'Content-Type':'application/json'},
    body: JSON.stringify({limit: v})
  }).then(function(r){ return r.json(); })
    .then(function(){ loadHistory(); })
    .catch(function(){});
});

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
  // 记「已同意」而不是「这个版本已同意」：覆盖安装保留、卸载重装清空，
  // 正好对应「只有全新安装才需要再看一次」。
  // 同时写后端：WebView 的 localStorage 会被系统清掉，SharedPreferences 才是权威。
  localStorage.setItem('riskOk', '1');
  fetch('/api/riskagree', {method:'POST'}).catch(function(){});
  document.getElementById('risk').classList.remove('show');
});
document.getElementById('riskQuit').addEventListener('click', function(){
  // 有 Java 桥就真的退出；Termux 版没有桥，退回关闭窗口
  if (window.Bridge && window.Bridge.exitApp) { window.Bridge.exitApp(); return; }
  window.close();
});

document.getElementById('tipChk').addEventListener('change', function(){
  showTip = this.checked;
  localStorage.setItem('showTip', showTip ? '1' : '0');
  // 重新打开「界面提示」时，把「清理不再提示」也一起复位 ——
  // 否则用户勾过一次之后，就再也没有入口能把它改回来了
  if (showTip) localStorage.removeItem('killNoAsk');
  applyTip();
  renderSettings();
});

/* ---------- 保活 ----------
   启动时静默做两件事：把自己加进电源管理白名单（界面不展示这条）、
   按上次保存的选择决定是否隐藏最近任务卡片。默认隐藏。 */
var keepHide = true, keepInited = false;

function renderKeep(){
  var c = document.getElementById('showCardChk');
  if (c) c.checked = !keepHide;          // 开关语义是「显示卡片」，与 hide 相反
}

function keepAliveInit(){
  fetch('/api/keepalive', {method:'POST'})
    .then(function(r){ return r.json(); })
    .then(function(d){
      if (!d || !d.ok) return;
      keepHide = !!d.hide_recents; renderKeep();
      // 刚更新完 → 自动打开设置页（标记由 Java 侧读取后即清除）
      if (d.open_settings) setTimeout(function(){ openSettings(); }, 400);
    })
    .catch(function(){});
}

(function(){
  var c = document.getElementById('showCardChk');
  if (!c) return;
  c.addEventListener('change', function(){
    var hide = !c.checked;
    fetch('/api/hiderecents', {method:'POST', headers:{'Content-Type':'application/json'},
                               body: JSON.stringify({on: hide})})
      .then(function(r){ return r.json(); })
      .then(function(d){
        if (!d || !d.ok){ toast(T('keepFail')); c.checked = !c.checked; return; }
        keepHide = hide; renderKeep();
        toast(hide ? T('keepHidden') : T('keepShown'));
      })
      .catch(function(){ toast(T('tFail')); c.checked = !c.checked; });
  });
})();

/* 「界面提示」这个词本身看不出指什么，点 ⓘ 给一句解释 */
(function(){
  var i = document.getElementById('tipHelp');
  if (!i) return;
  i.addEventListener('click', function(e){ e.stopPropagation(); toast(T('tipHelp')); });
})();

/* 打开系统电池设置：读不到设计容量时，让用户自己去系统里看健康度 */
/* 文案里的内联链接（查看 / 通知 / 自启动）都用事件委托 —— applyLang 重绘会重建节点 */
document.addEventListener('click', function(e){
  if (!e.target) return;
  var id = e.target.id;
  if (id !== 'lnkNotif' && id !== 'lnkAuto' && id !== 'lnkPower') return;
  e.preventDefault();
  var k = id === 'lnkNotif' ? 'notif' : (id === 'lnkAuto' ? 'autostart' : 'power');
  fetch('/api/opensetting', {method:'POST', headers:{'Content-Type':'application/json'},
                             body: JSON.stringify({k: k})})
    .catch(function(){});
});

/* 「查看」是内联在文案里的链接，applyLang 重绘会重建节点 —— 用事件委托才绑得住 */
document.addEventListener('click', function(e){
  if (!e.target || e.target.id !== 'lnkBattSet') return;
  e.preventDefault();
  fetch('/api/battsettings', {method:'POST'}).catch(function(){});
});

/* 电池设计容量：留空 = 回到自动探测；填了就以填的为准 */
(function(){
  var din = document.getElementById('battDesignIn');
  if (!din) return;
  din.addEventListener('change', function(){
    var raw = din.value.trim();
    var v = raw === '' ? 0 : parseInt(raw, 10);
    // 必须整串都是数字：parseInt('1000d') 会静默得到 1000，把脏输入当合法值
    if (raw !== '' && (!/^[0-9]+$/.test(raw) || isNaN(v) || v < 1000 || v > 30000)){
      toast(T('battDesignBad'));
      din.value = '';
      return;
    }
    fetch('/api/design', {method:'POST', headers:{'Content-Type':'application/json'},
                          body: JSON.stringify({mah: v})})
      .then(function(r){ return r.json(); })
      .then(function(d){
        if (!d || !d.ok){ toast(T('tFail')); return; }
        toast(v ? T2('battDesignSaved', {n: v}) : T('battDesignCleared'));
        load(true);          // 重拉列表，健康度按新容量立刻重算
      })
      .catch(function(){ toast(T('tFail')); });
  });
})();

/* ---------- 检查更新 ----------
   默认不联网：只有用户点「检查」或开了自动检查且距上次超过 24 小时，才会发一次 GET。
   请求只带 GitHub 公开 API 的常规头，不上传任何本机信息。 */
var UPD_AUTO = 'updAuto', UPD_LAST = 'updLast';
var updUrl = '', updApk = '';

function cmpVer(a, b){
  a = String(a || '').replace(/^v/i, '').split('.');
  b = String(b || '').replace(/^v/i, '').split('.');
  for (var i = 0; i < 3; i++){
    var x = parseInt(a[i] || '0', 10) || 0, y = parseInt(b[i] || '0', 10) || 0;
    if (x !== y) return x > y ? 1 : -1;
  }
  return 0;
}

/* 启动时读一次版本号存起来：updState 的文案会被检查结果覆盖，不能每次去 DOM 里取 */
var CUR_VER = (function(){
  var el = document.querySelector('[data-ver]');
  return el ? (el.getAttribute('data-ver') || '') : '';
})();
function curVer(){ return CUR_VER; }

/* 外链一律交给系统浏览器：WebView 里直接跳转会把整个界面替换掉 */
function openExtUrl(url){
  var a = document.createElement('a');
  a.href = url; a.style.display = 'none';
  document.body.appendChild(a);
  a.click();
  setTimeout(function(){ if (a.parentNode) a.parentNode.removeChild(a); }, 0);
}

/* 记录状态而不是直接写死文案：切换语言时要能按新语言重绘 */
var updKind = 'current', updVar = '';
/* 标题栏直接显示版本号；检查结果只临时顶替 1 秒，随后自动回到版本号。
   用短文案（4~5 字）而不是「已是最新版本 2.1.0」，因为标签列只有 84px。 */
var updTimer = null;

function renderUpdState(){
  var el = document.getElementById('updLabel');
  if (!el) return;
  if (updTimer){ clearTimeout(updTimer); updTimer = null; }
  // 进行中状态（检查中 / 将自动重启）必须常驻，直到被下一个状态替换 ——
  // 只有终态（已是最新 / 发现新版本 / 检查失败）才需要 2 秒后回落到版本号
  if (updKind === 'restarting'){ el.textContent = T('updRestart'); return; }
  if (updKind === 'checking'){ el.textContent = T('updCheckingShort'); return; }
  var k = {latest:'updLatestShort', new:'updNewShort', fail:'updFailShort'}[updKind];
  if (!k){ el.textContent = T2('updCurrent', {v: curVer()}); return; }
  el.textContent = updVar ? T2(k, {v: updVar}) : T(k);
  updTimer = setTimeout(function(){
    updKind = 'current';
    el.textContent = T2('updCurrent', {v: curVer()});
  }, 2000);
}
function setUpdState(kind, v){ updKind = kind; updVar = v || ''; renderUpdState(); }

function checkUpdate(silent){
  var btn = document.getElementById('updBtn');
  if (btn) btn.disabled = true;
  if (!silent) setUpdState('checking');
  fetch('/api/update', {method:'POST'})
    .then(function(r){ return r.json(); })
    .then(function(d){
      if (btn) btn.disabled = false;
      localStorage.setItem(UPD_LAST, String(Date.now()));
      if (!d || !d.ok){
        setUpdState('fail');
        if (!silent) toast(T('updFail'));
        return;
      }
      // 用后端清洗过的 ver：多通道版的 tag 是 multi-v3.9.0，
      // 直接拿 tag 比大小会 parseInt('multi-v3') = NaN → 永远判成「已是最新」
      var latest = String(d.ver || d.tag || '').replace(/^v/i, '');
      if (cmpVer(latest, curVer()) > 0){
        updUrl = d.url || '';
        updApk = d.apk || '';                 // 有资产地址就走应用内静默安装
        setUpdState('new', latest);
        toast(T2('updNew', {v: latest}));
        var label = updApk ? 'updInstall' : 'updOpen';
        if (btn){ btn.textContent = T(label); btn.setAttribute('data-t', label); }
      } else {
        updUrl = ''; updApk = '';
        setUpdState('latest', curVer());
        if (!silent) toast(T2('updLatest', {v: curVer()}));
        if (btn){ btn.textContent = T('updCheck'); btn.setAttribute('data-t', 'updCheck'); }
      }
    })
    .catch(function(){
      if (btn) btn.disabled = false;
      setUpdState('fail');
      if (!silent) toast(T('updFail'));
    });
}

/* 应用内静默安装：下载 → cp 到 /data/local/tmp → pm install -r，全程无系统安装器弹窗。
   失败（签名变了 / 系统拒绝）就退回浏览器下载，不让用户卡住。 */
var progTimer = null;

function stopProg(){
  if (progTimer){ clearInterval(progTimer); progTimer = null; }
}

/* 把「更新」按钮本身当进度条：背景按百分比填充，文字跟着变 */
function paintProg(pct, text){
  var btn = document.getElementById('updBtn');
  if (!btn) return;
  btn.classList.add('prog');
  btn.style.setProperty('--p', (pct || 0) + '%');
  if (text != null) btn.textContent = text;
}

function clearProg(){
  var btn = document.getElementById('updBtn');
  if (!btn) return;
  btn.classList.remove('prog');
  btn.style.removeProperty('--p');
}

/* 每 300ms 拉一次进度 */
function pollProg(){
  fetch('/api/installprogress', {method:'POST'})
    .then(function(r){ return r.json(); })
    .then(function(d){
      if (!d) return;
      if (d.phase === 'download'){
        paintProg(d.pct || 0, (d.pct || 0) + '%');
      } else if (d.phase === 'finishing'){
        paintProg(100, T('updFinishing'));   // 满格停留，随后才重启
      } else if (d.phase === 'install'){
        paintProg(100, T('updFinishing'));   // 不再用「更新中」：三个字会把按钮撑宽
      } else if (d.phase === 'done'){
        paintProg(100, T('updDone'));
      } else if (d.phase === 'fail'){
        clearProg();
        var b = document.getElementById('updBtn');
        if (b) b.textContent = T('updInstallFail');
      }
    })
    .catch(function(){});
}

function installUpdate(){
  var btn = document.getElementById('updBtn');
  if (btn) btn.disabled = true;
  setUpdState('restarting');          // 常驻：将自动重启
  paintProg(0, '0%');                 // 按钮自己当进度条
  pollProg();
  stopProg();
  progTimer = setInterval(pollProg, 300);
  fetch('/api/installupdate', {method:'POST', headers:{'Content-Type':'application/json'},
                               body: JSON.stringify({url: updApk})})
    .then(function(r){ return r.json(); })
    .then(function(d){
      pollProg();
      if (d && d.ok){ toast(T('updInstallDone')); return; }   // 进程随后会被系统替换
      stopProg(); clearProg();
      fallbackDownload();
    })
    .catch(function(){ stopProg(); clearProg(); fallbackDownload(); });
}

function fallbackDownload(){
  var btn = document.getElementById('updBtn');
  if (btn){ btn.disabled = false; btn.textContent = T('updOpen'); }
  toast(T('updInstallFail'));
  if (updUrl) openExtUrl(updUrl);
}

document.getElementById('szBtn').addEventListener('click', askShizuku);

/* 从 Shizuku 授权页返回时刷新一次状态（WebView 恢复可见即触发） */
document.addEventListener('visibilitychange', function(){
  if (!document.hidden && typeof renderShizuku === 'function') renderShizuku();
});

document.getElementById('updBtn').addEventListener('click', function(){
  if (updApk){ installUpdate(); return; }      // 有安装包 → 应用内装
  if (updUrl){ openExtUrl(updUrl); return; }   // 只有网页 → 去下载页
  checkUpdate(false);
});

var updChk = document.getElementById('updAutoChk');
if (updChk){
  updChk.checked = localStorage.getItem(UPD_AUTO) === '1';
  updChk.addEventListener('change', function(){
    localStorage.setItem(UPD_AUTO, updChk.checked ? '1' : '0');
    toast(updChk.checked ? T('updAutoOn') : T('updAutoOff'));
  });
}
setUpdState('current', curVer());

/* 自动检查：默认关闭。开着也要距上次超过 24 小时才发请求，启动时不打扰 */
setTimeout(function(){
  if (localStorage.getItem(UPD_AUTO) !== '1') return;
  var last = parseInt(localStorage.getItem(UPD_LAST) || '0', 10);
  if (Date.now() - last < 24 * 3600 * 1000) return;
  checkUpdate(true);
}, 6000);

document.getElementById('iconToggle').addEventListener('click', function(e){
  e.preventDefault();
  iconExpanded = !iconExpanded;
  renderIcons();
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

/* 底部内存由原生侧主动推过来：KeepAliveService 每 3 秒查一次内存（通知栏本来就要查），
   查完直接调这个函数，不再让前端挂定时器轮询 —— 一条命令的结果两处共用。 */
window.__onMem = function(total, avail, pct){
  if (!total) return;
  memTotal = total;
  memAvail = avail || 0;
  memPct = pct || 0;
  updateMemOnly();
};

/* Termux 版没有 Java 桥，图标选择器无从落地，整段（标题 + 展开 + 网格）一起藏掉 */
if (!HAS_BRIDGE) {
  ['iconSec', 'iconGrid'].forEach(function(id){
    var el = document.getElementById(id);
    if (el) el.style.display = 'none';
  });
}
load();
</script>
</body>
</html>
'''
