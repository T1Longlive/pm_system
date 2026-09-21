#!/usr/bin/env bash
# ======================================================================
# pm-system 部署脚本（在阿里云服务器上由流水线调用）
# ----------------------------------------------------------------------
# 与 Cloudland 的 deploy.sh 同源设计：
#   - set -Eeuo pipefail：任一命令失败即退出，不吞错
#   - flock：并发保护（两次部署同时跑会把容器状态搞乱）
#   - 镜像按 commit SHA 取 tag（不再是 latest）→ 因此可回滚
#   - 健康检查：后端 + 前端都查；后端还要求「没崩过 + 撑过启动期」
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
BACKEND_CONTAINER="pm-backend"
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

# ---------- 路径守卫：本次变更真的需要重新部署吗 ----------
# 为什么需要这一步
# ----------------
# 流水线的触发条件只有「push 到 main」，**没有路径过滤** —— 改 docs/ 或 infra/
# 也会走完整的构建 + 容器重建。
#   构建浪费的是云端分钟数；而**容器重建才是危险的那一半**：
#   2026-09-21 的 OOM 雪崩正是在容器重建的内存尖峰上被放大的
#   （那天推了 6 次，每次都让两个 JVM 冷启动一遍）。
#
# 🔴 为什么守卫放在这里，而不是去改流水线的 triggers
#   Gitee Go（个人版）的 push 触发只支持「分支 / Tag / 提交注释」三种匹配，
#   **没有文件路径过滤** —— 那是 Gitee 企业版流水线的能力。
#   在 triggers 里猜一个字段名，最坏情况是整个触发配置失效 →
#   完全不再自动部署，那比"多部署几次"严重得多。
#   放在这里则完全在版本控制内：可 review、可测试、不会被网页编辑器改掉。
#
# 判定方向刻意用「黑名单」（只有**全部**变更都落在非应用路径下才跳过）：
#   漏判的后果偏向"多部署一次"（无害），而不是"该部署却跳过了"（危险）。
if [ -z "$TARGET_COMMIT" ]; then
  # 回滚模式（TARGET_COMMIT 非空）不参与此判断 —— 回滚是有意倒退，必须执行。
  NON_APP_PATHS='^(docs/|infra/|\.workflow/|\.gitee/|\.github/|[^/]+\.md$|LICENSE$|\.gitignore$|\.gitattributes$)'

  # 「当前线上跑的是哪个版本」以**容器实际使用的镜像 tag** 为准 ——
  # 它比任何日志文件都真实（手工 docker compose up 不会更新日志文件）。
  RUNNING_IMAGE="$(docker inspect -f '{{.Config.Image}}' "$BACKEND_CONTAINER" 2>/dev/null || true)"
  RUNNING_TAG="${RUNNING_IMAGE##*:}"

  if [ -n "$RUNNING_TAG" ] && [ "$RUNNING_TAG" != "$IMAGE_TAG" ] \
     && git cat-file -e "${RUNNING_TAG}^{commit}" 2>/dev/null; then
    # 🔴 -c core.quotepath=false 不能省：
    #    git 默认给含非 ASCII 的路径加双引号并做八进制转义，
    #    `docs/CI-CD现状与补全计划.md` 会变成 `"docs/CI-CD\347\216\260..."` ——
    #    以引号开头就匹配不上 ^docs/，会把"只改了文档"误判成"改了应用"。
    #    这个坑是拿**真实提交历史**跑测试才发现的，光看正则看不出来。
    CHANGED="$(git -c core.quotepath=false diff --name-only "$RUNNING_TAG" "$IMAGE_TAG")"

    if [ -n "$CHANGED" ] && ! printf '%s\n' "$CHANGED" | grep -qvE "$NON_APP_PATHS"; then
      log "Skipping deploy: 本次变更未触及应用路径（线上保持 $RUNNING_TAG）"
      log "  变更文件（最多 20 条）："
      printf '%s\n' "$CHANGED" | head -20 | sed 's/^/    /' | tee -a "$LOG_FILE"
      log "  判定依据：以上路径全部属于「与运行无关」的文件（docs/ infra/ *.md 等），后端、前端、compose 均未改动。"
      exit 0
    fi
    log "变更触及应用路径，继续部署（$RUNNING_TAG -> $IMAGE_TAG）"
  else
    log "无法确定当前运行版本（容器不存在或该 tag 不在本地仓库），按常规继续部署"
  fi
fi

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
# 阶段三：后端崩溃不再被漏报成「部署成功」。
#
# 三个条件缺一不可，只查任何一个都会漏报：
#   1) status=running            容器活着
#   2) RestartCount=0            从创建起没崩过。
#      启动即崩的容器（如 DB 连不上）在 restart 策略下大部分时间也是 running
#      —— 每轮 JVM 要 boot ~12s 才崩，只看状态会在崩溃发生前就放行。
#   3) 连续 running ≥ min_stable  撑过了应用自身的启动期。
#      up -d 返回时 Spring Boot 还在 booting，此刻 status 必然是 running，
#      什么都不证明。后端启动实测 ~12s，给 25s 余量。
#
# 已知局限（有意为之）：应用「卡死但不退出」查不出来。
# 那需要 HTTP 探活（actuator/health 之类），暂不引入 ——
# 等真的出现过这类故障再补，别为没发生过的故障加复杂度。
wait_for_container() {
  local name="$1"
  local min_stable="${2:-0}"
  local retries=18
  local delay=5
  local status=""
  local restarts=0
  local first_running=0
  local up=0

  for _ in $(seq 1 "$retries"); do
    status="$(docker inspect -f '{{.State.Status}}' "$name" 2>/dev/null || true)"
    restarts="$(docker inspect -f '{{.RestartCount}}' "$name" 2>/dev/null || echo 0)"
    restarts="${restarts:-0}"

    if [ "$status" = "running" ]; then
      if [ "$first_running" -eq 0 ]; then first_running="$(date +%s)"; fi
      up=$(( $(date +%s) - first_running ))
      if [ "$restarts" -eq 0 ] && [ "$up" -ge "$min_stable" ]; then
        log "Container $name healthy: stable ${up}s (need >= ${min_stable}s), restarts=0"
        return 0
      fi
    else
      first_running=0
      up=0
    fi
    sleep "$delay"
  done

  log "Container $name FAILED health check: status=${status:-missing}, restarts=${restarts}, stable=${up}s (need >= ${min_stable}s)"
  log "Dumping container status and logs for diagnosis"
  $DC ps | tee -a "$LOG_FILE" >/dev/null || true
  $DC logs --tail=200 backend  | tee -a "$LOG_FILE" >/dev/null || true
  $DC logs --tail=200 frontend | tee -a "$LOG_FILE" >/dev/null || true
  exit 1
}

# 先查后端：它要连 DB、要完成 Spring 上下文，最容易在启动期失败，也最致命。
# 前端是 nginx，起来即就绪，不需要稳定期。
wait_for_container "$BACKEND_CONTAINER" 25
wait_for_container "$FRONTEND_CONTAINER" 0

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
