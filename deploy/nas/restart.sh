#!/bin/sh
# 在 NAS 上把 hc-health-mcp 重启到最新代码。
#
# 现状（2026-10-30 核实）：服务由 **容器** hc-health-mcp 提供（docker-compose，`./app:/app:ro` 绑定挂载），
# 所以「改 ./app/server.py 与 ./app/hcb/ → 重启容器」就生效，不需要重建镜像。
# 部署/install.sh 复制代码时必须把 server.py、hcb_admin.py 和整个 hcb/ 包一起同步到 app/。
#
# 旧版这个脚本是「杀用户进程再 nohup 起一个」——从容器占住 18927 之后它一直是**假成功**：
# 杀不动 root 的容器进程（|| true 吞掉），新进程 bind 失败写进 data/server.log 里
# `OSError: [Errno 98] Address already in use`，而脚本只看端口，看到容器在听就打印成功。
#
# 用法（需要 docker 权限；example-user 当前不在 docker 组，得 sudo）：
#   sudo sh restart.sh            # 自动判断：有容器就 docker restart，否则回落到用户进程
#
# 新部署优先用 install.sh（备份 + 同步 + 重建 + 冒烟 + 回滚）；本脚本只保留
# 作为旧的无容器环境回落。
set -e
D=<deploy-dir>
cd "$D"

# 1) 优先：容器模式
if command -v docker >/dev/null 2>&1 && docker ps --format '{{.Names}}' 2>/dev/null | grep -qx hc-health-mcp; then
  echo "--- 容器模式：docker restart hc-health-mcp ---"
  docker restart hc-health-mcp >/dev/null
  sleep 6
  echo "--- health ---"; curl -s --max-time 8 http://127.0.0.1:18927/health; echo
  echo "（代码来自 ./app 绑定挂载；server.py 与 hcb/ 都要同步，改完重启容器即生效；不需要重建镜像）"
  exit 0
fi

# 2) 回落：用户进程模式（只在没有容器时才有意义）
echo "--- 没有容器，按用户进程方式启动 ---"
if grep -qE '^(PUBLIC_BASE_URL|HCB_[A-Z_]+)=' .env; then
  echo "error: .env 含 OAuth/后台配置，拒绝回落并重写；请先修好容器或显式手动处理" >&2
  exit 1
fi
TOK=$(grep '^HEALTH_MCP_TOKEN=' .env | cut -d= -f2-)
TOKS=$(grep '^HEALTH_MCP_TOKENS=' .env | cut -d= -f2-)
{
  printf 'HEALTH_DB=%s/data/health.db\n' "$D"
  printf 'HEALTH_CREDS=%s/data/creds.json\n' "$D"
  printf 'PORT=18927\n'
  printf 'FETCH_INTERVAL_SEC=900\n'
  printf 'HEALTH_MCP_TOKEN=%s\n' "$TOK"
  if [ -n "$TOKS" ]; then
    printf 'HEALTH_MCP_TOKENS=%s\n' "$TOKS"
  fi
} > .env.new
mv .env.new .env
chmod 600 .env
for p in $(ps -eo pid,args 2>/dev/null | awk '/[a]pp\/server.py/{print $1}'); do kill "$p" 2>/dev/null || true; done
sleep 2
set -a
# shellcheck disable=SC1091  # .env 是部署目录里的运行时文件
. ./.env
set +a
setsid nohup python3 -u app/server.py > data/server.log 2>&1 < /dev/null &
sleep 8
echo "--- 端口 ---"; ss -ltn 2>/dev/null | grep 18927 || true
echo "--- health ---"; curl -s --max-time 8 http://127.0.0.1:18927/health; echo
echo "--- log ---"; tail -3 data/server.log
