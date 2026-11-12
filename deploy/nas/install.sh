#!/usr/bin/env bash
# 把 deploy/nas/ 的代码同步到 NAS 的 hc-health-mcp 容器并做健康检查。
#
# 关键点（Task 1 的部署记录）：
#   - 容器用 ./app:/app:ro 绑定挂载，所以同步 server.py + hcb/ + hcb_admin.py
#     后 docker compose up -d --force-recreate 即可生效，不重建镜像。
#   - 先备份 ./app 和 docker-compose.yaml，失败自动回滚；备份保留最近若干份。
#   - 不在 argv、日志或 stdout 打印 .env、令牌、密码。脚本从不读取 .env 内容。
#
# 用法（在 NAS 上，需要 docker 权限；建议 sudo）：
#   sudo ./install.sh
#   sudo HCB_DEPLOY_DIR=<deploy-dir> ./install.sh
#   sudo ./install.sh --rollback            # 回滚到最近一份备份
#   sudo ./install.sh --rollback <备份目录>  # 回滚到指定备份
#
# 环境变量：
#   HCB_DEPLOY_DIR   部署根目录（默认 <deploy-dir>）
#   HCB_CONTAINER    容器名（默认 hc-health-mcp，仅用于提示）
#   HCB_HEALTH_URL   health 检查 URL（默认 http://192.0.2.9:18927/health）
#   HCB_MCP_URL      /mcp 401 检查 URL（默认 http://192.0.2.9:18927/mcp）
#   HCB_HEALTH_TIMEOUT 等待 health 的秒数（默认 60）
#   HCB_BACKUP_KEEP  保留备份份数（默认 5）
#   HCB_SYNC_COMPOSE 设为 1 时同时覆盖部署目录的 docker-compose.yaml
set -euo pipefail
# bash 4.4+：让 $(...) 也继承 -e；下面的函数仍逐条检查，双保险。
shopt -s inherit_errexit 2>/dev/null || true
umask 077

SCRIPT_DIR=$(CDPATH='' cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
DEPLOY_DIR=${HCB_DEPLOY_DIR:-<deploy-dir>}
APP_DIR="$DEPLOY_DIR/app"
BACKUP_ROOT="$DEPLOY_DIR/backups"
CONTAINER=${HCB_CONTAINER:-hc-health-mcp}
HEALTH_URL=${HCB_HEALTH_URL:-http://192.0.2.9:18927/health}
MCP_URL=${HCB_MCP_URL:-http://192.0.2.9:18927/mcp}
HEALTH_TIMEOUT=${HCB_HEALTH_TIMEOUT:-60}
BACKUP_KEEP=${HCB_BACKUP_KEEP:-5}
SYNC_COMPOSE=${HCB_SYNC_COMPOSE:-0}

COMPOSE=()
BACKUP_DIR=""

log() { printf '%s\n' "$*"; }
die() { printf 'error: %s\n' "$*" >&2; exit 1; }

usage() {
    cat <<'EOF'
用法: install.sh [--help] [--rollback [备份目录]]

无参数：备份 -> 同步代码 -> 重建容器 -> /health 与 /mcp 401 检查。
--rollback：恢复最近一份（或指定）备份，然后重建容器并检查。

环境变量：HCB_DEPLOY_DIR、HCB_HEALTH_URL、HCB_MCP_URL、HCB_HEALTH_TIMEOUT、
HCB_BACKUP_KEEP、HCB_SYNC_COMPOSE。
EOF
}

pick_compose() {
    if docker compose version >/dev/null 2>&1; then
        COMPOSE=(docker compose)
    elif command -v docker-compose >/dev/null 2>&1; then
        COMPOSE=(docker-compose)
    else
        die "docker compose / docker-compose not found"
    fi
}

compose() { "${COMPOSE[@]}" "$@"; }

require_deploy() {
    command -v curl >/dev/null 2>&1 || die "curl not found"
    command -v docker >/dev/null 2>&1 || die "docker not found"
    [ -d "$DEPLOY_DIR" ] || die "deploy directory not found: $DEPLOY_DIR"
    [ -f "$DEPLOY_DIR/docker-compose.yaml" ] || \
        die "docker-compose.yaml not found in $DEPLOY_DIR"
    [ -d "$APP_DIR" ] || die "app directory not found: $APP_DIR"
    case "$HEALTH_TIMEOUT" in
        ''|*[!0-9]*) die "HCB_HEALTH_TIMEOUT must be an integer" ;;
    esac
    case "$BACKUP_KEEP" in
        ''|*[!0-9]*) die "HCB_BACKUP_KEEP must be an integer" ;;
    esac
    [ "$BACKUP_KEEP" -ge 1 ] || die "HCB_BACKUP_KEEP must be at least 1"
}

require_source() {
    [ -f "$SCRIPT_DIR/server.py" ] || die "server.py not found in $SCRIPT_DIR"
    [ -f "$SCRIPT_DIR/hcb_admin.py" ] || die "hcb_admin.py not found in $SCRIPT_DIR"
    [ -d "$SCRIPT_DIR/hcb" ] || die "hcb/ not found in $SCRIPT_DIR"
    [ -d "$SCRIPT_DIR/hcb/admin_ui" ] || \
        die "hcb/admin_ui/ not found in $SCRIPT_DIR (run scripts/build-admin-ui.py)"
    [ -f "$SCRIPT_DIR/hcb/admin_ui/index.html" ] || \
        die "hcb/admin_ui/index.html not found (run scripts/build-admin-ui.py)"
    [ -f "$SCRIPT_DIR/docker-compose.yaml" ] || \
        die "source docker-compose.yaml not found in $SCRIPT_DIR"
    local src
    for src in "$SCRIPT_DIR"/hcb/*.py; do
        [ -f "$src" ] || die "hcb/ has no .py files"
    done
}

make_backup() {
    local stamp created gitrev backup
    mkdir -p "$BACKUP_ROOT" || return 1
    chmod 700 "$BACKUP_ROOT" 2>/dev/null || return 1
    stamp=$(date +%Y%m%d-%H%M%S) || return 1
    backup="$BACKUP_ROOT/app-$stamp-$$"
    umask 077
    mkdir -m 700 "$backup" || return 1
    cp -a "$APP_DIR" "$backup/app" || return 1
    cp -a "$DEPLOY_DIR/docker-compose.yaml" "$backup/docker-compose.yaml" || return 1
    created=$(date -u +%Y-%m-%dT%H:%M:%SZ) || return 1
    gitrev=$(git -C "$SCRIPT_DIR" rev-parse --short HEAD 2>/dev/null || echo unknown) || return 1
    {
        printf 'created=%s\n' "$created"
        printf 'source=%s\n' "$SCRIPT_DIR"
        printf 'git=%s\n' "$gitrev"
        printf 'container=%s\n' "$CONTAINER"
    } > "$backup/MANIFEST" || return 1
    chmod 700 "$backup" || return 1
    chmod 600 "$backup/MANIFEST" || return 1
    [ -d "$backup/app" ] || return 1
    [ -f "$backup/MANIFEST" ] || return 1
    BACKUP_DIR="$backup"
}

copy_admin_ui() {
    # SPA 静态资源（构建产物）：白名单由 hcb/admin_api.py 决定，这里整目录同步。
    local src base names="" old
    install -d -m 700 "$APP_DIR/hcb/admin_ui" || return 1
    for src in "$SCRIPT_DIR/hcb/admin_ui"/*; do
        [ -f "$src" ] || continue
        base=$(basename -- "$src") || return 1
        names="$names $base"
        install -m 600 "$src" "$APP_DIR/hcb/admin_ui/$base" || return 1
    done
    [ -n "$names" ] || return 1
    # 源目录删掉的旧资源不能留在目标目录（否则 /admin/assets/* 还能命中旧文件）。
    for old in "$APP_DIR/hcb/admin_ui"/*; do
        [ -f "$old" ] || continue
        base=$(basename -- "$old") || return 1
        case " $names " in
            *" $base "*) ;;
            *) rm -f -- "$old" || return 1 ;;
        esac
    done
    [ -f "$APP_DIR/hcb/admin_ui/index.html" ]
}

sync_code() {
    # chmod 700/600：只有部署用户（及容器 root）可读写；目录不可被其他用户遍历。
    local src base old source_names=""
    install -d -m 700 "$APP_DIR" || return 1
    install -d -m 700 "$APP_DIR/hcb" || return 1
    install -m 600 "$SCRIPT_DIR/server.py" "$APP_DIR/server.py" || return 1
    install -m 600 "$SCRIPT_DIR/hcb_admin.py" "$APP_DIR/hcb_admin.py" || return 1
    for src in "$SCRIPT_DIR"/hcb/*.py; do
        base=$(basename -- "$src") || return 1
        source_names="$source_names $base"
        install -m 600 "$src" "$APP_DIR/hcb/$base" || return 1
    done
    # 删除源目录里已经不存在、但 app/hcb/ 仍残留的模块；备份已先做好。
    for old in "$APP_DIR"/hcb/*.py; do
        [ -f "$old" ] || continue
        base=$(basename -- "$old") || return 1
        case " $source_names " in
            *" $base "*) ;;
            *) rm -f -- "$old" || return 1 ;;
        esac
    done
    copy_admin_ui || return 1
    # 旧 pyc 可能被 Python 复用；删掉后由源码重新生成。
    rm -rf -- "$APP_DIR/__pycache__" "$APP_DIR/hcb/__pycache__" || return 1
    find "$APP_DIR" -type f -name '*.py' -exec chmod 600 {} + || return 1
    find "$APP_DIR/hcb/admin_ui" -type f -exec chmod 600 {} + || return 1
    find "$APP_DIR" -type d -exec chmod 700 {} + || return 1
    if [ -f "$DEPLOY_DIR/.env" ]; then
        chmod 600 "$DEPLOY_DIR/.env" 2>/dev/null || return 1
    fi
    if [ -d "$DEPLOY_DIR/data" ]; then
        chmod 700 "$DEPLOY_DIR/data" 2>/dev/null || return 1
    fi
    if [ "$SYNC_COMPOSE" = "1" ]; then
        install -m 600 "$SCRIPT_DIR/docker-compose.yaml" \
            "$DEPLOY_DIR/docker-compose.yaml" || return 1
    fi
    [ -f "$APP_DIR/server.py" ] || return 1
    [ -f "$APP_DIR/hcb_admin.py" ] || return 1
    [ -f "$APP_DIR/hcb/admin_ui/index.html" ] || return 1
    [ -n "$source_names" ] || return 1
}

wait_health() {
    local deadline body
    deadline=$((SECONDS + HEALTH_TIMEOUT))
    while [ "$SECONDS" -lt "$deadline" ]; do
        body=$(curl -fsS --max-time 3 "$HEALTH_URL" 2>/dev/null || true)
        if printf '%s' "$body" | grep -Eq '"ok"[[:space:]]*:[[:space:]]*true'; then
            return 0
        fi
        sleep 2
    done
    log "smoke: /health did not become ready within ${HEALTH_TIMEOUT}s"
    return 1
}

check_mcp_401() {
    local code
    code=$(curl -sS -o /dev/null -w '%{http_code}' --max-time 5 \
        -X POST "$MCP_URL" \
        -H 'Content-Type: application/json' \
        -H 'Accept: application/json, text/event-stream' \
        --data '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' \
        2>/dev/null || true)
    if [ "$code" != "401" ]; then
        log "smoke: /mcp returned ${code:-000} without a token (expected 401)"
        return 1
    fi
    return 0
}

smoke() {
    wait_health || return 1
    check_mcp_401 || return 1
    log "smoke: /health ok; /mcp without token -> 401"
}

restore_app() {
    local backup=$1
    [ -d "$backup/app" ] || { log "rollback: backup has no app/: $backup"; return 1; }
    log "rollback: restoring app from $backup"
    find "$APP_DIR" -mindepth 1 -maxdepth 1 -exec rm -rf -- {} + || return 1
    cp -a "$backup/app/." "$APP_DIR/" || return 1
    if [ -f "$backup/docker-compose.yaml" ]; then
        cp -a "$backup/docker-compose.yaml" \
            "$DEPLOY_DIR/docker-compose.yaml" || return 1
    fi
    find "$APP_DIR" -type f -name '*.py' -exec chmod 600 {} + || return 1
    if [ -d "$APP_DIR/hcb/admin_ui" ]; then
        find "$APP_DIR/hcb/admin_ui" -type f -exec chmod 600 {} + || return 1
    fi
    find "$APP_DIR" -type d -exec chmod 700 {} + || return 1
    [ -f "$APP_DIR/server.py" ]
}

prune_backups() {
    local backups=() i
    mapfile -t backups < <(ls -1dt "$BACKUP_ROOT"/app-* 2>/dev/null || true)
    if [ "${#backups[@]}" -le "$BACKUP_KEEP" ]; then
        return 0
    fi
    for ((i = BACKUP_KEEP; i < ${#backups[@]}; i++)); do
        rm -rf -- "${backups[$i]}" || return 1
    done
}

do_rollback() {
    local target=${1:-} backup
    pick_compose
    require_deploy
    if [ -z "$target" ]; then
        # shellcheck disable=SC2012  # 备份目录名由本脚本生成（时间戳+PID），无空格
        target=$(ls -1dt "$BACKUP_ROOT"/app-* 2>/dev/null | head -n 1 || true)
    fi
    [ -n "$target" ] || die "no backup found under $BACKUP_ROOT"
    [ -d "$target/app" ] || die "not a valid backup (missing app/): $target"
    [ -f "$target/MANIFEST" ] || die "not a valid backup (missing MANIFEST): $target"
    if ! restore_app "$target"; then
        die "rollback restore failed: $target"
    fi
    cd "$DEPLOY_DIR"
    if ! compose up -d --force-recreate; then
        die "rollback: docker compose up failed; backup remains at $target"
    fi
    if smoke; then
        log "rollback complete: $target"
        return 0
    fi
    die "rollback restored files but smoke failed; backup: $target"
}

main_install() {
    pick_compose
    require_deploy
    require_source
    if ! make_backup; then
        die "backup failed; nothing deployed"
    fi
    local backup="$BACKUP_DIR"
    if [ ! -d "$backup/app" ] || [ ! -f "$backup/MANIFEST" ]; then
        die "backup incomplete: $backup; nothing deployed"
    fi
    log "backup: $backup"

    if ! sync_code; then
        log "sync failed; restoring backup"
        if restore_app "$backup"; then
            (cd "$DEPLOY_DIR" && compose up -d --force-recreate) || \
                log "warning: rollback compose restart failed"
        else
            log "error: rollback restore failed; backup: $backup"
        fi
        die "install failed before restart; backup kept at $backup"
    fi

    cd "$DEPLOY_DIR"
    if ! compose up -d --force-recreate; then
        log "compose up failed; restoring backup"
        if restore_app "$backup"; then
            compose up -d --force-recreate || \
                log "warning: rollback compose restart failed"
        else
            log "error: rollback restore failed; backup: $backup"
        fi
        die "compose up failed; rollback attempted; backup: $backup"
    fi

    if ! smoke; then
        log "smoke failed; restoring backup"
        if restore_app "$backup"; then
            if compose up -d --force-recreate && smoke; then
                log "rollback complete: $backup"
            else
                log "rollback smoke also failed; backup: $backup"
            fi
        else
            log "error: rollback restore failed; backup: $backup"
        fi
        die "deployment smoke failed; backup: $backup"
    fi

    prune_backups || log "warning: could not prune old backups"
    log "install complete: $CONTAINER restarted"
    log "rollback command: sudo $SCRIPT_DIR/install.sh --rollback $backup"
}

case "${1:-}" in
    -h|--help)
        usage
        exit 0
        ;;
    --rollback)
        shift
        do_rollback "${1:-}"
        ;;
    "")
        main_install
        ;;
    *)
        die "unknown argument: $1 (try --help)"
        ;;
esac
