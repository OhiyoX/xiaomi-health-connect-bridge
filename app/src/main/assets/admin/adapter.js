"use strict";
/* ============================================================
   Adapter: App（WebView + 原生 JS 桥）
   ------------------------------------------------------------
   Target for `app/src/main/assets/admin/adapter.js`（构建时从本文件复制）。

   原生侧必须挂一个名为 `HcbAdmin` 的对象（`addJavascriptInterface`），
   方法返回 **JSON 字符串**：

       HcbAdmin.state()                     -> 状态 JSON
       HcbAdmin.act(name, payloadJson)      -> {"ok":true,...} / {"ok":false,"error":"…"}
       HcbAdmin.clipboard(text)             -> {"ok":true} / {"ok":false,...}
       HcbAdmin.close()                     -> 关闭管理页（退出）
       HcbAdmin.ready()                     -> 可选：首屏就绪信号

   本机限制通过 `caps` 注入（users / sessions 单条、策略不可改）；不伪造写动作。
   ============================================================ */
(function () {
  // App 专属能力：`localAccess`（本机访问卡片）与 `policies`（令牌 / 客户端的工具策略与时间窗）。
  // native state 里给了 caps 就**只补缺**：显式值优先，没写的键用这里的默认值。
  const CAPS = {
    users: "single", sessions: "single", settings: false,
    localAccess: true, policies: true, followTheme: true,
  };

  // App 里给 WebView 让出纵向空间：页头压缩到 44/40px（CSS 见 admin.css）。
  try {
    document.body.classList.add("compact-head");
  } catch (err) { /* body 还没解析完时忽略，DOMContentLoaded 再补一次 */ }
  document.addEventListener("DOMContentLoaded", () => {
    document.body.classList.add("compact-head");
  });

  function bridge() {
    const b = window.HcbAdmin;
    if (!b || typeof b.state !== "function") throw new Error("缺少原生桥 HcbAdmin");
    return b;
  }

  function parse(text, what) {
    if (typeof text !== "string" || !text) throw new Error(what + " 没有返回内容");
    try {
      return JSON.parse(text);
    } catch (err) {
      throw new Error(what + " 返回的不是 JSON");
    }
  }

  window.AdminAPI = {
    theme() {
      try {
        const b = window.HcbAdmin;
        if (b && typeof b.theme === "function") {
          const value = b.theme();
          if (value === "dark" || value === "light") return value;
        }
      } catch (err) { /* 回退到本地偏好 */ }
      return null;
    },

    async state() {
      const data = parse(bridge().state(), "state");
      if (!data || typeof data !== "object") throw new Error("state 返回格式不对");
      data.caps = Object.assign({}, CAPS, data.caps && typeof data.caps === "object" ? data.caps : {});
      return data;
    },

    async act(name, payload) {
      const res = parse(bridge().act(String(name), JSON.stringify(payload || {})), "act");
      if (!res || typeof res !== "object") return { ok: false, error: "act 返回格式不对" };
      if (typeof res.ok !== "boolean") res.ok = false;
      if (!res.ok) return { ok: false, error: res.error || "操作未完成" };
      // 原生桥把一次性明文放在结果顶层（{ok:true, secret,...}）；共享契约是 {ok:true, data:{…}}
      if (!res.data || typeof res.data !== "object") {
        const data = {};
        for (const key of Object.keys(res)) {
          if (key === "ok" || key === "data" || key === "error") continue;
          data[key] = res[key];
        }
        res.data = data;
      }
      return res;
    },

    async clipboard(text) {
      const value = String(text === null || text === undefined ? "" : text);
      if (!value) throw new Error("没有可复制的内容");
      const res = parse(bridge().clipboard(value), "clipboard");
      if (res && res.ok === false) throw new Error(res.error || "剪贴板不可用");
      return { ok: true };
    },

    logout() {
      bridge().close();
    },

    ready() {
      try {
        const b = window.HcbAdmin;
        if (b && typeof b.ready === "function") b.ready();
      } catch (err) {
        /* 就绪信号只是给原生收起加载指示，失败不影响功能 */
      }
    },
  };
})();
