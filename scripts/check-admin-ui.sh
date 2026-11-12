#!/usr/bin/env bash
# 共享管理前端（admin-ui/）的静态检查：没有浏览器也能跑。
#
#   ./scripts/check-admin-ui.sh
#
# 检查项：
#   1. 每个 JS 文件的语法（源 + 两端构建出来的 adapter.js）
#   2. 渲染冒烟：所有路由 / 抽屉在两种真实状态形状下都能渲染（scripts/admin-ui-smoke.mjs）
#   3. 生成物是否最新（python3 scripts/build-admin-ui.py --check）
#   4. CSP 友好：无内联脚本、无内联事件、无外链资源 / 外链字体
#   5. 端到端（可选）：装了 Playwright 就用真 Chromium 跑一遍交互（scripts/admin-ui-e2e.mjs）；
#      没装就跳过（不阻塞没有浏览器的环境）
#
# 退出码非 0 = 不能合。
set -uo pipefail

cd "$(dirname "$0")/.." || exit 2

FAILED=()
STEP=0
step() { STEP=$((STEP + 1)); echo; echo "== [$STEP] $* =="; }
run() {
  local name="$1"; shift
  step "$name"
  if "$@"; then echo "-- OK: $name"; else echo "-- FAIL: $name"; FAILED+=("$name"); fi
}

JS_FILES=(
  admin-ui/data.js
  admin-ui/render.js
  admin-ui/app.js
  admin-ui/adapters/nas.js
  admin-ui/adapters/app.js
  deploy/nas/hcb/admin_ui/adapter.js
  app/src/main/assets/admin/adapter.js
  scripts/admin-ui-smoke.mjs
  scripts/admin-ui-e2e.mjs
  scripts/admin-ui-e2e-adapter.js
)

check_js() {
  local f ok=0
  for f in "${JS_FILES[@]}"; do
    if [ ! -f "$f" ]; then echo "missing: $f"; ok=1; continue; fi
    node --check "$f" || ok=1
  done
  [ "$ok" -eq 0 ] && echo "node --check: ${#JS_FILES[@]} files ok"
  return "$ok"
}

check_static() {
  local ok=0 bad
  # 内联脚本：每个 <script 都必须带 src=
  bad=$(grep -n '<script' admin-ui/index.html | grep -v 'src=' || true)
  if [ -n "$bad" ]; then echo "inline <script>: $bad"; ok=1; fi
  # 内联事件属性（页面与脚本里都不该出现）
  bad=$(grep -nE '\son(click|change|input|submit|load|error|focus|blur)=' \
          admin-ui/index.html admin-ui/data.js admin-ui/render.js admin-ui/app.js || true)
  if [ -n "$bad" ]; then echo "inline handler: $bad"; ok=1; fi
  # 外链资源 / CDN / 外部字体
  bad=$(grep -nE '(src|href)="https?://' admin-ui/index.html || true)
  if [ -n "$bad" ]; then echo "external resource: $bad"; ok=1; fi
  bad=$(grep -nE '@import|fonts\.googleapis|cdn\.' admin-ui/admin.css admin-ui/*.js || true)
  if [ -n "$bad" ]; then echo "external css/font: $bad"; ok=1; fi
  # 只有 adapter 能发请求 / 触达原生桥
  bad=$(grep -nE '\bfetch\(|XMLHttpRequest|window\.HcbAdmin' \
          admin-ui/data.js admin-ui/render.js admin-ui/app.js || true)
  if [ -n "$bad" ]; then echo "io outside adapter: $bad"; ok=1; fi
  # 动作名必须出现在 API.md（防文档与实现漂移）
  bad=$(grep -oE '"(user|session|token|oauth|pending|coverage|audit|security|settings|local)\.[a-z-]+"' admin-ui/app.js \
        | tr -d '"' | sort -u | while read -r act; do
            grep -q "| \`$act\` |" admin-ui/API.md || echo "$act"
          done)
  if [ -n "$bad" ]; then echo "action not documented in API.md: $bad"; ok=1; fi
  [ "$ok" -eq 0 ] && echo "static checks ok"
  return "$ok"
}

run "JS 语法（node --check）" check_js
run "渲染冒烟（全部路由 + 抽屉 × 两种状态形状）" node scripts/admin-ui-smoke.mjs
run "生成物最新（build-admin-ui.py --check）" python3 scripts/build-admin-ui.py --check
run "CSP / 无外链 / 文档同步" check_static

if node -e "import('playwright').then(()=>process.exit(0),()=>process.exit(1))" 2>/dev/null \
   || node -e "import('/usr/local/lib/node_modules/playwright/index.mjs').then(()=>process.exit(0),()=>process.exit(1))" 2>/dev/null; then
  run "端到端（Playwright / Chromium）" node scripts/admin-ui-e2e.mjs
else
  step "端到端（Playwright / Chromium）"
  echo "-- SKIP: 没有 playwright（只跑静态检查 + 渲染冒烟）"
fi

echo
if [ ${#FAILED[@]} -eq 0 ]; then
  echo "ADMIN-UI: PASS"
  exit 0
fi
echo "ADMIN-UI: FAIL"
for name in "${FAILED[@]}"; do echo "  - $name"; done
exit 1
