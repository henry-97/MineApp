# 使用须知 · Usage notes

<p align="center">
  <a href="README.md">简体中文</a> · <a href="README.en.md">English</a>
</p>

本软件通过 ADB 直接调用系统 `pm` / `am` / `cmd` 命令，**能力很强**。

请**不要随意冻结或限制系统必要组件**（时钟、桌面启动器、输入法、电话 / 短信等），
否则可能导致闹钟不响、桌面异常、收不到来电等问题。

如果不慎误操作、且本软件已经无法打开，可以用数据线连电脑执行：

```bash
adb shell pm enable <包名>
```

把对应组件重新启用即可恢复。

**因使用不当造成的数据丢失或设备异常，作者不承担责任。**

---

This app drives the system `pm` / `am` / `cmd` commands directly through ADB — it is **genuinely powerful**.

**Do not freeze or restrict components the system depends on** (Clock, the launcher, the keyboard,
Phone / Messaging, …). Doing so can stop alarms, break the home screen, or make the phone unable to
receive calls.

If you lock yourself out and the app can no longer be opened, connect the phone to a PC and run:

```bash
adb shell pm enable <package>
```

That re-enables the component and gets you back in.

**The author is not liable for data loss or device misbehaviour caused by misuse.**

---

- [返回项目主页 / Back to the project](https://github.com/henry-97/MineApp)
