#!/usr/bin/env python3
"""把 docs/release-notes/*.md 发布到 GitHub Release 正文。

## 这个脚本存在的唯一理由

同一份发版说明活在两个渲染环境里，而它们对「相对链接」的解析基准不同：

| 渲染环境 | `(v1.7.0.md)` 解析成 | `(../../LICENSE)` 解析成 |
|---|---|---|
| 仓库文件视图 / 本地编辑器 / 任意托管站 | 同目录的兄弟文件 ✓ | 仓库根的 LICENSE ✓ |
| GitHub Release 页面 | 404 | 404 |

Release 页面不在 `docs/release-notes/` 这个目录下，它压根没有「当前目录」这个概念。

**解法不是把源文件改成绝对 URL。** 那样做等于为了一个渲染环境牺牲另外三个：本地点开
会跳浏览器而不是打开本地文件，仓库换到 Gitee / GitLab / 自建站之后所有链接仍指向
github.com 上那个旧地址。源文件必须保持相对路径——那是唯一在四个环境里都成立的写法。

所以转换放在**发布这一刻**：读源文件，把相对链接就地换成绝对 URL，把结果喂给
`gh release edit`。仓库里的文件一个字都不动。

## 仓库地址从哪来

从 `git remote get-url origin` 推导，**不写死**。仓库搬家之后重跑一次即可，
不需要改这个脚本。

## 用法

    python3 scripts/publish-release-notes.py --print v1.8.0     # 只打印，先看一眼
    python3 scripts/publish-release-notes.py v1.8.0             # 发布这一个
    python3 scripts/publish-release-notes.py --all              # 发布全部
"""

import argparse
import os
import re
import subprocess
import sys
from urllib.parse import quote

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
NOTES_DIR = "docs/release-notes"

# markdown 链接。第二组刻意不含空格与右括号——带标题的 [x](url "t") 本仓库没有，
# 真出现了会原样跳过而不是转坏。
LINK = re.compile(r"\[([^\]]*)\]\(([^)\s]+)\)")

SKIP_PREFIXES = ("http://", "https://", "#", "mailto:", "//")


def blob_base() -> str:
    """从 origin 推导「浏览某个文件」的 URL 前缀，末尾带斜杠。

    支持 SSH（git@host:owner/repo.git）与 HTTPS（https://host/owner/repo.git）两种写法。
    GitLab 的路径形状与 GitHub/Gitee 不同（多一段 /-/），按 host 区分。
    """
    url = subprocess.run(
        ["git", "-C", REPO_ROOT, "remote", "get-url", "origin"],
        capture_output=True, text=True, check=True,
    ).stdout.strip()

    m = re.match(r"^(?:git@|ssh://git@)([^:/]+)[:/](.+?)(?:\.git)?$", url) \
        or re.match(r"^https?://(?:[^@/]+@)?([^/]+)/(.+?)(?:\.git)?$", url)
    if not m:
        sys.exit(f"认不出 origin 的形状，没法推导仓库地址：{url}")
    host, path = m.group(1), m.group(2)

    infix = "/-/blob/" if "gitlab" in host else "/blob/"
    # 固定用 main 而不是 tag：老版本的说明是按<b>今天</b>的目录布局写的
    # （v1.3.1 那份在它自己的 tag 上还叫 release-notes-v1.3.0.md），
    # 按 tag 取会 404。
    return f"https://{host}/{path}{infix}main/"


def absolutize(text: str, base: str) -> tuple[str, int]:
    """把指向仓库内真实文件的相对链接换成绝对 URL。返回（新文本, 替换条数）。"""
    n = 0

    def repl(m: re.Match) -> str:
        nonlocal n
        label, target = m.group(1), m.group(2)
        if target.startswith(SKIP_PREFIXES):
            return m.group(0)
        path, _, anchor = target.partition("#")
        real = os.path.normpath(os.path.join(NOTES_DIR, path))
        # 「解析得到的文件在仓库里真的存在」是唯一判据。正文里那个示意用的
        # `![](路径)` 因此会被原样留下，不需要为它写例外。
        if not path or not os.path.exists(os.path.join(REPO_ROOT, real)):
            return m.group(0)
        n += 1
        # 锚点<b>不做百分号编码</b>：GitHub 的标题锚点 id 就是原始 UTF-8
        # （如 `#️-安全声明本版必须重读的部分`——开头那个看不见的字符是变体选择符
        # U+FE0F，slug 算法去掉了 ⚠ 却留下它）。编码过一遍虽然多数浏览器也能跳，
        # 但肉眼再也核对不了，改坏了不会有人发现。
        url = base + quote(real) + (("#" + anchor) if anchor else "")
        return f"[{label}]({url})"

    return LINK.sub(repl, text), n


def notes_path(tag: str) -> str:
    p = os.path.join(REPO_ROOT, NOTES_DIR, f"{tag}.md")
    if not os.path.exists(p):
        sys.exit(f"没有这份发版说明：{p}")
    return p


def all_tags() -> list[str]:
    d = os.path.join(REPO_ROOT, NOTES_DIR)
    tags = [f[:-3] for f in os.listdir(d) if re.fullmatch(r"v\d+\.\d+\.\d+\.md", f)]
    return sorted(tags, key=lambda t: [int(x) for x in t[1:].split(".")])


def main() -> None:
    ap = argparse.ArgumentParser(description="发布 release notes 到 GitHub Release 正文")
    ap.add_argument("tags", nargs="*", help="如 v1.8.0；省略则须给 --all")
    ap.add_argument("--all", action="store_true", help="全部发版说明")
    ap.add_argument("--print", dest="dry", action="store_true",
                    help="只把转换后的正文打到 stdout，不碰 Release")
    args = ap.parse_args()

    tags = all_tags() if args.all else args.tags
    if not tags:
        ap.error("给个 tag，或者用 --all")

    base = blob_base()
    for tag in tags:
        body, n = absolutize(open(notes_path(tag), encoding="utf-8").read(), base)
        if args.dry:
            print(body)
            continue
        r = subprocess.run(["gh", "release", "edit", tag, "--notes-file", "-"],
                           input=body, text=True, capture_output=True,
                           cwd=REPO_ROOT)
        print(f"{tag}  {'ok' if r.returncode == 0 else 'FAILED: ' + r.stderr.strip()}"
              f"  （{n} 条链接改成绝对 URL）")
        if r.returncode != 0:
            sys.exit(1)


if __name__ == "__main__":
    main()
