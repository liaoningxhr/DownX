# 匿名通道探测：看每条路在没登录态时到底返回什么
import sys, json, gzip, io, urllib.request, urllib.error, re, http.cookiejar

UA_M = ("Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) "
        "Chrome/124.0.0.0 Mobile Safari/537.36")
UA_D = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
        "Chrome/124.0.0.0 Safari/537.36")
APP_ID = "936619743392459"
ALPHA = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"


def sid(code):
    n = 0
    for ch in code:
        n = n * 64 + ALPHA.index(ch)
    return str(n)


def get(url, headers=None, timeout=20, jar=None):
    h = {"User-Agent": UA_M, "Accept": "*/*", "Accept-Language": "en-US,en;q=0.9",
         "Accept-Encoding": "gzip"}
    if headers:
        h.update(headers)
    req = urllib.request.Request(url, headers=h)
    opener = urllib.request.build_opener(
        urllib.request.HTTPCookieProcessor(jar) if jar else urllib.request.HTTPHandler())
    try:
        r = opener.open(req, timeout=timeout)
        code, raw, hdrs = r.status, r.read(), r.headers
    except urllib.error.HTTPError as e:
        code, raw, hdrs = e.code, e.read(), e.headers
    except Exception as e:
        return -1, "", {}, str(e)
    if hdrs.get("Content-Encoding") == "gzip":
        try:
            raw = gzip.decompress(raw)
        except Exception:
            pass
    return code, raw.decode("utf-8", "replace"), dict(hdrs), ""


def has(body, *keys):
    return [k for k in keys if k in body]


def run(code):
    print("== shortcode %s  media_id=%s" % (code, sid(code)))
    jar = http.cookiejar.CookieJar()

    # 0) 预热首页拿匿名 cookie
    st, body, hd, err = get("https://www.instagram.com/", jar=jar)
    ck = "; ".join("%s=%s" % (c.name, c.value) for c in jar)
    print("[warm] %s len=%d err=%s cookies=%s" % (st, len(body), err, ck[:160]))

    # 1) embed 页
    st, body, hd, err = get("https://www.instagram.com/reel/%s/embed/captioned/" % code,
                            {"Referer": "https://www.instagram.com/"}, jar=jar)
    print("[embed] %s len=%d err=%s keys=%s" % (st, len(body), err,
          has(body, "video_url", "og:video", "display_url", "<video", "loginForm", "LoginAndSignupPage")))
    m = re.search(r'"video_url"\s*:\s*"([^"]{0,120})', body)
    if m:
        print("        video_url=" + m.group(1)[:110].replace("\\u0026", "&"))

    # 2) web api
    st, body, hd, err = get("https://www.instagram.com/api/v1/media/%s/info/" % sid(code),
                            {"x-ig-app-id": APP_ID, "x-ig-www-claim": "0",
                             "x-requested-with": "XMLHttpRequest",
                             "Referer": "https://www.instagram.com/p/%s/" % code,
                             "Cookie": ck}, jar=jar)
    print("[webapi] %s len=%d err=%s head=%s" % (st, len(body), err, body[:100].replace("\n", " ")))

    # 3) app api
    st, body, hd, err = get("https://i.instagram.com/api/v1/media/%s/info/" % sid(code),
                            {"x-ig-app-id": "567067343352427", "x-ig-www-claim": "0",
                             "Cookie": ck}, jar=jar)
    print("[appapi] %s len=%d err=%s head=%s" % (st, len(body), err, body[:100].replace("\n", " ")))

    # 4) ?__a=1
    st, body, hd, err = get("https://www.instagram.com/p/%s/?__a=1&__d=dis" % code,
                            {"x-ig-app-id": APP_ID, "Cookie": ck}, jar=jar)
    print("[__a=1] %s len=%d err=%s head=%s" % (st, len(body), err, body[:90].replace("\n", " ")))

    # 5) 官方 oembed（公开 API）
    st, body, hd, err = get("https://api.instagram.com/oembed/?url=https://www.instagram.com/p/%s/" % code)
    print("[oembed] %s len=%d err=%s head=%s" % (st, len(body), err, body[:120]))

    # 6) 镜像
    for u in ["https://ddinstagram.com/p/%s" % code,
              "https://www.kkinstagram.com/p/%s" % code,
              "https://instagramez.com/p/%s" % code]:
        st, body, hd, err = get(u, {"User-Agent": UA_D}, timeout=15)
        print("[mirror %s] %s len=%d err=%s og=%s" % (u.split("/")[2], st, len(body), err,
              has(body, 'property="og:video"', "video_url")))

    # 7) 第三方解析服务（看是否服务端匿名可行）
    st, body, hd, err = get("https://www.instagram.com/p/%s/" % code, {"User-Agent": UA_D}, jar=jar)
    print("[post html] %s len=%d keys=%s" % (st, len(body),
          has(body, "video_url", "shortcode_media", "login_required", "require_login")))


if __name__ == "__main__":
    for c in (sys.argv[1:] or ["C8g7WIZM6Z"]):
        run(c)
        print()
