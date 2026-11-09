#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
构建期守卫：Bridge 里对 JS 暴露的接口必须带 @JavascriptInterface。

为什么需要它：
    3.9.0 的重构把 buildindex 的注解弄丢了。JS 调不到 → shim 的 catch 吞掉异常
    → 返回空对象 → 界面统一显示「索引构建失败」，而后端一行日志都没有。
    这种错误能正常编译、能正常安装，只有用户点了才会暴露，且症状完全误导人。

判定规则：
    `public void xxx(int id, ...)` 这种以 id 作首参的方法就是给 JS 用的，
    它的上一非空行必须是 @JavascriptInterface。
"""
import re
import sys
import pathlib

SRC = pathlib.Path(__file__).resolve().parent.parent / "src/java/com/henry/appmanager/Bridge.java"

PAT = re.compile(r"public\s+void\s+(\w+)\s*\(\s*(?:final\s+)?int\s+id\b")

def main() -> int:
    lines = SRC.read_text(encoding="utf-8").splitlines()
    missing = []
    total = 0
    for i, line in enumerate(lines):
        m = PAT.search(line)
        if not m:
            continue
        total += 1
        j = i - 1
        while j >= 0 and not lines[j].strip():
            j -= 1
        if j < 0 or "@JavascriptInterface" not in lines[j]:
            missing.append((m.group(1), i + 1))

    if missing:
        print("✗ 以下接口缺少 @JavascriptInterface，JS 将无法调用：", file=sys.stderr)
        for name, ln in missing:
            print("    Bridge.java:%d  %s" % (ln, name), file=sys.stderr)
        print("  这些接口在界面上会表现为「操作失败」，且后端不会有任何日志。", file=sys.stderr)
        return 1

    print("  JS 接口注解检查通过（%d 个）" % total)
    return 0


if __name__ == "__main__":
    sys.exit(main())
