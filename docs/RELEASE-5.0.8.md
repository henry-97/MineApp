## Mine App 5.0.8 · 双版本更新

### 双版本共同改动

**「限制」换了一套更硬的实现 —— 从这一版起，界面上的「已限制」才真的对得起它**

上一版用的 `am set-inactive`，本质是把应用的**待机分组**写成「罕见」档。真机实测两个坑：

| 坑 | 表现 |
|---|---|
| 白名单应用写不进去 | 只要应用在系统的**电池优化白名单**里（设置里给过「无限制」），分组恒为 `EXEMPTED`，此后所有写操作**静默失败** —— 命令不报错、状态一动不动，界面却照旧显示「已限制」 |
| 和分组是同一套东西 | `set-inactive` 会把已经更严的档位降回来 |

现在改成三步：

```
cmd deviceidle whitelist -<pkg>            # 先移出电池优化白名单
am set-standby-bucket <pkg> restricted     # 直接写系统最严档
cmd appops set <pkg> RUN_ANY_IN_BACKGROUND ignore   # 四条后台权限
cmd appops set <pkg> RUN_IN_BACKGROUND ignore
cmd appops set <pkg> START_FOREGROUND ignore
cmd appops set <pkg> WAKE_LOCK ignore
```

最后那条 `RUN_ANY_IN_BACKGROUND` 是**兜底**：系统给最严档配的执法点本来就是它。
而 ROM 会拒绝把「刚用过」的应用降进最严档（实测 60 个受限应用里长期卡住十几个），
这种时候分组写不进去，但这条权限写得进去、也不会被回滚 —— 后台起服务和拉闹钟照样拦得住。

**实测数据**（Redmi / HyperOS 3.0 / Android 15）：

| 项 | 结果 |
|---|---|
| 迁移 | 110 个受限应用，一次通过 |
| 电池优化白名单 | 50 条 → 31 条 |
| 60 个第三方受限应用落到最严档 | 44 个（改造前 1 个） |
| 后台权限 `RUN_ANY_IN_BACKGROUND=ignore` | 60 / 60 |
| 抽查最严档应用进程 | 全部 `frozen 1` |

**解除限制**会把上面全部撤回：分组放回活跃档、四条权限恢复、索引清掉；
只有当初被我们移出白名单的应用才会加回白名单，不会反向过度放行。

**升级后旧限制名单里的应用会自动迁移到新实现**，不需要重新限制一遍；迁移只做一次。
旧的两次迁移（appops 墓碑、补前台服务权限）已删除，也不再读取它们留下的布尔标记。

需要收消息的应用，点「解除限制」即可恢复。

### 经典版

本版无专属改动。

### 多通道版

本版无专属改动。

---

### 下载哪个

| 文件 | 包名 | 说明 |
|---|---|---|
| `MineApp-5.0.8.apk` | `com.henry.appmanager` | 经典版（仅 ADB 通道） |
| `MineApp-5.0.8-multi.apk` | `com.henry.appmanager.multi` | 多通道版（ADB / Root / Shizuku） |

两个包可以同时安装，互不影响。升级时请下载与当前已装版本对应的那一个，包名不同无法互相覆盖。
