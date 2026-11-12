## Mine App 5.0.4 · 双版本更新

### 双版本共同改动

**列表和弹窗加了可见的滚动条**

Android WebView 默认的滚动条几乎看不见（只在拖动瞬间闪一下），长列表容易让人以为「已经到底了」。现在主列表和弹窗都给滚动条画了明确样式：

```css
::-webkit-scrollbar        { width: 3px }
::-webkit-scrollbar-thumb  { background: var(--ink3); border-radius: 2px }
::-webkit-scrollbar-thumb:active { background: var(--ink2) }
```

- **宽度 3px** —— 够看见，又不占内容空间
- **默认用次要文字色**，按住拖动时加深
- 主列表（`#list`）和弹窗内容区（`.sheet-body`，操作历史/设置页共用）都生效

### 经典版

本版无专属改动。

### 多通道版

本版无专属改动。

---

### 下载哪个

| 文件 | 包名 | 说明 |
|---|---|---|
| `MineApp-5.0.4.apk` | `com.henry.appmanager` | 经典版（仅 ADB 通道） |
| `MineApp-5.0.4-multi.apk` | `com.henry.appmanager.multi` | 多通道版（ADB / Root / Shizuku） |

两个包可以同时安装，互不影响。升级时请下载与当前已装版本对应的那一个，包名不同无法互相覆盖。
