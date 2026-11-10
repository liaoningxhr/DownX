#!/usr/bin/env python3
"""极简 GitHub REST 客户端（只用标准库，走系统 HTTP(S)_PROXY）。

用法:
  python gh.py whoami
  python gh.py create <repo> [private|public]
  python gh.py upload <owner/repo> [branch]   # 把本地 HEAD 的整棵树同步上去
  python gh.py verify <owner/repo> [branch]   # 逐文件核对远端树与本地 HEAD
  python gh.py count  <owner/repo> [branch]

踩过的坑（别改回去）:
  1. 树条目的 content 只按 UTF-8 解释，塞 base64 会把文件写成 base64 文本。
     正确做法：先 POST /git/blobs（显式 encoding=base64），树里只引用 blob 的 sha。
  2. 内容要从 git 对象库读（git cat-file HEAD），不要读工作区——工作区是 CRLF、
     仓库里是 LF，读工作区会有 28 个文件哈希对不上。
  3. 空仓库不允许 POST /git/trees（409），先 PUT /contents/.downx-init 造占位提交。
"""
import base64
import concurrent.futures
import json
import os
import subprocess
import sys
import urllib.error
import urllib.request

API = "https://api.github.com"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TOKEN_FILE = os.path.join(ROOT, "keys", ".github-token")


def token():
    with open(TOKEN_FILE, "r", encoding="utf-8-sig") as f:
        return f.read().strip()


def call(method, path, payload=None):
    url = path if path.startswith("http") else API + path
    data = None
    headers = {
        "Authorization": "Bearer " + token(),
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
        "User-Agent": "DownX-uploader",
    }
    if payload is not None:
        data = json.dumps(payload).encode("utf-8")
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=180) as r:
            return r.status, json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")


def die(status, body):
    print("HTTP", status)
    print(body if isinstance(body, str) else json.dumps(body, ensure_ascii=False)[:2000])
    sys.exit(1)


# ---------- 本地 ----------

def head_items():
    """[(path, mode, sha)]，取自 HEAD（不是工作区）。"""
    out = subprocess.check_output(["git", "-C", ROOT, "ls-tree", "-r", "-z", "HEAD"])
    items = []
    for rec in out.decode("utf-8").split("\0"):
        if not rec:
            continue
        meta, path = rec.split("\t", 1)
        mode, typ, sha = meta.split()
        if typ == "blob":
            items.append((path, mode, sha))
    return items


def read_blobs(shas):
    """用 git cat-file --batch 一次性读出所有 blob 字节。"""
    p = subprocess.Popen(["git", "-C", ROOT, "cat-file", "--batch"],
                         stdin=subprocess.PIPE, stdout=subprocess.PIPE)
    out, _ = p.communicate(("\n".join(shas) + "\n").encode())
    res, pos = {}, 0
    for sha in shas:
        nl = out.index(b"\n", pos)
        header = out[pos:nl].decode().split()
        if len(header) < 3 or header[1] == "missing":
            raise SystemExit("读不到对象: " + sha)
        size = int(header[2])
        start = nl + 1
        res[sha] = out[start:start + size]
        pos = start + size + 1
    return res


# ---------- 远端 ----------

def whoami():
    s, b = call("GET", "/user")
    if s != 200:
        die(s, b)
    print("login :", b["login"], " id:", b["id"])
    s2, repos = call("GET", "/user/repos?per_page=100&sort=updated")
    if s2 == 200:
        print("仓库数:", len(repos))
        for r in repos[:10]:
            print("  -", r["full_name"], "private=" + str(r["private"]))


def create(repo, private=True):
    s, me = call("GET", "/user")
    if s != 200:
        die(s, me)
    s, b = call("POST", "/user/repos", {
        "name": repo,
        "private": bool(private),
        "description": "DownX - Android 多平台视频/图片下载器",
        "has_issues": True,
        "has_wiki": False,
        "auto_init": False,
    })
    if s not in (200, 201):
        die(s, b)
    print("created:", b["full_name"], "private=" + str(b["private"]))


def remote_tree(repo_full, branch):
    s, t = call("GET", f"/repos/{repo_full}/git/trees/{branch}?recursive=1")
    if s != 200:
        die(s, t)
    return {e["path"]: (e["sha"], e.get("mode")) for e in t["tree"] if e["type"] == "blob"}


def upload(repo_full, branch="main"):
    items = head_items()
    blobs = read_blobs(sorted({sha for _, _, sha in items}))
    total = sum(len(blobs[sha]) for _, _, sha in items)
    print("文件 %d 个，共 %.2f MB（读自 HEAD）" % (len(items), total / 1048576.0))

    def make_blob(item):
        path, mode, sha = item
        s, b = call("POST", f"/repos/{repo_full}/git/blobs",
                    {"content": base64.b64encode(blobs[sha]).decode("ascii"), "encoding": "base64"})
        if s not in (200, 201):
            raise SystemExit(f"blob 失败 {path}: HTTP {s} {b}")
        return {"path": path, "mode": mode, "type": "blob", "sha": b["sha"]}

    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
        entries = list(pool.map(make_blob, items))
    print("blob 建好:", len(entries))

    s, tree = call("POST", f"/repos/{repo_full}/git/trees", {"tree": entries})
    if s == 409 and "empty" in str(tree).lower():
        print("仓库为空，先建立占位提交…")
        s2, b2 = call("PUT", f"/repos/{repo_full}/contents/.downx-init",
                      {"message": "init", "content": base64.b64encode(b"downx\n").decode()})
        if s2 not in (200, 201):
            die(s2, b2)
        s, tree = call("POST", f"/repos/{repo_full}/git/trees", {"tree": entries})
    if s not in (200, 201):
        die(s, tree)
    print("tree:", tree["sha"])

    msg = subprocess.check_output(["git", "-C", ROOT, "log", "-1", "--pretty=%s"]).decode("utf-8").strip()
    local_commit = subprocess.check_output(["git", "-C", ROOT, "rev-parse", "HEAD"]).decode().strip()
    s, commit = call("POST", f"/repos/{repo_full}/git/commits",
                     {"message": msg or "DownX", "tree": tree["sha"]})
    if s not in (200, 201):
        die(s, commit)
    print("commit:", commit["sha"])

    s, ref = call("GET", f"/repos/{repo_full}/git/refs/heads/{branch}")
    if s == 404:
        s, r2 = call("POST", f"/repos/{repo_full}/git/refs",
                     {"ref": f"refs/heads/{branch}", "sha": commit["sha"]})
        if s not in (200, 201):
            die(s, r2)
        print("branch created:", branch)
    elif s == 200:
        s, r2 = call("PATCH", f"/repos/{repo_full}/git/refs/heads/{branch}",
                     {"sha": commit["sha"], "force": True})
        if s not in (200, 201):
            die(s, r2)
        print("branch updated:", branch)
    else:
        die(s, ref)

    call("PATCH", f"/repos/{repo_full}", {"default_branch": branch})
    print("本地 HEAD:", local_commit[:10])
    verify(repo_full, branch)


def verify(repo_full, branch="main"):
    items = head_items()
    local = {p: (sha, mode) for p, mode, sha in items}
    remote = remote_tree(repo_full, branch)
    only_remote = sorted(set(remote) - set(local))
    only_local = sorted(set(local) - set(remote))
    diff = [p for p in set(local) & set(remote) if local[p][0] != remote[p][0]]
    modediff = [p for p in set(local) & set(remote) if local[p][1] != remote[p][1]]
    print("远端 blob 数:", len(remote), " 本地:", len(local))
    print("仅远端有:", only_remote[:5], " 仅本地有:", only_local[:5])
    print("内容不一致:", len(diff), diff[:5])
    print("模式不一致:", len(modediff), modediff[:5])
    ok = not (only_remote or only_local or diff or modediff)
    print("★ 逐文件校验:", "全部一致" if ok else "有问题，见上")
    s, info = call("GET", f"/repos/{repo_full}")
    if s == 200:
        print("仓库:", info["full_name"], "private=" + str(info["private"]), info["html_url"])
    return ok


def count(repo_full, branch="main"):
    remote = remote_tree(repo_full, branch)
    local = len(head_items())
    print("远端文件数:", len(remote), " 本地文件数:", local)


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "whoami"
    arg2 = sys.argv[2] if len(sys.argv) > 2 else None
    arg3 = sys.argv[3] if len(sys.argv) > 3 else None
    if cmd == "whoami":
        whoami()
    elif cmd == "create":
        create(arg2, (arg3 != "public"))
    elif cmd == "upload":
        upload(arg2, arg3 or "main")
    elif cmd == "verify":
        sys.exit(0 if verify(arg2, arg3 or "main") else 1)
    elif cmd == "count":
        count(arg2, arg3 or "main")
    else:
        print(__doc__)
