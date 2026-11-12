#!/usr/bin/env python3
"""Generate the shared admin SPA assets and copy them to both hosts.

Single source of truth:

    docs/admin-ui-proto/src/1-head.html      -> admin-ui/admin.css
    docs/admin-ui-proto/src/3-body.html      -> admin-ui/sprite.svg
                                                + inline sprite block in admin-ui/index.html
    admin-ui/{index.html,data.js,render.js,app.js,adapters/*.js}

Targets (both get the same files; only ``adapter.js`` and the CSP meta differ):

    deploy/nas/hcb/admin_ui/            adapter.js = adapters/nas.js
    app/src/main/assets/admin/          adapter.js = adapters/app.js  (+ CSP meta)

Legacy: ``deploy/nas/hcb/style.py`` (``ADMIN_CSS``) is still generated for the
SSR pages that stay available under ``/admin/legacy`` after the SPA ships.

Usage:
    python3 scripts/build-admin-ui.py            # generate + copy
    python3 scripts/build-admin-ui.py --check    # exit 1 if anything is stale
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PROTO = ROOT / "docs" / "admin-ui-proto" / "src"
SPA = ROOT / "admin-ui"

TARGETS = {
    "nas": (ROOT / "deploy" / "nas" / "hcb" / "admin_ui", "nas.js"),
    "app": (ROOT / "app" / "src" / "main" / "assets" / "admin", "app.js"),
}

# NAS 把静态文件挂在 /admin/assets/ 下，而页面本身在 /admin（没有尾斜杠）——
# 相对路径会解析到 / 而不是 /admin/，所以 NAS 的 index.html 用绝对路径。
# App 从 file:///android_asset/admin/index.html 加载，保持相对路径。
ASSET_PREFIX = {"nas": "/admin/assets/", "app": ""}

# Body of the WebView page: everything is a same-directory asset, nothing remote.
# ``file:`` is listed next to ``'self'`` because a ``file://`` document has an
# opaque origin in Chromium and ``'self'`` alone does not reliably match siblings.
APP_CSP = (
    "default-src 'none'; script-src 'self' file:; "
    "style-src 'self' file: 'unsafe-inline'; img-src data:"
)

SPRITE_START = "<!-- sprite:start -->"
SPRITE_END = "<!-- sprite:end -->"
CSP_MARK = "<!-- build:csp -->"

SPA_FILES = ("index.html", "admin.css", "sprite.svg", "data.js", "render.js", "app.js")


# ------------------------------------------------------------------ 原型抽取
def proto_css() -> str:
    raw = (PROTO / "1-head.html").read_text(encoding="utf-8")
    start = raw.index("<style>") + len("<style>")
    return raw[start:].strip() + "\n"


def proto_sprite() -> str:
    raw = (PROTO / "3-body.html").read_text(encoding="utf-8")
    start = raw.index("<svg")
    end = raw.index("</svg>") + len("</svg>")
    return raw[start:end] + "\n"


# ------------------------------------------------------------------ 生成物
def sync_sprite(index_html: str, sprite: str) -> str:
    """把 sprite 写进 index.html 的标记块（幂等）。"""
    start = index_html.index(SPRITE_START) + len(SPRITE_START)
    end = index_html.index(SPRITE_END)
    return index_html[:start] + "\n" + sprite + index_html[end:]


def target_index(index_html: str, flavour: str) -> str:
    """按目标注入 / 去掉 CSP meta，并把资源路径改成该端要的形状。"""
    if flavour == "app":
        meta = f'<meta http-equiv="Content-Security-Policy" content="{APP_CSP}">'
    else:
        meta = ""
    if CSP_MARK not in index_html:
        raise SystemExit(f"index.html lacks {CSP_MARK}")
    if meta:
        html = index_html.replace(CSP_MARK, meta)
    else:
        html = index_html.replace(CSP_MARK + "\n", "")
    prefix = ASSET_PREFIX[flavour]
    if not prefix:
        return html
    html = html.replace('href="admin.css"', f'href="{prefix}admin.css"')
    for name in ("data.js", "render.js", "app.js", "adapter.js"):
        html = html.replace(f'src="{name}"', f'src="{prefix}{name}"')
    if 'href="admin.css"' in html:
        raise SystemExit("asset rewrite missed admin.css")
    return html


def build() -> tuple[dict[str, str], dict[str, dict[str, str]]]:
    """返回 (admin-ui 下的生成文件, {flavour: {文件名: 内容}})。"""
    css = proto_css()
    sprite = proto_sprite()

    index_path = SPA / "index.html"
    index_html = index_path.read_text(encoding="utf-8")
    index_html = sync_sprite(index_html, sprite)

    generated = {"admin.css": css, "sprite.svg": sprite}
    if index_html != index_path.read_text(encoding="utf-8"):
        generated["index.html"] = index_html

    shell = {
        "index.html": index_html,
        "admin.css": css,
        "sprite.svg": sprite,
        "data.js": (SPA / "data.js").read_text(encoding="utf-8"),
        "render.js": (SPA / "render.js").read_text(encoding="utf-8"),
        "app.js": (SPA / "app.js").read_text(encoding="utf-8"),
    }
    outputs: dict[str, dict[str, str]] = {}
    for flavour, (out_dir, adapter_name) in TARGETS.items():
        files = dict(shell)
        files["index.html"] = target_index(index_html, flavour)
        files["adapter.js"] = (SPA / "adapters" / adapter_name).read_text(encoding="utf-8")
        outputs[flavour] = files
    return generated, outputs


def style_py() -> str:
    style = proto_css()
    if "'''" in style:
        raise SystemExit("CSS contains triple quote; adjust style.py template")
    return (
        '"""Generated by scripts/build-admin-ui.py. Do not edit."""\n\n'
        "ADMIN_CSS = r'''" + style + "'''\n"
    )


# ------------------------------------------------------------------ 落盘 / 校验
def plan() -> dict[Path, str]:
    generated, outputs = build()
    files: dict[Path, str] = {SPA / name: text for name, text in generated.items()}
    files[ROOT / "deploy" / "nas" / "hcb" / "style.py"] = style_py()
    for flavour, (out_dir, _adapter) in TARGETS.items():
        for name, text in outputs[flavour].items():
            files[out_dir / name] = text
    return files


def write(files: dict[Path, str]) -> None:
    for path, text in files.items():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
    # prune generated files that are no longer produced (this dir is all build output)
    for _flavour, (out_dir, _adapter) in TARGETS.items():
        keep = {p.name for p in files if p.parent == out_dir}
        for stale in out_dir.glob("*"):
            if stale.is_file() and stale.suffix in (".html", ".js", ".css", ".svg") \
                    and stale.name not in keep:
                stale.unlink()


def check(files: dict[Path, str]) -> int:
    stale: list[str] = []
    for path, text in files.items():
        if not path.exists():
            stale.append(f"missing: {path.relative_to(ROOT)}")
        elif path.read_text(encoding="utf-8") != text:
            stale.append(f"stale:   {path.relative_to(ROOT)}")
    for _flavour, (out_dir, _adapter) in TARGETS.items():
        keep = {p.name for p in files if p.parent == out_dir}
        for extra in out_dir.glob("*"):
            if extra.is_file() and extra.suffix in (".html", ".js", ".css", ".svg") \
                    and extra.name not in keep:
                stale.append(f"extra:   {extra.relative_to(ROOT)}")
    if stale:
        print("admin-ui build is out of date:")
        for line in stale:
            print("  " + line)
        print("run: python3 scripts/build-admin-ui.py")
        return 1
    css = len((SPA / "admin.css").read_text(encoding="utf-8"))
    print(f"ok: admin-ui build is current ({css} bytes CSS, {len(files)} files)")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="fail if outputs are stale")
    args = parser.parse_args()
    files = plan()
    if args.check:
        return check(files)
    write(files)
    print(f"wrote {len(files)} files")
    for flavour, (out_dir, adapter) in TARGETS.items():
        print(f"  {flavour}: {out_dir.relative_to(ROOT)}/ (adapter.js = adapters/{adapter})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
