"use strict";
/* ============================================================
   Adapter: NAS（HTTP JSON API）
   ------------------------------------------------------------
   Target for `deploy/nas/hcb/admin_ui/adapter.js`（构建时从本文件复制）。

   - `GET  /admin/api/state`  -> 状态 JSON（见 admin-ui/API.md）
   - `POST /admin/api/act`    -> {name, payload} -> {ok,data?,error?}
     请求头带 `X-CSRF-Token`（值来自 state 的 `csrf`），同源 + 管理员会话由服务端校验。
   - 401：整页跳 `/login?next=/admin`（SPA 里没有登录页）。
   - 剪贴板：`navigator.clipboard`（HTTPS 下的同源页面可用）。
   - 退出：POST 到 `/logout`，带 `_csrf` 字段（服务端要求同源 + CSRF）。
   ============================================================ */
(function () {
  const STATE_URL = "/admin/api/state";
  const ACT_URL = "/admin/api/act";
  const LOGIN_URL = "/login?next=/admin";
  let csrf = "";

  function takeCsrf(value) {
    if (typeof value === "string" && value) csrf = value;
  }

  async function request(url, options) {
    const init = Object.assign({ credentials: "same-origin", cache: "no-store" }, options || {});
    const res = await fetch(url, init);
    if (res.status === 401) {
      // 会话失效：整页走登录，不留在 SPA 里
      location.href = LOGIN_URL;
      throw new Error("未登录或会话已过期");
    }
    if (!res.ok) throw new Error("接口返回 HTTP " + res.status);
    const text = await res.text();
    if (!text) return {};
    try {
      return JSON.parse(text);
    } catch (err) {
      throw new Error("接口返回的不是 JSON");
    }
  }

  window.AdminAPI = {
    async state() {
      const data = await request(STATE_URL);
      takeCsrf(data && data.csrf);
      return data;
    },

    async act(name, payload) {
      const headers = { "Content-Type": "application/json" };
      if (csrf) headers["X-CSRF-Token"] = csrf;
      const res = await request(ACT_URL, {
        method: "POST",
        headers,
        body: JSON.stringify({ name: String(name), payload: payload || {} }),
      });
      if (!res || typeof res !== "object") return { ok: false, error: "接口返回格式不对" };
      takeCsrf(res.csrf);
      if (typeof res.ok !== "boolean") res.ok = false;
      return res;
    },

    async clipboard(text) {
      const value = String(text === null || text === undefined ? "" : text);
      if (!value) throw new Error("没有可复制的内容");
      if (!navigator.clipboard || typeof navigator.clipboard.writeText !== "function") {
        throw new Error("浏览器不允许剪贴板");
      }
      await navigator.clipboard.writeText(value);
      return { ok: true };
    },

    logout() {
      const form = document.createElement("form");
      form.method = "POST";
      form.action = "/logout";
      const field = document.createElement("input");
      field.type = "hidden";
      field.name = "_csrf";
      field.value = csrf;
      form.appendChild(field);
      document.body.appendChild(form);
      form.submit();
    },
  };
})();
