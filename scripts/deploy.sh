#!/usr/bin/env bash
# ======================================================================
# pm-system 部署脚本（在阿里云服务器上由流水线调用）
# ----------------------------------------------------------------------
# 与 Cloudland 的 deploy.sh 同源设计：
#   - set -Eeuo pipefail：任一命令失败即退出，不吞错
#   - flock：并发保护（两次部署同时跑会把容器状态搞乱）
#   - 健康检查：等前端容器真正 running 才算成功
#   - 失败时打印容器日志，便于在流水线页面直接定位
# ======================================================================
set -Eeuo pipefail

APP_DIR="/root/pm-system"
BRANCH="${1:-main}"
LOCK_FILE="/tmp/pm-system-deploy.lock"
LOG_FILE="/var/log/pm-system-deploy.log"
FRONTEND_CONTAINER="pm-frontend"
FRONTEND_PORT="8081"

log() {
  echo "[$(date '+%F %T')] $*" | tee -a "$LOG_FILE"
}

# ---------- 并发保护 ----------
exec 9>"$LOCK_FILE"
if ! flock -n 9; then
  log "Another deployment is already running, exiting"
  exit 1
fi

cd "$APP_DIR"

# ---------- 选择 compose 命令 ----------
if docker compose version >/dev/null 2>&1; then
  DC="docker compose"
elif command -v docker-compose >/dev/null 2>&1; then
  DC="docker-compose"
else
  log "docker compose is not installed"
  exit 1
fi

# ---------- 环境文件检查 ----------
if [ ! -f .env ]; then
  log "ERROR: .env not found in $APP_DIR"
  log "请在服务器上创建 .env（可参考 .env.example），否则容器起不来"
  exit 1
fi

log "Starting deployment for branch: $BRANCH"

# ---------- 拉取最新代码 ----------
git fetch origin "$BRANCH"
git checkout "$BRANCH"
git reset --hard "origin/$BRANCH"

# ---------- 应用镜像来自云端构建产物 ----------
# 方案 B：镜像由 Gitee 云端构建（build@docker）并推送到阿里云 ACR，
# 服务器只负责拉取运行，不再本地编译 —— 因此不再需要 Maven / Node，
# 也就没有了本地编译的内存峰值。
ACR_HOST="crpi-jzqyelh9p5zb0dmu.cn-chengdu.personal.cr.aliyuncs.com"

# 私有仓库需要先登录。若没登录，pull 会失败并报 Authentication 相关错误，
# 这里提前检测并给出可执行的提示，避免对着难懂的报错排查。
if [ ! -f "$HOME/.docker/config.json" ] || ! grep -q "$ACR_HOST" "$HOME/.docker/config.json" 2>/dev/null; then
  log "WARN: 未检测到 $ACR_HOST 的登录记录"
  log "      若 pull 失败，请先在服务器上执行： docker login $ACR_HOST"
fi

log "Pulling dependency images"
$DC pull mysql redis || true

log "Pulling application images from registry"
$DC pull backend frontend

log "Starting containers"
$DC up -d --remove-orphans --force-recreate

# ---------- 健康检查 ----------
wait_for_frontend() {
  local retries=12
  local delay=5
  local status=""

  for _ in $(seq 1 "$retries"); do
    status="$(docker inspect -f '{{.State.Status}}' "$FRONTEND_CONTAINER" 2>/dev/null || true)"
    if [ "$status" = "running" ]; then
      log "Frontend container is running"
      return 0
    fi
    sleep "$delay"
  done

  log "Frontend container failed to reach running state, current status: ${status:-missing}"
  $DC ps | tee -a "$LOG_FILE" >/dev/null || true
  $DC logs --tail=200 frontend | tee -a "$LOG_FILE" >/dev/null || true
  $DC logs --tail=200 backend  | tee -a "$LOG_FILE" >/dev/null || true
  exit 1
}

wait_for_frontend

log "Container status"
$DC ps

log "Cleaning unused images"
docker image prune -f >/dev/null 2>&1 || true

log "Deployment finished"
