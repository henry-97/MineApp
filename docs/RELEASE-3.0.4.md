# Mine App 3.0.4

> 3.0.2 / 3.0.3 里「更新完自动回到设置页」只做对了一半：跳转生效了，但**没自动拉起**。

---

## 一、拉起失败的原因

3.0.2 写的是：

```bash
pm install -r xxx.apk ; sleep 1.5 ; am start -n .../.MainActivity
```

看起来很合理 —— shell 是设备侧进程，不该受 App 进程影响。

**实测证明这个假设是错的。** 用户更新后反馈：应用没被拉起，但手动打开时**确实停在设置页**（说明跳转标记是好的，只有拉起没执行）。

原因是安装过程中本进程被杀，**同一条 shell 里后面的 `sleep` / `am start` 也跟着没了**。串行执行等于把拉起任务押在一个会被杀掉的进程上。

## 二、改成「先脱离，再安装」

```bash
cp apk /data/local/tmp/x.apk
(setsid sh -c 'for i in 1 2 3; do sleep 2; am start --activity-single-top -n .../.MainActivity; done' &)
pm install -r /data/local/tmp/x.apk
```

三处关键：

| 做法 | 作用 |
|---|---|
| `setsid` | 把拉起任务从当前会话摘出去，安装杀进程也波及不到它 |
| 先脱离**再**安装 | 顺序反了就来不及 |
| `--activity-single-top` | 重复调用只触发 `onNewIntent`，不会叠出多个实例 |

循环 3 次（2s / 4s / 6s）是为了兜住「包管理器还没收工」的那几秒。

顺手把整条命令的输出写进 `/data/local/tmp/mineapp_update.log`，下次再出问题可以直接看日志，不用猜。

## 三、两处文案

- 状态从「发现新版本」改成带版本号的「**发现 v 3.0.3**」
- 所有临时状态的停留时间从 1 秒改成 **2 秒**（1 秒太短，眼睛容易错过）

---

## What's new (English)

- **Fixed the post-install relaunch.** 3.0.2 chained `am start` after `pm install` in the same shell —
  but that shell dies together with the app process being replaced, so the launch never ran. The app
  now detaches the launch task with `setsid` *before* installing, retries three times, and uses
  `--activity-single-top` so repeats are harmless.
- Update log is written to `/data/local/tmp/mineapp_update.log` for diagnosis.
- Status label now includes the version ("found v 3.0.3"), and transient states linger 2 s instead of 1 s.
