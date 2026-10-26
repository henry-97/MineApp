# Mine App 2.0.7

> 加一段说明，回答一个很自然的顾虑：开性能模式会不会更费电。

---

## 顾虑

「性能模式」这个名字听起来就是个费电开关。所以很多人不敢开，宁可留在「均衡」档，结果手机又卡、续航也没见好。

## 实际情况

**性能模式不费电。** 它只是**解除 CPU 的峰值主频上限**，并不会让 CPU 一直满血跑。

CPU 是按需调度的：前台任务几秒干完就回落到低频。比被压着主频慢慢磨更省——同样的活，跑得快反而更早休息。

真正吃掉待机电量的，是**后台应用偷偷唤醒、联网、刷新**，不是峰值主频。

## 那「均衡模式」为什么不好

它压主频，但压不住后台：

- 前台同样的事要跑更久（卡顿来自这里）
- 后台该醒还是醒（耗电来自这里）

两头不讨好，结果往往是**又卡又费电**。

## 推荐组合

| 动作 | 作用 |
|---|---|
| 打开**性能模式** | 放开主频上限，前台跑完就退，不拖时间 |
| **限制**用不着的应用 | 让后台真正睡下去，砍掉唤醒 |

**前台快、后台静，两头都省。**

这段说明现在放在「性能调优」页面里（开关组下方），README 也同步更新了。

---

## What's new (English)

- The performance-tuning sheet now explains **why "Performance" mode does not drain your battery**:
  it only lifts the CPU's peak-frequency cap rather than pinning the CPU at full tilt, so
  foreground work finishes sooner and clocks drop back down.
- The real standby drain is background apps waking and refreshing — which is what the
  *Restrict* action is for. Capping the CPU while leaving the background alone just gives you
  **slower and just as thirsty**.
