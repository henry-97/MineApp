#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""从 Termux 版的 server.py 生成 APK 需要的资源。

产出：
  assets/index.html      —— 原样提取前端，仅注入一层 fetch→Java 桥接的 shim
  src/java/.../AppNames.java —— 包名到中文名的映射

这样前端逻辑（排序、持久化、交互）与 Termux 版完全一致，不产生第二份实现。
"""
import os
import re
import sys

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(os.path.dirname(BASE), "termux", "webui", "server.py")
PKG_DIR = os.path.join(BASE, "src", "java", "com", "henry", "appmanager")

SHIM = r"""
<script>
/* 本地桥接：把 fetch 重定向到 Java 层，前端逻辑无需任何改动 */
(function(){
  var seq = 0, pending = {};
  window.__bridgeResult = function(id, json){
    var cb = pending[id];
    if (!cb) return;
    delete pending[id];
    var data = {};
    try { data = JSON.parse(json); } catch (e) { data = {}; }
    cb(data);
  };
  function call(method, arg){
    return new Promise(function(resolve){
      var id = ++seq;
      pending[id] = resolve;
      try { Bridge[method](id, arg); }
      catch (e) { delete pending[id]; resolve({}); }
    });
  }
  window.fetch = function(url, opts){
    var body = {};
    if (opts && opts.body) { try { body = JSON.parse(opts.body); } catch (e) {} }
    var pkgs = JSON.stringify(body.pkgs || []);
    var p;
    if (url.indexOf('/api/list') === 0) p = call('list', '');
    else if (url.indexOf('/api/freeze') === 0) p = call('freeze', pkgs);
    else if (url.indexOf('/api/unfreeze') === 0) p = call('unfreeze', pkgs);
    else if (url.indexOf('/api/restrict') === 0) p = call('restrict', pkgs);
    else if (url.indexOf('/api/unrestrict') === 0) p = call('unrestrict', pkgs);
    else if (url.indexOf('/api/perf') === 0) p = call('perf', body.on ? '1' : '0');
    else if (url.indexOf('/api/usage') === 0) p = call('usage', '');
    else if (url.indexOf('/api/tune') === 0) p = call('tune', body.k || '');
    else if (url.indexOf('/api/millet') === 0) p = call('millet', body.csv || '');
    else if (url.indexOf('/api/name') === 0) p = call('name', body.name || '');
    else if (url.indexOf('/api/icon') === 0) p = call('icon', body.key || '');
    else if (url.indexOf('/api/aot') === 0) p = call('aot', (body.pkg || '') + '|' + (body.on ? '1' : '0'));
    else p = Promise.resolve({});
    return p.then(function(d){
      return { ok: true, json: function(){ return Promise.resolve(d); } };
    });
  };
})();
</script>
"""


def main() -> int:
    src = open(SRC, encoding="utf-8").read()

    m = re.search(r"HTML = r'''(.*?)'''\n", src, re.S)
    if not m:
        print("未找到 HTML 块")
        return 1
    html = m.group(1)

    # 在 <body> 之后注入桥接 shim，保证先于页面脚本执行
    idx = html.find("<body>")
    if idx < 0:
        print("未找到 <body>")
        return 1
    idx += len("<body>")
    html = html[:idx] + SHIM + html[idx:]

    # Android WebView 里 env(safe-area-inset-*) 恒为 0，
    # 改为优先读 CSS 变量（由 MainActivity 按真实 insets 注入），回退到 env()。
    html = html.replace("env(safe-area-inset-top)", "var(--sat, env(safe-area-inset-top))")
    html = html.replace("env(safe-area-inset-bottom)", "var(--sab, env(safe-area-inset-bottom))")

    os.makedirs(os.path.join(BASE, "assets"), exist_ok=True)
    with open(os.path.join(BASE, "assets", "index.html"), "w", encoding="utf-8", newline="\n") as f:
        f.write(html)

    # ---- 包名映射 ----
    m = re.search(r"APP_NAMES = \{(.*?)\n\}", src, re.S)
    if not m:
        print("未找到 APP_NAMES")
        return 1
    pairs = re.findall(r'"([^"]+)"\s*:\s*"([^"]+)"', m.group(1))

    lines = [
        "package com.henry.appmanager;",
        "",
        "import java.util.HashMap;",
        "import java.util.Map;",
        "",
        "/** 由 tools/gen_assets.py 从 server.py 的 APP_NAMES 自动生成，请勿手改。 */",
        "public final class AppNames {",
        "    public static final Map<String, String> MAP = new HashMap<String, String>();",
        "    static {",
    ]
    for k, v in pairs:
        lines.append('        MAP.put("%s", "%s");' % (k, v))
    lines += ["    }", "", "    private AppNames() { }", "}", ""]

    os.makedirs(PKG_DIR, exist_ok=True)
    with open(os.path.join(PKG_DIR, "AppNames.java"), "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines))

    print("index.html   %d 字节" % len(html.encode("utf-8")))
    print("AppNames.java %d 条映射" % len(pairs))
    return 0


if __name__ == "__main__":
    sys.exit(main())
