## Mine App 5.0.1 · 双版本更新

### 双版本共同改动

**1. 「限制」升级为三层生效 + 一层索引**

以前只有墓碑一层。现在一次点击同时施加四道：

| 层 | 命令 | 作用 |
|---|---|---|
| ① 墓碑 | `am set-inactive` | 冻结后台进程，点开时同一进程原地复活 |
| ② 索引标记 | `appops RUN_IN_BACKGROUND ignore` | 不生效（ROM 忽略），但用来一条命令查出全部限制项 |
| ③ **禁前台服务** | `appops START_FOREGROUND ignore` | **新增**：堵掉「我是前台服务」这条保命路 |
| ④ **禁唤醒锁** | `appops WAKE_LOCK ignore` | **新增**：待机时不持锁，不把 CPU 拽醒 |

第③项是这次的关键。很多国产应用靠「我常驻在前台服务里」躲过后台清理——把它启动前台服务的权限关掉，墓碑才压得实。

第②项虽然不生效，但**必须保留**：`appops query-op` 一条命令就能查出所有被限制的应用，比逐个 `am get-inactive`（605 个包要几十秒）快得多。

**2. 升级自动补齐**

老用户手机里已有的限制只有前两层，升级后会自动补上第③④层：

- 首次启动时跑一次，给所有已限制应用批量补两项（一条 shell，约 5~10 秒）
- 用独立的布尔标记 `deepRestrictMigrated` 记录，跑过就永久跳过
- 全部成功才写标记，中途断线会在下次启动重试

**3. 「解除限制」四层一起撤**

对应地，解限会把四道全部撤回：

```java
am set-inactive --user 0 <包名> false
cmd appops set <包名> RUN_IN_BACKGROUND allow
cmd appops set <包名> START_FOREGROUND default
cmd appops set <包名> WAKE_LOCK default
```

后两项用 `default`（恢复系统默认）而不是 `allow`，避免把本来就被系统限制的应用改成完全放行。

**4. 只有一个进程的应用现在也会显示**

以前只有多进程才标 `N进程 ›`，现在 1 个进程也标，点开能看到是哪条进程、占多少内存、CPU 时间、oom 档位。

**5. 界面细节**

- 展开的进程面板不再有大片空白（`.proc-wrap` 继承了全局 `li{min-height:68px}`，已覆盖为 0）
- 不活跃的进程不再显示灰点（透明占位，多行名称仍对齐）

### 经典版

本版无专属改动。

### 多通道版

本版无专属改动。

---

### 下载哪个

| 文件 | 包名 | 说明 |
|---|---|---|
| `MineApp-5.0.1.apk` | `com.henry.appmanager` | 经典版（仅 ADB 通道） |
| `MineApp-5.0.1-multi.apk` | `com.henry.appmanager.multi` | 多通道版（ADB / Root / Shizuku） |

两个包可以同时安装，互不影响。升级时请下载与当前已装版本对应的那一个，包名不同无法互相覆盖。
