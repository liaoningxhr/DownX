# -*- coding: utf-8 -*-
"""主机探针：1:1 复刻 IgFetcher.viaGraphql 的请求，迭代到 200 为止。
用法：python gql.py <shortcode> [--no-warm] [--ua mobile|desktop]
"""
import sys, json, re, gzip, time
import urllib.request, urllib.parse, http.cookiejar

CODE = sys.argv[1] if len(sys.argv) > 1 else "DercauFo80K"
UA_MOBILE = ("Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 "
             "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
UA_DESKTOP = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
              "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")

jar = http.cookiejar.CookieJar()
# 主机的 IG 通道：本机 10808 HTTP 代理（上午验证过的网络策略）
op = urllib.request.build_opener(
    urllib.request.HTTPCookieProcessor(jar),
    urllib.request.ProxyHandler({"http": "http://127.0.0.1:10808",
                                 "https": "http://127.0.0.1:10808"}))
op.addheaders = []

def read(resp):
    raw = resp.read()
    if resp.headers.get("Content-Encoding") == "gzip":
        raw = gzip.decompress(raw)
    return raw.decode("utf-8", "replace")

def warm(ua):
    req = urllib.request.Request("https://www.instagram.com/", headers={
        "User-Agent": ua,
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language": "en-US,en;q=0.9",
        "Accept-Encoding": "gzip",
    })
    r = op.open(req, timeout=25)
    html = read(r)
    m = re.search(r'"LSD",\[\],\{"token":"([^"]+)"', html)
    lsd = m.group(1) if m else ""
    ck = "; ".join("%s=%s" % (c.name, c.value) for c in jar)
    print("[warm] %s len=%d lsd=%s" % (r.status, len(html), lsd or "缺"))
    print("[warm] cookies: %s" % ck[:200])
    return lsd, ck

def gql(lsd, ck, ua, doc_id="8845758582119845", tag=""):
    variables = json.dumps({"shortcode": CODE, "fetch_tagged_user_count": None,
                            "hoisted_comment_id": None, "hoisted_reply_id": None},
                           separators=(",", ":"))
    form = urllib.parse.urlencode({
        "av": "0", "__d": "www", "__user": "0", "__a": "1", "__req": "k",
        "__ccg": "UNKNOWN", "__comet_req": "7", "dpr": "1",
        "lsd": lsd or "AVo0fg_n",
        "jazoest": str(sum(ord(c) for c in (lsd or "AVo0fg_n")) * 2),
        "doc_id": doc_id,
        "variables": variables,
        "server_timestamps": "true",
    })
    headers = {
        "User-Agent": ua,
        "Content-Type": "application/x-www-form-urlencoded",
        "X-FB-Friendly-Name": "PolarisPostActionLoadPostQueryQuery",
        "X-IG-App-ID": "936619743392459",
        "X-FB-LSD": lsd or "AVo0fg_n",
        "X-ASBD-ID": "129477",
        "Origin": "https://www.instagram.com",
        "Sec-Fetch-Site": "same-origin",
        "Sec-Fetch-Mode": "cors",
        "Sec-Fetch-Dest": "empty",
        "Referer": "https://www.instagram.com/p/%s/" % CODE,
        "Accept-Encoding": "gzip",
        "Cookie": ck,
    }
    csrf = ""
    for part in ck.split(";"):
        if part.strip().startswith("csrftoken="):
            csrf = part.strip()[10:]
    if csrf:
        headers["X-CSRFToken"] = csrf
    req = urllib.request.Request("https://www.instagram.com/graphql/query",
                                 data=form.encode(), headers=headers, method="POST")
    t0 = time.time()
    try:
        r = op.open(req, timeout=25)
        body = read(r)
        print("[%s] %s %.1fs len=%d" % (tag or "gql", r.status, time.time() - t0, len(body)))
        print("[%s] head: %s" % (tag or "gql", body[:220].replace("\n", " ")))
        try:
            j = json.loads(body)
            sm = (j.get("data") or {}).get("xdt_shortcode_media")
            if sm:
                vu = sm.get("video_url") or ""
                print("[%s] ✅ 命中! video_url=%s owner=%s" %
                      (tag or "gql", vu[:90], (sm.get("owner") or {}).get("username")))
                return True
            print("[%s] JSON 正常但无 xdt_shortcode_media: %s" % (tag or "gql", body[:160]))
        except Exception as e:
            print("[%s] 非 JSON: %s" % (tag or "gql", e))
    except urllib.error.HTTPError as e:
        b = e.read().decode("utf-8", "replace")
        print("[%s] HTTP %d %.1fs: %s" % (tag or "gql", e.code, time.time() - t0, b[:200].replace("\n", " ")))
    except Exception as e:
        print("[%s] 异常: %s" % (tag or "gql", e))
    return False

if __name__ == "__main__":
    ua = UA_MOBILE
    lsd, ck = warm(ua)
    ok = gql(lsd, ck, ua, tag="v1")
    if not ok:
        print("\n--- 变体2：先等 3 秒换 desktop UA 重warm ---")
        time.sleep(3)
        lsd2, ck2 = warm(UA_DESKTOP)
        ok = gql(lsd2, ck2, UA_DESKTOP, tag="v2")
    if not ok:
        print("\n--- 变体3：不带 Friendly-Name/ASBD，最小头 ---")
        time.sleep(3)
        ok = gql(lsd, ck, ua, tag="v3")
