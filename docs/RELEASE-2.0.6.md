# Mine App 2.0.6

> 改一句文案，没动功能。

---

## 「等待手机授权」→「刷新中，请稍后」

刷新时如果超过 4 秒还没拿到数据，摘要行原本会显示：

> 等待手机授权…

这句有歧义——它其实是**两种完全不同的情况**共用了一句话：

1. 首次连接，手机上确实弹了授权框，在等你点
2. 连接正常，只是这次数据回来得慢

第二种情况占绝大多数，但提示让人以为手机上有东西要点，会去找一个并不存在的弹窗。

改成：

> 刷新中，请稍后

只描述**正在发生什么**，不猜原因。真需要授权时，手机上的弹窗自己会跳出来，不用文字提示。

英文同步改成 `Refreshing, please wait…`。

---

## What's new (English)

- The refresh status used to read "Waiting for phone authorization…", which was ambiguous: it
  covered both "the phone is showing an authorization prompt" and "the data is just slow".
  It now simply says **"Refreshing, please wait…"** — describing what's happening instead of
  guessing why.
