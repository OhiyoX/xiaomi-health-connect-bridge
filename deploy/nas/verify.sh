#!/usr/bin/env bash
# 只读部署验收检查：容器在跑、/health、/mcp 401 挑战、OAuth 元数据、
# 管理后台登录页、审计链。只发 GET/无声明的 POST，不写数据库、不改配置、
# 不需要令牌，也绝不打印任何秘密（脚本本身不读取 .env、令牌或密码）。
#
# 用法：
#   ./verify.sh                                   # 宿主机 + 默认端口映射
#   ./verify.sh --base-url https://health.example.com
#   ./verify.sh --audit-db /path/data/health.db   # 宿主机上的 owner 0 库
#   ./verify.sh --skip-audit                      # 不做审计链校验
#
# 环境变量：HCB_BASE_URL、HCB_AUDIT_DB、HCB_DEPLOY_DIR、HCB_VERIFY_TIMEOUT。
# 退出码：0 = 全部 PASS（或只有 SKIP）；1 = 至少一项 FAIL；2 = 用法错误。
set -uo pipefail

SCRIPT_DIR=$(CDPATH='' cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
BASE_URL=${HCB_BASE_URL:-http://192.0.2.9:18927}
DEPLOY_DIR=${HCB_DEPLOY_DIR:-<deploy-dir>}
AUDIT_DB=${HCB_AUDIT_DB:-$DEPLOY_DIR/data/health.db}
TIMEOUT=${HCB_VERIFY_TIMEOUT:-5}
SKIP_AUDIT=0
SKIP_AUDIT_ENV=${HCB_SKIP_AUDIT:-0}

PASSED=0
FAILED=0
SKIPPED=0

usage() {
    cat <<'EOF'
用法: verify.sh [--base-url URL] [--audit-db PATH] [--skip-audit] [--help]

  --base-url URL   服务根地址（默认 http://192.0.2.9:18927，容器内用 :18927）
  --audit-db PATH  owner 0 SQLite 路径（默认 $HCB_DEPLOY_DIR/data/health.db）
  --skip-audit     不校验审计链（数据库不在本机时用）

只读：只发 GET 与无凭据的 POST，不写库、不改配置、不读取任何秘密。
退出码 0 = 全 PASS（可含 SKIP），1 = 有 FAIL，2 = 用法错误。
EOF
}

pass() {
    PASSED=$((PASSED + 1))
    printf 'PASS %s\n' "$*"
}

fail() {
    FAILED=$((FAILED + 1))
    printf 'FAIL %s\n' "$*"
}

skip() {
    SKIPPED=$((SKIPPED + 1))
    printf 'SKIP %s\n' "$*"
}

die() {
    printf 'error: %s\n' "$*" >&2
    exit 2
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        -h|--help) usage; exit 0 ;;
        --base-url)
            [ "$#" -ge 2 ] || die "--base-url needs a value"
            BASE_URL=$2
            shift 2
            ;;
        --audit-db)
            [ "$#" -ge 2 ] || die "--audit-db needs a value"
            AUDIT_DB=$2
            shift 2
            ;;
        --skip-audit) SKIP_AUDIT=1; shift ;;
        *) die "unknown argument: $1 (try --help)" ;;
    esac
done

case "$SKIP_AUDIT_ENV" in
    1|true|yes) SKIP_AUDIT=1 ;;
esac

# 去掉尾部斜杠，元数据里的 issuer/resource 不带尾斜杠。
while [ "${BASE_URL%/}" != "$BASE_URL" ]; do
    BASE_URL=${BASE_URL%/}
done
[ -n "$BASE_URL" ] || die "base URL must not be empty"
case "$TIMEOUT" in
    ''|*[!0-9]*) die "HCB_VERIFY_TIMEOUT must be an integer" ;;
esac
[ "$TIMEOUT" -ge 1 ] || die "HCB_VERIFY_TIMEOUT must be at least 1"

command -v curl >/dev/null 2>&1 || die "curl not found"

TMP_DIR=$(mktemp -d "${TMPDIR:-/tmp}/hcb-verify-XXXXXX") || die "cannot create temp dir"
trap 'rm -rf -- "$TMP_DIR"' EXIT INT TERM
BODY_FILE="$TMP_DIR/body"
HEADER_FILE="$TMP_DIR/headers"
ERR_FILE="$TMP_DIR/curl.err"
AUDIT_FILE="$TMP_DIR/audit.json"

HAVE_PYTHON=0
if command -v python3 >/dev/null 2>&1; then
    HAVE_PYTHON=1
fi

# request METHOD PATH [curl args...] → 设置 HTTP_CODE；响应体/头写入临时文件。
request() {
    local method=$1 path=$2
    shift 2
    : > "$BODY_FILE"
    : > "$HEADER_FILE"
    : > "$ERR_FILE"
    if ! HTTP_CODE=$(curl -sS --max-time "$TIMEOUT" -X "$method" \
            -o "$BODY_FILE" -D "$HEADER_FILE" -w '%{http_code}' \
            "$@" "$BASE_URL$path" 2>"$ERR_FILE"); then
        HTTP_CODE=000
        return 1
    fi
    [ -n "$HTTP_CODE" ] || HTTP_CODE=000
    return 0
}

header_value() {
    # 返回第一个同名响应头的值（已去掉 CR）；没有则空。
    awk -v name="$1" '
        BEGIN { target = tolower(name) }
        index($0, ":") > 0 {
            key = tolower(substr($0, 1, index($0, ":") - 1))
            if (key == target) {
                value = substr($0, index($0, ":") + 1)
                sub(/^[ \t]+/, "", value)
                sub(/\r$/, "", value)
                print value
                exit
            }
        }
    ' "$HEADER_FILE"
}

json_get() {
    # $1 = 键路径（a.b 或 a.0）；失败退出 1。
    python3 - "$BODY_FILE" "$1" <<'PY'
import json
import sys

try:
    with open(sys.argv[1], encoding="utf-8") as handle:
        data = json.load(handle)
except Exception:
    sys.exit(1)
for part in sys.argv[2].split("."):
    try:
        if isinstance(data, list):
            data = data[int(part)]
        else:
            data = data[part]
    except (KeyError, IndexError, TypeError, ValueError):
        sys.exit(1)
if isinstance(data, bool):
    print("true" if data else "false")
elif isinstance(data, (dict, list)):
    print(json.dumps(data, ensure_ascii=False))
else:
    print(data)
PY
}

json_is() {
    # $1 = 键路径，$2 = 期望值；python3 缺失时退化为 grep 粗检查。
    local key=$1 expected=$2 actual
    if [ "$HAVE_PYTHON" = 1 ]; then
        actual=$(json_get "$key" 2>/dev/null) || return 1
        [ "$actual" = "$expected" ]
        return
    fi
    grep -Eq "\"$key\"[[:space:]]*:[[:space:]]*\"$expected\"" "$BODY_FILE"
}

json_list_has() {
    # $1 = 键路径（数组），$2 = 期望包含的字符串。
    local key=$1 expected=$2
    if [ "$HAVE_PYTHON" = 1 ]; then
        python3 - "$BODY_FILE" "$key" "$expected" <<'PY'
import json
import sys

try:
    with open(sys.argv[1], encoding="utf-8") as handle:
        data = json.load(handle)
except Exception:
    sys.exit(1)
for part in sys.argv[2].split("."):
    try:
        data = data[int(part)] if isinstance(data, list) else data[part]
    except (KeyError, IndexError, TypeError, ValueError):
        sys.exit(1)
sys.exit(0 if isinstance(data, list) and sys.argv[3] in data else 1)
PY
        return
    fi
    grep -Fq "\"$expected\"" "$BODY_FILE"
}

request_error() {
    local detail
    detail=$(head -n 1 "$ERR_FILE" 2>/dev/null || true)
    printf '%s' "${detail:-request failed}"
}

# ---------------------------------------------------------------- 1. /health
if ! request GET /health; then
    fail "health: $(request_error)"
elif [ "$HTTP_CODE" != 200 ]; then
    fail "health: GET /health -> $HTTP_CODE (expected 200)"
elif ! grep -Eq '"ok"[[:space:]]*:[[:space:]]*true' "$BODY_FILE"; then
    fail "health: 200 but body has no \"ok\": true"
else
    pass "health: GET /health -> 200 ok"
fi

# ------------------------------------------------------- 2. /mcp 401 挑战
CHALLENGE="Bearer resource_metadata=\"$BASE_URL/.well-known/oauth-protected-resource/mcp\""
if ! request POST /mcp \
        -H 'Content-Type: application/json' \
        -H 'Accept: application/json, text/event-stream' \
        --data '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'; then
    fail "mcp 401: $(request_error)"
elif [ "$HTTP_CODE" != 401 ]; then
    fail "mcp 401: POST /mcp without token -> $HTTP_CODE (expected 401)"
else
    actual=$(header_value www-authenticate)
    if [ "$actual" != "$CHALLENGE" ]; then
        fail "mcp 401: WWW-Authenticate is '${actual:-<missing>}', expected '$CHALLENGE'"
    else
        pass "mcp 401: challenge points at resource metadata"
    fi
fi

# -------------------------------------------- 3. 保护资源元数据（RFC 9728）
if ! request GET /.well-known/oauth-protected-resource; then
    fail "resource metadata: $(request_error)"
elif [ "$HTTP_CODE" != 200 ]; then
    fail "resource metadata: -> $HTTP_CODE (expected 200)"
elif ! json_is resource "$BASE_URL/mcp"; then
    fail "resource metadata: resource is not $BASE_URL/mcp"
elif ! json_list_has authorization_servers "$BASE_URL"; then
    fail "resource metadata: authorization_servers does not list $BASE_URL"
elif ! json_list_has scopes_supported "health.read"; then
    fail "resource metadata: scopes_supported lacks health.read"
else
    pass "resource metadata: resource/issuer/scopes ok"
fi

# ------------------------------------------ 4. 授权服务器元数据（RFC 8414）
if ! request GET /.well-known/oauth-authorization-server; then
    fail "as metadata: $(request_error)"
elif [ "$HTTP_CODE" != 200 ]; then
    fail "as metadata: -> $HTTP_CODE (expected 200)"
elif ! json_is issuer "$BASE_URL"; then
    fail "as metadata: issuer is not $BASE_URL"
elif ! json_is token_endpoint "$BASE_URL/token"; then
    fail "as metadata: token_endpoint is not $BASE_URL/token"
elif ! json_list_has code_challenge_methods_supported "S256"; then
    fail "as metadata: code_challenge_methods_supported lacks S256"
elif ! json_list_has grant_types_supported "refresh_token"; then
    fail "as metadata: grant_types_supported lacks refresh_token"
else
    pass "as metadata: issuer/endpoints/PKCE/grant types ok"
fi

# ------------------------------------------------------ 5. 管理员登录页
if ! request GET /login; then
    fail "login page: $(request_error)"
elif [ "$HTTP_CODE" = 503 ]; then
    fail "login page: 503 (admin password not configured; set HCB_ADMIN_PASSWORD once)"
elif [ "$HTTP_CODE" != 200 ]; then
    fail "login page: GET /login -> $HTTP_CODE (expected 200)"
elif ! grep -Fq 'name="password"' "$BODY_FILE"; then
    fail "login page: no password field"
elif ! grep -Fq 'name="_csrf"' "$BODY_FILE"; then
    fail "login page: no CSRF field"
else
    cache=$(header_value cache-control)
    case "$cache" in
        *no-store*) pass "login page: form rendered, Cache-Control no-store" ;;
        *) fail "login page: Cache-Control is '${cache:-<missing>}' (expected no-store)" ;;
    esac
fi

# ------------------------------------------------ 6. 后台门禁（未登录）
if ! request GET /admin; then
    fail "admin gate: $(request_error)"
elif [ "$HTTP_CODE" = 503 ]; then
    fail "admin gate: 503 (admin password not configured)"
elif [ "$HTTP_CODE" != 302 ]; then
    fail "admin gate: GET /admin without session -> $HTTP_CODE (expected 302)"
else
    location=$(header_value location)
    case "$location" in
        /login?next=*) pass "admin gate:未登录 302 /login?next=…" ;;
        *) fail "admin gate: Location is '${location:-<missing>}' (expected /login?next=…)" ;;
    esac
fi

# --------------------------------------------------------- 7. 审计链校验
if [ "$SKIP_AUDIT" = 1 ]; then
    skip "audit chain: skipped (--skip-audit)"
elif [ ! -f "$AUDIT_DB" ]; then
    skip "audit chain: database not found at $AUDIT_DB (use --audit-db or --skip-audit)"
elif [ "$HAVE_PYTHON" != 1 ]; then
    skip "audit chain: python3 not found on this host"
elif [ ! -f "$SCRIPT_DIR/hcb_admin.py" ]; then
    fail "audit chain: hcb_admin.py not found next to verify.sh"
elif python3 "$SCRIPT_DIR/hcb_admin.py" audit-verify --db "$AUDIT_DB" \
        >"$AUDIT_FILE" 2>&1; then
    rows=$(grep -m1 '"rows":' "$AUDIT_FILE" 2>/dev/null | tr -cd '0-9' || true)
    pass "audit chain: verified (${rows:-?} rows)"
else
    detail=$(grep -m1 '"error"' "$AUDIT_FILE" 2>/dev/null || true)
    fail "audit chain: ${detail:-audit-verify failed (see hcb_admin.py audit-verify)}"
fi

# ---------------------------------------------------------------- 汇总
printf 'SUMMARY: PASS=%d FAIL=%d SKIP=%d (base %s)\n' \
    "$PASSED" "$FAILED" "$SKIPPED" "$BASE_URL"
if [ "$FAILED" -gt 0 ]; then
    exit 1
fi
exit 0
