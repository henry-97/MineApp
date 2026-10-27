# Mine App 3.0.9

> 更新完自动拉起的等待时间，从「固定 2 秒」改成「装好就起」。

---

## 改了什么

原来的拉起是一个固定节奏的循环：

```sh
for i in 1 2 3; do sleep 2; am start ...; done
```

问题很直接 —— 不管安装用了多久，**第一次尝试都在 2 秒后**。装得快就要白等，装得慢又可能三次全落空。

现在改成「先试，再确认」：

```sh
for i in 1..15; do
  am start --activity-single-top -n pkg/.MainActivity
  sleep 0.2
  dumpsys activity activities | grep -q "topResumedActivity.*pkg" && exit 0
  sleep 0.3
done
```

| | 旧 | 新 |
|---|---|---|
| 首次尝试 | 固定等 2 秒后 | **立刻** |
| 成功判定 | 无法判断，固定跑 3 次 | 查 `topResumedActivity` 是否真是自己 |
| 装得慢 | 3 次用完就放弃 | 15 轮（≈8 秒）兜底 |

安装完成的那一刻就会拉起，实际等待 ≈ **安装耗时 + 0.5 秒**。

实测：从桌面用这套循环拉起 App，耗时 **0.50 秒**。

真实更新时前几轮会因为包管理器忙而失败 —— 无害，装好的那一轮就会成功。

---

## What's new (English)

- The post-install relaunch no longer waits a fixed 2 seconds before its first attempt. It now tries
  immediately, verifies via `dumpsys activity activities` that the app is actually in the foreground,
  and exits as soon as it is — up to 15 rounds (~8 s) for slow installs. Measured launch time: 0.50 s.
