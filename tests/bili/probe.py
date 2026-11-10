# -*- coding: utf-8 -*-
"""B 站 403 复现台：App 同源 UA / 同 playurl 参数，逐项加头定位 403。
用法: sh tests/bili/run.sh   （或 python tests/bili/probe.py [BV号]）
"""
import json
import sys
import urllib.request
import urllib.error
import uuid

APP_UA = ("Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 "
          "(KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36")
BUVID = uuid.uuid4().hex.upper() + "infoc"   # B 站接口对无 buvid 的请求会间歇性 -400
BV = sys.argv[1] if len(sys.argv) > 1 else "BV1GJ411x7h7"


def get(url, headers=None, timeout=18):
    req = urllib.request.Request(url)
    if headers and "Cookie" not in headers:
        req.add_header("Cookie", "buvid3=" + BUVID)
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read().decode("utf-8", "replace")


def probe(url, ua=None, referer=None, range_req=True):
    """返回 (状态码, 响应体摘要)。头顺序与 DlService.open 一致。"""
    req = urllib.request.Request(url)
    if ua:
        req.add_header("User-Agent", ua)
    req.add_header("Accept", "*/*")
    if referer:
        req.add_header("Referer", referer)
    if range_req:
        req.add_header("Range", "bytes=0-65535")
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            got = 0
            while len(r.read(8192)) > 0 and got < 65536:
                got += 1
            return r.status, ""
    except urllib.error.HTTPError as e:
        body = ""
        try:
            body = e.read().decode("utf-8", "replace").strip()[:160]
        except Exception:
            pass
        return e.code, body
    except Exception as e:
        return -1, str(e)[:160]


def abbrev(s, n=120):
    return s[:n] + "…" if s and len(s) > n else s


def main():
    view = json.loads(get("https://api.bilibili.com/x/web-interface/view?bvid=" + BV,
                          {"User-Agent": APP_UA, "Referer": "https://www.bilibili.com/"}))
    if view.get("code") != 0:
        print("FAIL view:", view.get("message"), "| raw:", str(view)[:150])
        return 1
    d = view["data"]
    bvid, cid, title = d["bvid"], d["cid"], d["title"]
    print("视频:", title, " bvid=%s cid=%s" % (bvid, cid))

    # html5 链路（App addPlay 同款参数，未登录）
    pu = json.loads(get("https://api.bilibili.com/x/player/playurl?bvid=%s&cid=%s"
                        "&qn=64&fnval=0&fourk=0&platform=html5&high_quality=1"
                        % (bvid, cid),
                        {"User-Agent": APP_UA,
                         "Referer": "https://www.bilibili.com/video/" + bvid}))
    if pu.get("code") != 0:
        print("FAIL playurl:", pu.get("message"))
        return 1
    one = pu["data"]["durl"][0]
    url = one["url"]
    print("直链:", abbrev(url))
    print("标称大小:", one.get("size"), "字节")
    print()

    matrix = [
        ("无任何头", None, None),
        ("只 UA", APP_UA, None),
        ("只 Referer", None, "https://www.bilibili.com/"),
        ("UA+Referer(App现状)", APP_UA, "https://www.bilibili.com/"),
        ("UA+Referer 视频页", APP_UA, "https://www.bilibili.com/video/" + bvid),
    ]
    for name, ua, ref in matrix:
        code, body = probe(url, ua, ref, True)
        print("  %-24s Range -> HTTP %d %s" % (name, code, body))
    code0, _ = probe(url, APP_UA, "https://www.bilibili.com/", False)
    print("  %-24s 无Range -> HTTP %d" % ("UA+Referer 无Range", code0))
    # CDN 上同一 URL 再来几次，看 403 是不是间歇性的
    codes = []
    for _ in range(3):
        c, _b = probe(url, APP_UA, "https://www.bilibili.com/", True)
        codes.append(c)
    print("  连续 3 次重复探测 → %s" % codes)

    # dash 接口（未登录，看返回什么）
    try:
        nav = get("https://api.bilibili.com/x/web-interface/nav",
                  {"User-Agent": APP_UA, "Referer": "https://www.bilibili.com/"})
        print("\nnav ok (wbi key 可取)")
    except Exception as e:
        print("\nnav 失败:", e)

    # 登录链路没法在主机复现（没有 Cookie），先把 dash 接口无 Cookie 的返回打出来
    try:
        resp = get("https://api.bilibili.com/x/player/playurl?bvid=%s&cid=%s&qn=80&fnval=4048"
                   "&fourk=1&platform=pc" % (bvid, cid),
                   {"User-Agent": APP_UA, "Referer": "https://www.bilibili.com/video/" + bvid})
        o = json.loads(resp)
        print("dash 无登录 code=%s message=%s" % (o.get("code"), o.get("message")))
        dd = o.get("data") or {}
        dash = dd.get("dash")
        print("  dash=%s" % ("有" if dash else "无"), " durl=%s" % ("有" if dd.get("durl") else "无"))
        if dash:
            v0 = dash["video"][0]
            print("  第一路: qn=%s codec=%s" % (v0.get("id"), v0.get("codecs")))
            b0 = v0.get("baseUrl") or v0.get("base_url") or ""
            print("  dashURL:", abbrev(b0))
            code, body = probe(b0, APP_UA, "https://www.bilibili.com/", True)
            print("  dash Range 探测 -> HTTP %d %s" % (code, body))
    except Exception as e:
        print("dash 试探失败:", e)
    return 0


if __name__ == "__main__":
    sys.exit(main())
