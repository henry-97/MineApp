# Mine App 3.6

> **应用定位**：点一下，直接带你找到图标在哪一页、哪个文件夹。

---

## 一、应用定位（新功能）

应用名右边多了一个定位按钮。点它：

1. 自动回到桌面
2. 按应用名在无障碍树里找到图标坐标
3. 长按那个图标 —— 图标浮起、弹出菜单

**覆盖范围**：

| 位置 | 支持 |
|---|---|
| 当前桌面页 | ✅ |
| 当前页的文件夹里 | ✅ 自动点开文件夹找 |
| 其他桌面页 | ✅ 点页码圆点直接跳过去 |
| 其他页的文件夹 | ✅ |
| 桌面没有页码圆点的机型 | ✅ 退回滑动翻页 |

## 二、桌面索引（可选的加速）

设置页新增「初始化应用查找功能」。

首次定位要一页一页翻、逐个开文件夹，可能几十秒。点「开始」遍历一次，
把每个应用的位置记下来 —— 之后定位**一次 dump 都不用**，直接跳页 + 长按。

- 应用较多时约需几分钟，过程中会显示「正在遍历第 N/M 页」
- **随时可以操作手机打断**，下次点「开始」接着来
- 建好后会显示「上次完成：N 天前」

首次点定位、且还没建索引时，会弹三选一：先初始化（推荐）/ 直接查找 / 取消。

## 三、修复

- **编译时列表刷新，进度条消失**：编译状态从闭包提到全局，每帧按包名重新定位节点，
  重绘后自动补画。刷新多少次都不丢。
- **定位被刷新阻塞**：定位、列表刷新、索引构建原来共用队列，现在定位有独立线程池，
  三者互不干扰。
- **「QQ」被认成「QQ邮箱」**：匹配改成先精确、再宽松，宽松要求后缀以标点开头
  （覆盖「微信，3 条通知」这种带角标的描述）。
- **文件夹关不掉**：原来用 back，在文件夹编辑态下只退出编辑，文件夹还开着，
  下一轮点坐标会点到里面的应用。改用 home 强制回桌面。
- **界面提示关掉后白名单 ⓘ 也没了**：那是查看说明的唯一入口，加 ID 例外。

---

## What's new (English)

**App locator** — tap once and it takes you to the icon, on whichever page or folder it lives.

**Desktop index (optional speed-up)** — the first lookup has to walk every page and open every
folder, which can take tens of seconds. "Build app location index" in Settings records where each
app lives, after which a lookup needs no UI dump at all. Takes a few minutes on a full phone, can be
interrupted any time, and resumes where it left off.

**Fixes** — the compile progress bar no longer disappears when the list refreshes; locating is no
longer blocked by list refreshes or index building (separate worker pool); exact-then-loose name
matching stops "QQ" from matching "QQ邮箱"; folders are closed with Home so the next tap can't land
inside one.
