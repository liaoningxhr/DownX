# -*- coding: utf-8 -*-
"""SOCKS5(127.0.0.1:10808) 手工隧道 + 原生 ssl，零依赖访问 IG。
用法：python sock5.py [path] [method] [body]"""
import socket, ssl, sys, gzip, time

PROXY = ("127.0.0.1", 10808)
UA = ("Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")

def socks_connect(host, port=443):
    s = socket.create_connection(PROXY, timeout=15)
    s.sendall(b"\x05\x01\x00")                       # 无认证
    if s.recv(2) != b"\x05\x00":
        raise RuntimeError("socks 握手失败")
    addr = host.encode()
    s.sendall(b"\x05\x01\x00\x03" + bytes([len(addr)]) + addr + (port).to_bytes(2, "big"))
    r = s.recv(10)
    if r[1] != 0:
        raise RuntimeError("socks 连接被拒 code=%d" % r[1])
    return s

def request(host, path, headers="", method="GET", body=None):
    s = socks_connect(host)
    ctx = ssl.create_default_context()
    ss = ctx.wrap_socket(s, server_hostname=host)
    req = "%s %s HTTP/1.1\r\nHost: %s\r\nUser-Agent: %s\r\nAccept-Encoding: gzip\r\nConnection: close\r\n" % (
        method, path, host, UA)
    if headers:
        req += headers + "\r\n"
    if body is not None:
        req += "Content-Length: %d\r\n" % len(body)
    req += "\r\n"
    ss.sendall(req.encode() + (body.encode() if body else b""))
    buf = b""
    t0 = time.time()
    while time.time() - t0 < 25:
        try:
            d = ss.recv(65536)
        except Exception:
            break
        if not d:
            break
        buf += d
    ss.close()
    head, _, payload = buf.partition(b"\r\n\r\n")
    hl = head.decode("latin1")
    status = int(hl.split()[1]) if " " in hl else 0
    if "gzip" in hl.lower():
        try: payload = gzip.decompress(payload)
        except Exception: pass
    return status, hl, payload.decode("utf-8", "replace")

if __name__ == "__main__":
    path = sys.argv[1] if len(sys.argv) > 1 else "/"
    method = sys.argv[2] if len(sys.argv) > 2 else "GET"
    body = sys.argv[3] if len(sys.argv) > 3 else None
    st, hl, body = request("www.instagram.com", path, method=method, body=body)
    print("status=%d 总字节=%d" % (st, len(body)))
    print("头: %s" % hl.replace("\r\n", " | ")[:300])
    print("体: %s" % body[:300].replace("\n", " "))
