#!/usr/bin/env bash
# ======================================================================
# pm-system 部署脚本（在阿里云服务器上由流水线调用）
# ----------------------------------------------------------------------
# 与 Cloudland 的 deploy.sh 同源设计：
#   - set -Eeuo pipefail：任一命令失败即退出，不吞错
#   - flock：并发保护（两次部署同时跑会把容器状态搞乱）
#   - 镜像按 commit SHA 取 tag（不再是 latest）→ 因此可回滚
#   - 健康检查：等前端容器真正 running 才算成功
#   - 失败时打印容器日志，便于在流水线页面直接定位
#
# 用法：
#   scripts/deploy.sh [branch]              # 正常部署：跟随分支最新提交
#   TARGET_COMMIT=<sha> scripts/deploy.sh   # 回滚：部署指定提交（由 rollback.sh 传）
#   IMAGE_TAG=<tag> scripts/deploy.sh       # 强制指定镜像 tag（逃生口，一般不用）
#
# 镜像 tag 的真相来源只有一个：当前工作区的 HEAD。
#   构建阶段（Gitee 云端）打 tag = ${GITEE_COMMIT}（= 本次 push 的完整 commit）
#   部署阶段（本脚本）   算 tag = git rev-parse HEAD
#   两者是同一个值，前提是部署时 origin/<branch> 没有被更晚的 push 覆盖。
#   若发生覆盖，pull 会失败并给出明确提示 —— 宁可报错，不部署错版本。
# ======================================================================
set -Eeuo pipefail

APP_DIR="/root/pm-system"
BRANCH="${1:-main}"
TARGET_COMMIT="${TARGET_COMMIT:-}"
LOCK_FILE="/tmp/pm-system-deploy.lock"
LOG_FILE="/var/log/pm-system-deploy.log"
CURRENT_TAG_FILE="/var/log/pm-system-current-tag"
FRONTEND_CONTAINER="pm-frontend"
FRONTEND_PORT="8081"

ACR_HOST="crpi-jzqyelh9p5zb0dmu.cn-chengdu.personal.cr.aliyuncs.com"
ACR_NAMESPACE="t1longlive"
REGISTRY="$ACR_HOST/$ACR_NAMESPACE"

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

# ---------- 同步代码 ----------
git fetch origin "$BRANCH"

if [ -n "$TARGET_COMMIT" ]; then
  # 回滚模式：代码与镜像一起退到同一个 commit，保证两者严格一致。
  # 这是有意"倒退"，所以不参与下面的"过期构建"判断。
  # 下次流水线部署会 checkout main + reset --hard origin/main，自动回到最新。
  log "Rollback mode: checking out $TARGET_COMMIT"
  git checkout --detach "$TARGET_COMMIT"
else
  # 正常模式：先判断「本次构建是不是已经过期」。
  #
  # GITEE_COMMIT 是流水线注入的、本次构建所对应的提交（已实测在 shell@agent 里可读）。
  # 若它已不等于分支最新，说明构建期间又有新 push 进来 —— 本次**跳过部署**，
  # 交给那条更新的流水线去上线。
  #
  # 为什么不"照旧部署"、也不"强制部署旧提交"：
  #   · 照旧部署（reset --hard origin/BRANCH）会拿到新代码、却去拉旧提交的镜像
  #     → pull 报 manifest unknown，白跑一次（吵，但至少安全）
  #   · 强制锚定 GITEE_COMMIT 部署旧提交 → 可能在新版本之后才落地，
  #     把线上**顺序倒退**成旧版本（静默，且危险）
  #   · 跳过则两种坏结果都不会发生：线上不动，更新的那条流水线负责上线
  if [ -n "${GITEE_COMMIT:-}" ] && [ "$GITEE_COMMIT" != "$(git rev-parse "origin/$BRANCH")" ]; then
    log "Skipping deploy: built commit ${GITEE_COMMIT} is no longer the tip of ${BRANCH}"
    log "  分支最新已是 $(git rev-parse "origin/$BRANCH")，说明构建期间又有 push。"
    log "  本次不动线上 —— 那条更新的流水线会负责部署最新版本。"
    exit 0
  fi

  git checkout "$BRANCH"
  git reset --hard "origin/$BRANCH"
fi

# ---------- 计算镜像 tag ----------
# 只有两种来源，顺序不能颠倒：
#   1) 环境变量 IMAGE_TAG —— 运维手工指定（逃生口）
#   2) git rev-parse HEAD —— 当前代码版本，与云端构建使用的 ${GITEE_COMMIT} 对应
if [ -n "${IMAGE_TAG:-}" ]; then
  TAG_SOURCE="env IMAGE_TAG"
else
  TAG_SOURCE="git rev-parse HEAD"
  IMAGE_TAG="$(git rev-parse HEAD)"
fi
export IMAGE_TAG

log "Image tag      : $IMAGE_TAG  (source: $TAG_SOURCE)"
log "HEAD commit    : $(git rev-parse HEAD)"
if [ -n "$TARGET_COMMIT" ]; then
  log "HEAD subject   : $(git log -1 --format=%s HEAD)"
fi

# ---------- 审计记录：本次部署来自哪条流水线 / 哪个提交 ----------
# 已实测（2026-09-15）：GITEE_COMMIT / GITEE_BRANCH / GITEE_PIPELINE_BUILD_NUMBER
# 在 shell@agent 里**都能读到**（此前文档里"不保证能读到"的判断是错的）。
# 这行保留下来当审计线索：出问题时能直接对上是"哪次构建"把它推上线的。
# 手工执行（回滚、手动部署）时必然为空，显示 <unset>，属正常。
log "DIAG GITEE_COMMIT=${GITEE_COMMIT:-<unset>} GITEE_BRANCH=${GITEE_BRANCH:-<unset>} GITEE_PIPELINE_BUILD_NUMBER=${GITEE_PIPELINE_BUILD_NUMBER:-<unset>}"

# ---------- 登录检查 ----------
# 私有仓库需要先登录。若没登录，pull 会失败并报 Authentication 相关错误，
# 这里提前检测并给出可执行的提示，避免对着难懂的报错排查。
if [ ! -f "$HOME/.docker/config.json" ] || ! grep -q "$ACR_HOST" "$HOME/.docker/config.json" 2>/dev/null; then
  log "WARN: 未检测到 $ACR_HOST 的登录记录"
  log "      若 pull 失败，请先在服务器上执行： docker login $ACR_HOST"
fi

log "Pulling dependency images"
$DC pull mysql redis || true

# ---------- 拉取应用镜像（方案 B：来自云端构建产物）----------
# 镜像由 Gitee 云端构建（build@docker）推送到 ACR，服务器只拉取运行，
# 不再本地编译 —— 因此不再需要 Maven / Node，也没有本地编译的内存峰值。
#
# pull 必须在 `up -d` 之前，且失败必须直接退出：这样线上旧容器完全不受影响。
# 这也是回滚能安全生效的前提。
log "Pulling application images from registry"
log "  backend : $REGISTRY/pm-backend:$IMAGE_TAG"
log "  frontend: $REGISTRY/pm-frontend:$IMAGE_TAG"

if ! $DC pull backend frontend; then
  log "ERROR: 拉取应用镜像失败 —— 线上未做任何改动，旧版本仍在运行。"
  log "       先确认 ACR 上确实存在这个 tag："
  log "         docker manifest inspect $REGISTRY/pm-backend:$IMAGE_TAG"
  log "         docker manifest inspect $REGISTRY/pm-frontend:$IMAGE_TAG"
  log "       常见原因："
  log "         1) 构建阶段 tag 变量没生效，推上去的不是这个 SHA（查构建日志里 buildctl 的 -t 参数）"
  log "         2) 部署期间又有新 push，HEAD 已不是构建时那个 commit（看上面的 DIAG 行）"
  log "         3) 本机未 docker login $ACR_HOST"
  exit 1
fi

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

# ---------- 固化「当前线上版本」的证据 ----------
# 容器实际用的镜像地址是最好的证据：它同时证明了 tag 生效、且确实来自 ACR。
log "Running images"
docker inspect -f '{{.Name}} -> {{.Config.Image}}' pm-backend pm-frontend 2>/dev/null | tee -a "$LOG_FILE" || true

printf '%s\n' "$IMAGE_TAG" > "$CURRENT_TAG_FILE"
log "Current deployed tag recorded in $CURRENT_TAG_FILE"

log "Container status"
$DC ps

log "Cleaning unused images"
docker image prune -f >/dev/null 2>&1 || true

log "Deployment finished"
