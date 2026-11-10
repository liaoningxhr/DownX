# 第二轮：确认匿名 cookie 能否拿到 + 试 IG 匿名 GraphQL（服务端解析常用路）
import sys, json, gzip, urllib.request, urllib.error, re, http.cookiejar

UA_M = ("Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) "
        "Chrome/124.0.0.0 Mobile Safari/537.36")
UA_D = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
        "Chrome/124.0.0.0 Safari/537.36")
APP_ID = "936619743392459"
ALPHA = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
DOC_ID = "8845758582119845"      # PolarisPostActionLoadPostQueryQuery


def sid(code):
    n = 0
    for ch in code:
        n = n * 64 + ALPHA.index(ch)
    return str(n)


def raw(url, headers=None, data=None, timeout=20):
    h = {"User-Agent": UA_M, "Accept": "*/*", "Accept-Language": "en-US,en;q=0.9"}
    if headers:
        h.update(headers)
    req = urllib.request.Request(url, data=data, headers=h)
    try:
        r = urllib.request.urlopen(req, timeout=timeout)
        code, body, hdrs = r.status, r.read(), r.headers
    except urllib.error.HTTPError as e:
        code, body, hdrs = e.code, e.read(), e.headers
    except Exception as e:
        return -1, "", {}, str(e)
    if hdrs.get("Content-Encoding") == "gzip":
        try:
            body = gzip.decompress(body)
        except Exception:
            pass
    return code, body.decode("utf-8", "replace"), hdrs, ""


def setcookies(hdrs):
    out = []
    if hasattr(hdrs, "get_all"):
        out = hdrs.get_all("Set-Cookie") or []
    return out


def run(code):
    print("== %s" % code)
    st, home, hd, err = raw("https://www.instagram.com/")
    sc = setcookies(hd)
    print("[home] %s len=%d set-cookie=%d" % (st, len(home), len(sc)))
    for s in sc[:8]:
        print("        " + s.split(";")[0])
    ck = "; ".join(s.split(";")[0] for s in sc)
    lsd = re.search(r'"LSD",\[\],\{"token":"([^"]+)"', home)
    lsd = lsd.group(1) if lsd else ""
    print("        lsd=%s" % (lsd or "(none)"))

    # 1) 带 cookie 的 embed（移动 UA）
    st, b, hd, err = raw("https://www.instagram.com/reel/%s/embed/captioned/" % code,
                         {"Cookie": ck, "Referer": "https://www.instagram.com/"})
    print("[embed+ck] %s len=%d video_url=%s og:video=%s" % (
        st, len(b), '"video_url"' in b, 'property="og:video"' in b))

    # 2) 带 cookie 的 embed（桌面 UA）
    st, b, hd, err = raw("https://www.instagram.com/p/%s/embed/" % code,
                         {"Cookie": ck, "User-Agent": UA_D, "Referer": "https://www.instagram.com/"})
    print("[embed-desk] %s len=%d video_url=%s og:video=%s" % (
        st, len(b), '"video_url"' in b, 'property="og:video"' in b))

    # 3) 匿名 GraphQL
    variables = json.dumps({"shortcode": code, "fetch_comment_count": 0,
                            "fetch_related_profile_media_count": 0,
                            "parent_comment_count": 0, "child_comment_count": 0,
                            "fetch_like_count": 0, "has_threaded_comments": False},
                           separators=(",", ":"))
    body = ("av=0&__d=www&__user=0&__a=1&__req=1&dpr=1"
            "&fb_api_caller_class=RelayModern"
            "&fb_api_req_friendly_name=PolarisPostActionLoadPostQueryQuery"
            "&variables=" + urllib.parse.quote(variables) +
            "&doc_id=" + DOC_ID + "&server_timestamps=true").encode()
    hdr = {"Content-Type": "application/x-www-form-urlencoded",
           "x-ig-app-id": APP_ID, "x-fb-lsd": lsd, "x-asbd-id": "129477",
           "x-ig-www-claim": "0", "Origin": "https://www.instagram.com",
           "Referer": "https://www.instagram.com/p/%s/" % code,
           "Cookie": ck, "X-FB-Friendly-Name": "PolarisPostActionLoadPostQueryQuery"}
    st, b, hd, err = raw("https://www.instagram.com/api/graphql", hdr, body)
    print("[graphql] %s len=%d err=%s head=%s" % (st, len(b), err, b[:160].replace("\n", " ")))
    if '"video_url"' in b or '"video_versions"' in b:
        m = re.search(r'"video_url":"([^"]{0,80})', b)
        print("        HIT video_url=" + (m.group(1) if m else "?"))

    # 4) i.instagram.com 的 oembed（JSON）
    st, b, hd, err = raw("https://i.instagram.com/api/v1/oembed/?url=https://www.instagram.com/p/%s/" % code,
                         {"x-ig-app-id": APP_ID})
    print("[i-oembed] %s len=%d head=%s" % (st, len(b), b[:140].replace("\n", " ")))

    # 5) /p/{code}/ 带桌面 UA + cookie，看是否有 shortcode_media
    st, b, hd, err = raw("https://www.instagram.com/p/%s/" % code,
                         {"User-Agent": UA_D, "Cookie": ck})
    print("[post-desk] %s len=%d keys=%s" % (st, len(b),
          [k for k in ("shortcode_media", '"video_url"', "login", "require_login") if k in b]))


if __name__ == "__main__":
    import urllib.parse
    for c in (sys.argv[1:] or ["C8g7WIZM6Z"]):
        run(c)
        print()
