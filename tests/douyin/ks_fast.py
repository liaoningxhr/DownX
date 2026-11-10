# -*- coding: utf-8 -*-
"""
快手「快路径」主机验证台 —— 对应 ShortVideoScraper.ksFastParse() 的 Java 实现。

验证内容（对齐 CYQawa/YunX 的 KuaishouParser）：
  1. iOS UA + Referer https://v.kuaishou.com/ 能拿到带作品数据的 window.INIT_STATE
     （换安卓/桌面 UA 就是空壳 —— 以前误判"INIT_STATE 只剩空壳"的根因）；
  2. 括号配平提取 JSON → 遍历取第一个 photo 非空的 value；
  3. mainMvUrls[0] 直链可下载（206、mp4 头）；
  4. 双 CDN 镜像（备用线路）可下载；
  5. 图集 atlas 拼链逻辑（有图集的作品才验）。

用法：python tests/douyin/ks_fast.py [链接1] [链接2] ...
不传链接就用内置样例。
"""
import json
import re
import sys
import urllib.request

IOS_UA = ("Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 "
          "(KHTML, like Gecko) Version/17.4 Mobile/15E148 Safari/604.1")
REFERER = "https://v.kuaishou.com/"

SAMPLES = [
    "https://www.kuaishou.com/short-video/3x56qx9f9beqtmm",  # 北体航航（活链接，2026-11-09 验证）
    "https://www.kuaishou.com/short-video/3x297p7fn7nkp84",  # 西北信息报（活链接，2026-11-09 验证）
]

ok = fail = 0

def check(name, cond, detail=""):
    global ok, fail
    mark = "PASS" if cond else "FAIL"
    if cond:
        ok += 1
    else:
        fail += 1
    print("  [%s] %s%s" % (mark, name, (" · " + detail) if detail else ""))


def fetch(url, ua=IOS_UA):
    req = urllib.request.Request(url, headers={
        "User-Agent": ua, "Referer": REFERER,
        "Accept-Language": "zh-CN,zh;q=0.9", "Accept-Encoding": "identity"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return r.status, r.geturl(), r.read().decode("utf-8", "replace")


def extract_init_state(html):
    """与 Java extractInitState 逐行对应：括号配平，区分字符串内外。"""
    i = html.find("window.INIT_STATE")
    if i < 0:
        return None
    j = html.find("{", i)
    if j < 0:
        return None
    in_str = False
    esc = False
    depth = 0
    for k in range(j, len(html)):
        c = html[k]
        if in_str:
            if esc:
                esc = False
            elif c == "\\":
                esc = True
            elif c == '"':
                in_str = False
        elif c == '"':
            in_str = True
        elif c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                return html[j:k + 1]
    return None


def find_photo(init_state):
    js = json.loads(init_state)
    for key in js:
        v = js[key]
        if isinstance(v, dict):
            p = v.get("photo")
            if isinstance(p, dict) and p:
                return p
    return None


def first_cdn_url(arr):
    for e in arr or []:
        u = (e or {}).get("url") or ""
        if u.startswith("http"):
            return u
    return ""


def atlas_urls(atlas):
    out = []
    if not atlas:
        return out
    cdn_list = atlas.get("cdnList") or []
    cdn = (cdn_list[0] or {}).get("cdn") if cdn_list else ""
    for route in atlas.get("list") or []:
        if route:
            out.append("https://" + cdn + "/" + route)
    return out


def download_probe(url, want_mp4=True):
    req = urllib.request.Request(url, headers={"Range": "bytes=0-63"})
    with urllib.request.urlopen(req, timeout=20) as r:
        head = r.read(64)
        cr = r.headers.get("Content-Range", "")
        total = cr.split("/")[-1] if "/" in cr else ""
        return r.status, total, head


def run(link):
    print("=" * 68)
    print(link)
    try:
        s, final, html = fetch(link)
    except Exception as e:
        check("页面请求", False, str(e)[:60])
        return
    check("页面 200", s == 200, "最终地址 " + final[:70])
    check("重定向到作品页", "/fw/photo/" in final or "/fw/long-video/" in final)

    raw = extract_init_state(html)
    check("INIT_STATE 提取", raw is not None)
    if raw is None:
        return
    photo = None
    try:
        photo = find_photo(raw)
    except Exception as e:
        check("JSON 解析", False, str(e)[:60])
    check("photo 节点非空", photo is not None)
    if photo is None:
        return

    caption = photo.get("caption") or ""
    user = (photo.get("userName") or "").replace("\u3164", "").strip()
    check("标题 caption", len(caption) > 3, caption[:30])
    check("作者 userName", len(user) > 0, user[:20])

    mv = photo.get("mainMvUrls") or []
    check("mainMvUrls >=1 条", len(mv) >= 1, "共 %d 条（CDN 镜像）" % len(mv))
    v0 = first_cdn_url(mv)
    check("视频直链 http", v0.startswith("http"), v0[:70])

    st, total, head = download_probe(v0)
    check("主直链 206 + mp4", st == 206 and head[:4] in (b"\x00\x00\x00 ", b"ftyp"),
          "总 %s 字节" % total)
    if len(mv) > 1:
        v1 = first_cdn_url(mv[1:])
        if v1 and v1 != v0:
            st1, total1, _ = download_probe(v1)
            check("备用 CDN 也可下", st1 == 206 and total1 == total, v1[:60])

    atlas = (photo.get("ext_params") or {}).get("atlas")
    imgs = atlas_urls(atlas)
    if imgs:
        st2, _, head2 = download_probe(imgs[0], want_mp4=False)
        check("图集直链可下", st2 == 206, "共 %d 张" % len(imgs))
    else:
        print("  [INFO] 本作品无图集（纯视频），atlas 拼链逻辑由有图集的作品验证")


if __name__ == "__main__":
    links = sys.argv[1:] or SAMPLES
    for lk in links:
        run(lk)
    print("=" * 68)
    print("结果: %d PASS, %d FAIL" % (ok, fail))
    sys.exit(1 if fail else 0)
