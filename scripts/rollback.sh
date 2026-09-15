#!/usr/bin/env bash
# ======================================================================
# pm-system 回滚脚本
# ----------------------------------------------------------------------
# 把「代码 + 镜像」一起退到指定的历史提交，并重新部署。
#
# 用法（在服务器上执行）：
#   cd /root/pm-system
#   /bin/bash scripts/rollback.sh <commit-sha>          # 交互确认
#   /bin/bash scripts/rollback.sh <commit-sha> --yes    # 跳过确认
#   /bin/bash scripts/rollback.sh --show                # 只看当前版本与可选历史
#
# commit-sha 支持完整 40 位，也支持只要在仓库里唯一的前 7 位以上短 SHA。
#
# 能回滚到哪些版本？
#   只能回滚到「镜像 tag 版本化」改造之后的提交 —— 更早的提交没有 SHA tag 镜像，
#   脚本会拦下来（检查目标提交的 docker-compose.yml 是否引用 IMAGE_TAG）。
#
# 回滚之后会怎样？
#   线上代码处于 detached HEAD。下一次流水线部署会 `git checkout main` +
#   `git reset --hard origin/main`，自动回到最新版本 —— 也就是说
#   回滚是「临时止血」，不是「永久倒退」。要永久倒退就用 git revert 再 push。
#
# 为什么不自己抢 flock？
#   本脚本只做校验，真正的部署交给 scripts/deploy.sh，
#   由它持有 /tmp/pm-system-deploy.lock。这里再抢一次锁会自己把自己挡住。
# ======================================================================
set -Eeuo pipefail

APP_DIR="/root/pm-system"
BRANCH="main"
LOG_FILE="/var/log/pm-system-deploy.log"
CURRENT_TAG_FILE="/var/log/pm-system-current-tag"

ACR_HOST="crpi-jzqyelh9p5zb0dmu.cn-chengdu.personal.cr.aliyuncs.com"
ACR_NAMESPACE="t1longlive"
REGISTRY="$ACR_HOST/$ACR_NAMESPACE"

log() {
  echo "[$(date '+%F %T')] $*" | tee -a "$LOG_FILE"
}

die() {
  log "ERROR: $*"
  exit 1
}

TARGET_ARG=""
ASSUME_YES="no"
SHOW_ONLY="no"

for arg in "$@"; do
  case "$arg" in
    --yes|-y) ASSUME_YES="yes" ;;
    --show|-s) SHOW_ONLY="yes" ;;
    -h|--help)
      # 打印文件头部的注释块：跳过 shebang，到 `set -Eeuo` 为止。
      # 用纯 bash 循环而不是 sed/awk —— 不引入外部命令依赖，也不会因行号漂移而截断。
      while IFS= read -r line; do
        case "$line" in
          '#!'*) continue ;;
          'set -Eeuo'*) break ;;
        esac
        printf '%s\n' "$line"
      done < "$0"
      exit 0
      ;;
    *) TARGET_ARG="$arg" ;;
  esac
done

cd "$APP_DIR" || die "找不到 $APP_DIR"

# ---------- 只看当前版本 ----------
if [ "$SHOW_ONLY" = "yes" ]; then
  echo "当前部署 tag（本脚本部署成功后写入）："
  if [ -f "$CURRENT_TAG_FILE" ]; then
    cat "$CURRENT_TAG_FILE"
  else
    echo "  （无记录，说明还从未用带 tag 的版本部署过）"
  fi
  echo
  echo "工作区当前 HEAD："
  git log -1 --format='  %H  %ad  %s' --date=short
  echo
  echo "最近 10 条提交（可作为回滚目标）："
  git log -10 --format='  %h  %ad  %s' --date=short
  echo
  echo "线上容器实际使用的镜像："
  docker inspect -f '  {{.Name}} -> {{.Config.Image}}' pm-backend pm-frontend 2>/dev/null || true
  exit 0
fi

[ -n "$TARGET_ARG" ] || die "用法: /bin/bash scripts/rollback.sh <commit-sha> [--yes]"

# ---------- 解析目标提交 ----------
git fetch origin "$BRANCH"
TARGET_SHA="$(git rev-parse --verify --quiet "${TARGET_ARG}^{commit}" || true)"
if [ -z "$TARGET_SHA" ]; then
  die "本地仓库里找不到提交 ${TARGET_ARG}。先 git fetch origin $BRANCH 再试。"
fi

# ---------- 兼容性校验：目标提交必须支持 IMAGE_TAG ----------
# 改造之前的提交，compose 里写死 :latest，回滚过去会拉错镜像，必须拦住。
# 先分开判"文件不存在"和"文件里没有 IMAGE_TAG"，否则两者的报错文案会混淆排查方向。
if ! git cat-file -e "$TARGET_SHA:docker-compose.yml" 2>/dev/null; then
  die "提交 ${TARGET_SHA} 里没有 docker-compose.yml，无法回滚。"
fi
if ! git show "$TARGET_SHA:docker-compose.yml" | grep -q 'IMAGE_TAG'; then
  die "提交 ${TARGET_SHA} 的 docker-compose.yml 不使用 IMAGE_TAG（早于 tag 版本化改造），无法回滚。"
fi

# ---------- 先验证镜像真的在 ACR 上，且能拉到 ----------
# 顺序很重要：这里失败时线上什么都没动，旧版本还在正常跑。
log "Verifying images exist in ACR"
if ! docker pull "$REGISTRY/pm-backend:$TARGET_SHA" >/dev/null; then
  die "ACR 上没有 $REGISTRY/pm-backend:$TARGET_SHA，无法回滚（线上未改动）。"
fi
if ! docker pull "$REGISTRY/pm-frontend:$TARGET_SHA" >/dev/null; then
  die "ACR 上没有 $REGISTRY/pm-frontend:$TARGET_SHA，无法回滚（线上未改动）。"
fi

CURRENT_SHA="$(git rev-parse HEAD)"
log "Rollback target : $TARGET_SHA  $(git log -1 --format='%s' "$TARGET_SHA")"
log "Current HEAD    : $CURRENT_SHA  $(git log -1 --format='%s' "$CURRENT_SHA")"

if [ "$TARGET_SHA" = "$CURRENT_SHA" ]; then
  log "目标提交与当前 HEAD 相同，无需回滚。"
  exit 0
fi

if [ "$ASSUME_YES" != "yes" ]; then
  echo
  echo "即将把线上从 $CURRENT_SHA 回滚到 $TARGET_SHA 。"
  echo "确认请输 yes（若是非交互环境，请改用 --yes）："
  # `|| reply=""` 不能省：set -e 下 read 遇到 EOF 会直接终止脚本，
  # 那样"已取消"的提示还没打出来就退出了，用户只看到一个空荡荡的失败。
  read -r reply || reply=""
  [ "$reply" = "yes" ] || die "已取消，线上未改动。"
fi

# ---------- 交给 deploy.sh 执行 ----------
# 为什么先复制一份到 /tmp 再执行：
#   deploy.sh 内部会 git checkout 到目标提交，仓库里的 scripts/deploy.sh
#   有可能被同时改写。bash 是边读边执行脚本文件的，
#   读一个正在被替换的文件是自找麻烦。复制到 /tmp 后与工作区解耦。
TMP_SCRIPT="$(mktemp /tmp/pm-system-deploy-XXXXXX.sh)"
cp "$APP_DIR/scripts/deploy.sh" "$TMP_SCRIPT"
chmod +x "$TMP_SCRIPT"
trap 'rm -f "$TMP_SCRIPT"' EXIT

log "Handing over to deploy.sh (rollback to $TARGET_SHA)"
TARGET_COMMIT="$TARGET_SHA" /bin/bash "$TMP_SCRIPT" "$BRANCH"
