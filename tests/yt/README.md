# YouTube 线路诊断台

主机侧工具，用来回答一个问题：**一条 YouTube 链接下不了，是软件的问题，还是 YouTube 不给？**

## 1. `probe.py` —— 区分「哪一层挡住了」

```bash
python probe.py "https://youtu.be/g1irska7uL0?si=xxx"
python probe.py g1irska7uL0 fXSle7w6mgY      # 也接受纯 ID，可一次传多条
```

需要本机代理 `127.0.0.1:10808`（脚本里写死，换端口改 `PROXY`）。

它走**网页端**（带 Chrome UA + 跳过同意页的 cookie），读页面里的播放器数据，把结果分成四类：

| 标记 | 含义 | 该怎么办 |
|---|---|---|
| `A 正常` | 拿到 N 条流 | App 里也应该能下；下不了就是 App 的 bug |
| `B 要登录/换IP` | `Sign in to confirm you're not a bot` | **出口 IP 信誉问题**，换节点通常就好 |
| `C 视频级` | 私有 / 已删除 / 地区 / 年龄 | 换什么节点都没用 |
| `D 网络` | 连不上、被导到同意页 | 先查代理是不是全局模式 |

**为什么一定要看网页端，而不只看 App 用的 API：**
- 网页端也被拒 → 拦截在「这个出口 IP」这一层，换节点有意义；
- 只有 API 被拒、网页端正常 → 缺 BotGuard / PO Token，换节点未必有用，得带 cookies。
两者的解法完全不同，所以必须分开测。

## 2. `run.sh` —— App 错误文案的验证台（不联网）

```bash
sh tests/yt/run.sh
```

把 `YtDeny.java`（纯 Java，无 Android 依赖）原样编进桌面 JDK，校验各种 YouTube
拒绝原因是否落到正确的中文提示上。20 项断言。

## 3. 2026-11-09 的结论（存档）

用户给的两条链接 `g1irska7uL0`、`fXSle7w6mgY`：

- 网页端同为 `LOGIN_REQUIRED / Sign in to confirm you're not a bot`；
- 同一时刻、同一出口，公开视频 `dQw4w9WgXcQ` 正常出 **27 条流**；
- 重试 24 次全失败（是稳定拒绝，不是偶发）；带 `visitorData`、`deviceExperimentId`、
  `X-Goog-Api-Format-Version: 2`、`has_verified=1`、embed 页、m.youtube.com 全无效；
- 第三方取流服务（Invidious ×3、Piped）403/401，它们自己也被封。

**结论：不是代码问题，是这两条视频在当前出口要求登录。** 首选换代理节点；
换不动才需要「登录 cookies」。App 内的处理见 `docs/经验与坑.md` 第二十六节。
