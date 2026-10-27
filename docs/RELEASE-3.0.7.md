# Mine App 3.0.7

> 两个修复：进度条终于会动了；电池说明与「查看」链接合成一句。

---

## 一、🔴 进度条永远停在 0%

3.0.6 加了进度条，但实测它从头到尾都是 0%，然后 App 就重启了。

原因不在进度条本身，在**线程**：

```java
private final ExecutorService worker = Executors.newSingleThreadExecutor();
```

`installupdate` 会独占这个单线程池跑完整个下载 + 安装（含 1 秒满格停留），
而前端每 300ms 调一次的 `installprogress` 也排在同一个队列里 ——

```
worker 线程：[installupdate 下载+安装 ...........]
              [progress][progress][progress]   ← 全堵在后面，永远轮不到
```

等它们被调度时，App 早就被安装过程重启了。所以不是「满格停留没生效」，
而是**进度查询一次都没执行过**，中间 1%~99% 自然也不会显示。

修法：进度查询不进队列，只读几个 `volatile` 字段，直接在主线程回调。

```java
@JavascriptInterface
public void installprogress(int id, String arg) {
    o.put("phase", instPhase);   // volatile
    o.put("pct", instPct);       // volatile
    main.post(() -> web.evaluateJavascript("window.__bridgeResult(...)", null));
}
```

**通用教训**：单线程 executor 里跑长任务时，同队列的任何「查询类」接口都会饿死。
状态查询要么绕开队列，要么换独立线程。

## 二、电池说明与链接合成一句

原来是两行：一行说明、一行「去关于本机页查看 →」。

现在合成一句，「查看」是内联链接：

> 部分机型可能不支持自动读取容量，可**查看**后手动输入。

点击「查看」直接跳系统的「关于本机」页。

实现上把 `<a>` 内联进了 i18n 字符串（走 `data-html` 渲染），
点击改为事件委托 —— 语言切换会重绘节点，直接绑的监听会丢。

---

## What's new (English)

- **Fixed the progress bar stuck at 0%.** `installupdate` occupies the single-thread executor for the
  whole download + install, and the progress queries were queued behind it — so they never ran, and
  neither the intermediate percentages nor the full bar were ever visible. Progress queries now read
  `volatile` fields and post straight to the main thread, bypassing the queue.
- The battery hint and its "check here" link are now one sentence, with the link inline.
