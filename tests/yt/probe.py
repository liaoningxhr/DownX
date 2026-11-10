# -*- coding: utf-8 -*-
"""
YouTube 下载失败诊断器（主机侧，需要代理）

回答一个问题：这条视频下不了，到底是哪一层挡住的？
  A. 正常            —— 能拿到流，App 里也该能下
  B. 要登录 / 换 IP   —— "Sign in to confirm you're not a bot"（IP 信誉问题，换节点常能解）
  C. 视频级限制       —— 私有 / 已删除 / 地区限制 / 年龄限制（换什么节点都没用）
  D. 网络            —— 代理没通、超时

用法：
    python probe.py https://youtu.be/g1irska7uL0?si=xxx
    python probe.py g1irska7uL0 fXSle7w6mgY dQw4w9WgXcQ

为什么用网页端而不是 InnerTube API：
    网页端带上浏览器特征仍被拒，说明拦截发生在"这个出口 IP"这一层（换节点可能就好）；
    如果只有 API 被拒、网页端正常，说明是缺 BotGuard/PO Token（换节点未必有用）。
    两者的处理方式完全不同，所以必须分开看。
"""
import json
import re
import sys
import urllib.request

PROXY = "http://127.0.0.1:10808"
OP = urllib.request.build_opener(
    urllib.request.ProxyHandler({"http": PROXY, "https": PROXY}))
UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
HDRS = {
    "User-Agent": UA,
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "Accept-Language": "en-US,en;q=0.9",
    "Cookie": "CONSENT=YES+cb; SOCS=CAI",       # 跳过欧盟同意页
}

P_ID = re.compile(r"(?:youtu\.be/|/shorts/|/embed/|[?&]v=)([\w-]{11})")


def video_id(s):
    """网址 / 纯 ID 都接受（口令里带一堆参数也能抓出来）"""
    if re.fullmatch(r"[\w-]{11}", s):
        return s
    m = P_ID.search(s)
    return m.group(1) if m else None


def player_response(html):
    m = re.search(r"ytInitialPlayerResponse\s*=\s*\{", html)
    if not m:
        return None
    start = html.index("{", m.start())
    try:
        return json.JSONDecoder().raw_decode(html[start:])[0]
    except Exception:
        return None


def text_of(node):
    """YouTube 的文案字段有三种形态：纯字符串 / simpleText / runs[]
    （playabilityStatus.reason 在网页端就是纯字符串，漏了这一种会打印出空白）"""
    if isinstance(node, str):
        return node
    if not isinstance(node, dict):
        return ""
    if node.get("simpleText"):
        return node["simpleText"]
    return "".join(x.get("text", "") for x in (node.get("runs") or []))


def check(vid):
    url = "https://www.youtube.com/watch?v=%s&bpctr=9999999999" % vid
    try:
        r = OP.open(urllib.request.Request(url, headers=HDRS), timeout=30)
        html = r.read().decode("utf-8", "replace")
    except Exception as e:
        print("  %-12s [D 网络] %r" % (vid, repr(e)[:70]))
        return "NET"

    if "consent.youtube.com" in html:
        print("  %-12s [D 网络] 被导到同意页（代理出口在欧盟、或 cookie 没生效）" % vid)
        return "NET"

    pr = player_response(html)
    if pr is None:
        print("  %-12s [D 网络] HTTP %s 但没有播放器数据（%d KB）"
              % (vid, r.status, len(html) // 1024))
        return "NET"

    ps = pr.get("playabilityStatus") or {}
    st = ps.get("status") or ""
    reason = text_of(ps.get("reason"))
    sub = text_of(ps.get("subreason"))
    sd = pr.get("streamingData") or {}
    n = len(sd.get("formats") or []) + len(sd.get("adaptiveFormats") or [])
    title = (pr.get("videoDetails") or {}).get("title") or ""
    if not title:
        mf = (pr.get("microformat") or {}).get("playerMicroformatRenderer") or {}
        title = mf.get("title") or ""

    if st == "OK" and n > 0:
        print("  %-12s [A 正常] %d 条流  %s" % (vid, n, title[:30]))
        return "OK"

    # 机器人验证 / 要求登录：注意它也会带 errorScreen，不能据此判成"视频级限制"
    if st == "LOGIN_REQUIRED" or re.search(
            r"not a bot|sign in|confirm your age", (reason + " " + sub), re.I):
        if re.search(r"age", reason + sub, re.I):
            print("  %-12s [C 视频级] 年龄限制：%s" % (vid, (reason or sub)[:60]))
            return "DENY"
        print("  %-12s [B 要登录/换IP] %s %s" % (vid, reason[:50], title[:24]))
        return "LOGIN"

    print("  %-12s [C 视频级] status=%s %s" % (vid, st, (reason or sub)[:60]))
    return "DENY"


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    print("代理：%s\n" % PROXY)
    tally = {}
    for arg in sys.argv[1:]:
        v = video_id(arg)
        if not v:
            print("  %-12s 不是 YouTube 链接/ID，跳过" % arg[:12])
            continue
        tag = check(v)
        tally[tag] = tally.get(tag, 0) + 1

    print("\n—— 结论 ——")
    if tally.get("OK"):
        print("  能拿到流：%d 条 —— 这些在 App 里也应该能下。" % tally["OK"])
    if tally.get("LOGIN"):
        print("  %d 条要求登录/被判定为机器人：" % tally["LOGIN"])
        print("    这是出口 IP 的信誉问题。先在代理里换节点，再用手机浏览器打开链接；")
        print("    浏览器不弹登录、能直接播，就说明这个节点可用，回 App 重试即可。")
    if tally.get("DENY"):
        print("  %d 条是视频本身的限制（私有/地区/年龄）：换节点也没用。" % tally["DENY"])
    if tally.get("NET"):
        print("  %d 条根本没连上：先确认代理是全局模式、端口对不对。" % tally["NET"])
