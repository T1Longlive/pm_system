#!/usr/bin/env bash
# ======================================================================
# drift-check.sh —— 基础设施漂移检测（只读）
# ----------------------------------------------------------------------
# 回答一个问题：**线上现在的配置，还是不是仓库里声明的那个状态？**
#
# 为什么要它
#   IaC 的价值不在"把配置写进仓库"，而在"线上因此收敛到那个状态"。
#   2026-09-21 就出过一次事（见交接文档 §7.15）：
#     apply/01-docker-daemon.yml 写着 live-restore: true，
#     线上却一直是 false —— 漂移了好几小时，靠人工核对才发现。
#
# 检测原理（不需要额外工具）
#   Ansible 的 --check 是"演练"：不写任何东西，只报告"要改哪些"。
#   一个**本该幂等**的 apply 剧本，只要 --check 还说 changed≠0，
#   就说明线上跟期望状态已经分叉 —— 这就是漂移信号。
#
# 用法
#   bash infra/ansible/drift-check.sh
#
# 退出码（流水线靠它判断红/绿）
#   0  无漂移
#   1  检测到漂移（有人手工改了配置，或期望状态改了还没执行）
#   2  执行错误（ansible 挂了、解释器不对…… 这比漂移更该立刻处理）
#
# ⚠️ 只读保证：本脚本对 apply/ 只跑 --check，绝不实际执行。
#    audit/ 那三个本来就是只读探针。
# ======================================================================
set -Eeuo pipefail

ANSIBLE_DIR="/root/pm-system/infra/ansible"
ANSIBLE_BIN="/root/.ansible-venv/bin/ansible-playbook"
LOG_FILE="/var/log/pm-system-drift.log"

# 只跑 --check 的：apply/ 下三个会改系统的剧本
APPLY_PLAYBOOKS=(
  "playbooks/apply/01-docker-daemon.yml"
  "playbooks/apply/04-swap.yml"
  "playbooks/apply/05-ssh-authorized-keys.yml"
)

# 纯只读探针：跑它们是为了把"现状"留在日志里，便于事后对比
AUDIT_PLAYBOOKS=(
  "playbooks/audit/00-baseline.yml"
  "playbooks/audit/02-log-audit.yml"
  "playbooks/audit/03-disk-audit.yml"
)

# 进度与结论一律走 stderr + 日志文件；stdout 只用来传递机器可读的返回值（changed 计数）。
# 这样调用方可以 `changed=$(run_playbook ... | tail -1)` 而不被日志污染。
log() { printf '[%s] %s\n' "$(date '+%F %T')" "$*" | tee -a "$LOG_FILE" >&2; }

# 从 PLAY RECAP 那一行里抠出某个计数（ok / changed / failed）
# 形如：pm-ecs  : ok=15   changed=2    unreachable=0    failed=0
pick() {
  printf '%s\n' "$1" | grep -oE "$2=[0-9]+" | head -1 | grep -oE '[0-9]+$' || echo 0
}

run_playbook() {
  local pb="$1"
  local mode="$2"          # AUDIT | CHECK
  local out rc=0

  if [ "$mode" = "CHECK" ]; then
    out="$("$ANSIBLE_BIN" -i inventory.ini "$pb" --check 2>&1)" || rc=$?
  else
    out="$("$ANSIBLE_BIN" -i inventory.ini "$pb" 2>&1)" || rc=$?
  fi

  # 取 PLAY RECAP 行；取不到说明 playbook 根本没跑完
  local recap
  recap="$(printf '%s\n' "$out" | grep -E '^[^[:space:]].*: +ok=[0-9]+' | tail -1)"
  local changed failed
  changed="$(pick "$recap" changed)"
  failed="$(pick "$recap" failed)"

  if [ "$rc" -ne 0 ] || [ -z "$recap" ] || [ "$failed" -gt 0 ]; then
    log "  $mode $pb -> ERROR (exit=$rc, failed=$failed)"
    printf '%s\n' "$out" | tee -a "$LOG_FILE" >&2
    return 2
  fi

  log "  $mode $pb -> ok, changed=$changed"
  # 只有 CHECK 模式才需要把计数交给调用方；AUDIT 打印它会在日志里留下孤立的数字
  if [ "$mode" = "CHECK" ]; then
    printf '%s\n' "$changed"
  fi
  return 0
}

log "===== drift-check 开始 ====="

# ---------- 前置：环境必须可用 ----------
# 这一条是硬要求：ansible-core 2.19 要求被管端 Python >= 3.8，
# 不满足时模块**根本不会执行**，任务却显示 ok（交接文档 §7.14）。
# 那样本脚本会得出"无漂移"的错误结论 —— 比不检测更坏。
cd "$ANSIBLE_DIR" || { log "ERROR: $ANSIBLE_DIR 不存在"; exit 2; }
if [ ! -x "$ANSIBLE_BIN" ]; then
  log "ERROR: 未找到 $ANSIBLE_BIN —— 先跑 infra/ansible/bootstrap.sh"
  exit 2
fi

ERRORS=0
DRIFTS=""

# ---------- ① 只读探针：留一份现状 ----------
log "① 只读探针（audit/）"
for pb in "${AUDIT_PLAYBOOKS[@]}"; do
  [ -f "$pb" ] || { log "  SKIP $pb（文件不存在）"; continue; }
  # 探针的返回值不关心，但它的进度与报错要能看到 → 走 stderr，不污染 stdout
  if ! run_playbook "$pb" "AUDIT" >&2; then
    ERRORS=$((ERRORS + 1))
  fi
done

# ---------- ② 漂移检测：apply/ 只跑 --check ----------
# 判据很简单：changed=0 才是"线上 == 期望状态"。
log "② 漂移检测（apply/ 仅 --check，不写入）"
for pb in "${APPLY_PLAYBOOKS[@]}"; do
  [ -f "$pb" ] || { log "  SKIP $pb（文件不存在）"; continue; }

  # run_playbook 会把进度写进日志（stdout），真正要的值是它打印的最后一行
  changed="$(run_playbook "$pb" "CHECK" | tail -1)" || {
    ERRORS=$((ERRORS + 1))
    continue
  }
  # 防御：万一拿到的不是纯数字（比如 playbook 报错把堆栈打到了最后一行），按错误处理
  case "${changed:-}" in
    ''|*[!0-9]*) log "  CHECK $pb -> 无法解析 changed（拿到 ${changed:-<空>}），按 ERROR 处理"; ERRORS=$((ERRORS + 1)); continue ;;
  esac
  if [ "${changed:-0}" -gt 0 ]; then
    DRIFTS="${DRIFTS}    - ${pb} (changed=${changed})\n"
  fi
done

# ---------- ③ 结论 ----------
if [ "$ERRORS" -gt 0 ]; then
  log "RESULT: ERROR —— ${ERRORS} 个剧本没能正常跑完。这比漂移更紧急：检测结果本身不可信。"
  log "===== drift-check 结束（exit 2）====="
  exit 2
fi

if [ -n "$DRIFTS" ]; then
  log "RESULT: DRIFT DETECTED —— 以下剧本报告「需要变更」，即线上已偏离期望状态："
  printf "$DRIFTS" | tee -a "$LOG_FILE" >&2
  log "  处理：人工确认后跑对应 apply 剧本收敛（演练 → 执行 → 幂等），或改回期望状态。"
  log "  注意：也可能是有人改了仓库里的期望状态但还没执行 —— 先看 --check 的 diff 再决定方向。"
  log "===== drift-check 结束（exit 1）====="
  exit 1
fi

log "RESULT: OK —— 无漂移，线上与仓库声明的期望状态一致。"
log "===== drift-check 结束（exit 0）====="
exit 0
