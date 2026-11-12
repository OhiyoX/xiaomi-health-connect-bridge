"""管理后台的无框架 HTML 片段。

约束（设计 §9、§11）：

- 所有动态值都经 :func:`esc`；只有本模块自己构造的字符串才包成 :class:`Markup`。
- 不输出任何 ``<script>`` 或内联事件属性，兼容
  ``Content-Security-Policy: default-src 'none'``。
- 表单一律带 ``_csrf``；页面不缓存（由 ``web._html_response`` 统一加头）。
"""
from __future__ import annotations

import html


class Markup(str):
    """已由本模块生成的 HTML；``esc`` 不再转义。"""

    __slots__ = ()


def esc(value: object) -> str:
    """转义任意值；``None`` → 空串，``Markup`` 原样返回。"""
    if isinstance(value, Markup):
        return str(value)
    if value is None:
        return ""
    return html.escape(str(value), quote=True)


def raw(value: object) -> Markup:
    """声明一段已经转义/可信的 HTML。只在本模块内使用。"""
    return Markup(str(value))


def link(href: object, text: object, cls: str = "") -> Markup:
    attr = f' class="{esc(cls)}"' if cls else ""
    return raw(f'<a href="{esc(href)}"{attr}>{esc(text)}</a>')


def badge(text: object, tone: str = "") -> Markup:
    attr = f' class="badge {esc(tone)}"' if tone else ' class="badge"'
    return raw(f"<span{attr}>{esc(text)}</span>")


def code(text: object) -> Markup:
    return raw(f"<code>{esc(text)}</code>")


def pre(text: object, cls: str = "secret") -> Markup:
    return raw(f'<pre class="{esc(cls)}"><code>{esc(text)}</code></pre>')


def text_input(name: str, value: object = "", *, type_: str = "text",
               required: bool = False, autocomplete: str | None = None,
               placeholder: str | None = None, inputmode: str | None = None) -> Markup:
    attrs = [
        f'type="{esc(type_)}"',
        f'name="{esc(name)}"',
        f'value="{esc(value)}"',
    ]
    if required:
        attrs.append("required")
    if autocomplete:
        attrs.append(f'autocomplete="{esc(autocomplete)}"')
    if placeholder:
        attrs.append(f'placeholder="{esc(placeholder)}"')
    if inputmode:
        attrs.append(f'inputmode="{esc(inputmode)}"')
    return raw("<input " + " ".join(attrs) + ">")


def password_input(name: str, *, required: bool = True,
                   autocomplete: str = "current-password") -> Markup:
    return text_input(name, "", type_="password", required=required,
                      autocomplete=autocomplete)


def file_input(name: str, *, accept: str | None = None) -> Markup:
    attr = f' accept="{esc(accept)}"' if accept else ""
    return raw(f'<input type="file" name="{esc(name)}"{attr}>')


def checkbox(name: str, value: object, label: object, *,
             checked: bool = False) -> Markup:
    attr = " checked" if checked else ""
    return raw(
        f'<label class="check"><input type="checkbox" name="{esc(name)}" '
        f'value="{esc(value)}"{attr}> {esc(label)}</label>'
    )


def textarea(name: str, value: object = "", *, rows: int = 4,
             placeholder: str | None = None) -> Markup:
    ph = f' placeholder="{esc(placeholder)}"' if placeholder else ""
    return raw(
        f'<textarea name="{esc(name)}" rows="{int(rows)}"{ph}>'
        f"{esc(value)}</textarea>"
    )


def select(name: str, options, selected: object = "") -> Markup:
    """``options`` 为 ``(value, label)`` 序列。"""
    items = []
    for value, label in options:
        attr = " selected" if str(value) == str(selected) else ""
        items.append(
            f'<option value="{esc(value)}"{attr}>{esc(label)}</option>')
    return raw(f'<select name="{esc(name)}">' + "".join(items) + "</select>")


def hidden(name: str, value: object) -> Markup:
    return raw(
        f'<input type="hidden" name="{esc(name)}" value="{esc(value)}">')


def field(label: object, control: object, hint: object = None) -> Markup:
    hint_html = f'<span class="hint">{esc(hint)}</span>' if hint else ""
    return raw(
        f'<div class="field"><label>{esc(label)} {control}</label>{hint_html}</div>'
    )


def group(label: object, control: object, hint: object = None) -> Markup:
    """一组复选框等不能塞进单个 ``<label>`` 的控件。"""
    hint_html = f'<span class="hint">{esc(hint)}</span>' if hint else ""
    return raw(
        f'<div class="field"><span class="label">{esc(label)}</span>'
        f"{control}{hint_html}</div>"
    )


def form(action: str, fields, csrf: str | None, *, submit: str = "保存",
         extra: object = "", confirm: str | None = None,
         cls: str = "", enctype: str | None = None) -> Markup:
    """构造一个 POST 表单；``fields`` 是本模块的片段序列。"""
    hidden_csrf = hidden("_csrf", csrf) if csrf is not None else ""
    confirm_attr = f' data-confirm="{esc(confirm)}"' if confirm else ""
    attr = f' class="{esc(cls)}"' if cls else ""
    enc = f' enctype="{esc(enctype)}"' if enctype else ""
    return raw(
        f'<form method="post" action="{esc(action)}"{attr}{enc}'
        f"{confirm_attr}>"
        f"{hidden_csrf}{''.join(str(f) for f in fields)}"
        f'<p><button type="submit">{esc(submit)}</button></p></form>'
    )


def table(headers, rows, *, empty: str = "暂无记录",
          cls: str = "") -> Markup:
    """表格；每个单元格都经 ``esc``，需要链接时传 :class:`Markup`。"""
    if not rows:
        return raw(f'<p class="muted">{esc(empty)}</p>')
    head = "".join(f"<th>{esc(h)}</th>" for h in headers)
    body = []
    for row in rows:
        cells = "".join(f"<td>{esc(cell)}</td>" for cell in row)
        body.append(f"<tr>{cells}</tr>")
    attr = f' class="{esc(cls)}"' if cls else ""
    return raw(
        f'<div class="scroll"><table{attr}><thead><tr>{head}</tr></thead>'
        f"<tbody>{''.join(body)}</tbody></table></div>"
    )


def _nav(nav: str, csrf: str | None) -> Markup:
    if nav == "admin":
        items = (
            ("/admin", "概览"),
            ("/admin/users", "用户"),
            ("/admin/keys", "静态密钥"),
            ("/admin/clients", "OAuth 客户端"),
            ("/admin/sessions", "会话"),
            ("/admin/audit", "审计"),
            ("/admin/security", "安全"),
            ("/admin/settings", "设置"),
            ("/account", "我的账号"),
        )
    elif nav == "account":
        items = (
            ("/account", "我的账号"),
            ("/account/password", "修改密码"),
        )
    else:
        return raw("")
    parts = ["<nav>"]
    parts.extend(str(link(href, text)) for href, text in items)
    if csrf is not None:
        parts.append(
            '<form method="post" action="/logout" class="inline">'
            f'{hidden("_csrf", csrf)}<button type="submit">退出登录</button></form>'
        )
    parts.append("</nav>")
    return raw("".join(parts))


def page(title: object, body: object, csrf: str | None = None,
         nav: str = "admin", *, identity: object = None,
         flash: Markup | None = None) -> str:
    """整页 HTML；``body`` 由调用方用本模块的片段拼好。"""
    who = ""
    if identity is not None:
        who = raw(f'<p class="identity">当前身份：<strong>{esc(identity)}</strong></p>')
    banner = flash if flash is not None else raw("")
    return (
        "<!doctype html><html lang=\"zh-CN\"><head>"
        "<meta charset=\"utf-8\">"
        "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
        f"<title>{esc(title)} · hc-bridge</title>"
        "<link rel=\"stylesheet\" href=\"/admin.css\"></head><body>"
        f"<header><h1>{esc(title)}</h1>{_nav(nav, csrf)}</header>"
        f"<main>{who}{banner}{body}</main></body></html>"
    )
