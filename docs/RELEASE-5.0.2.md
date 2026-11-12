## Mine App 5.0.2 · 双版本更新

### 双版本共同改动

**1. 解限顺序调整：索引标记最后撤**

一次「解除限制」要撤四道开关，现在把**索引标记挪到最后**：

```bash
am set-inactive --user 0 <包名> false          # ① 撤墓碑
cmd appops set <包名> START_FOREGROUND default  # ② 撤禁前台服务
cmd appops set <包名> WAKE_LOCK default         # ③ 撤禁唤醒锁
cmd appops set <包名> RUN_IN_BACKGROUND allow   # ④ 最后撤索引 ← 挪到这里
```

原因：`RUN_IN_BACKGROUND=ignore` 是「一键解除」的入口（`appops query-op` 靠它列出全部被限制的应用）。如果先撤它，后面哪一步失败，这个应用就从名单里消失了，再也没法批量重试。

「限制」的顺序也对称调整了——索引**最先立**，这样即使后续步骤失败，应用仍在名单里，下次能查到并补做。

App 内按钮和 README 里的命令都已同步。

**2. 首次安装迁移不再白跑一趟**

限制升级时会自动给旧限制补上新开关，但之前有个时序问题：

```
App 启动 → 等 3.5 秒 → 查通道 → Shizuku binder 还没就绪 → 跳过
（没写标记）→ 下次启动重试 → 这次成功了
```

实测就是这样：装完第一次启动没生效，第二次才迁移成功。

现在改成**循环等通道，最多 10 秒**：

```java
int tries = 0;
while (tries < 5 && !"ok".equals(state())) { Thread.sleep(2000); tries++; }
if (!"ok".equals(state())) return;   // 真没通道才放弃
```

首次安装打开 App，最多等 10 秒就会完成迁移。

### 经典版

本版无专属改动。

### 多通道版

本版无专属改动。

---

### 下载哪个

| 文件 | 包名 | 说明 |
|---|---|---|
| `MineApp-5.0.2.apk` | `com.henry.appmanager` | 经典版（仅 ADB 通道） |
| `MineApp-5.0.2-multi.apk` | `com.henry.appmanager.multi` | 多通道版（ADB / Root / Shizuku） |

两个包可以同时安装，互不影响。升级时请下载与当前已装版本对应的那一个，包名不同无法互相覆盖。
