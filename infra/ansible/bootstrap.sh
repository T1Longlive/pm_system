#!/usr/bin/env bash
# ======================================================================
# Ansible 控制端引导安装（在服务器上一次性执行）
# ----------------------------------------------------------------------
# 这是个「鸡生蛋」问题：想用 Ansible 管理服务器，前提是服务器上先有 Ansible。
# 所以这一步刻意用最朴素的 shell 完成 —— 然后所有变更都交给 playbook。
#
# 为什么装进 venv，而不是 `apt install ansible`？
#   · 不污染系统 Python（这台机器只有 1.8GiB 内存，别的服务也在共用）
#   · 版本可控；要回滚直接删目录，不留残渣
#   · 若 CI 里也用同一份 requirements，本机与流水线版本一致，
#     不会出现"本机跑得通、流水线跑不通"
#
# 用法（在服务器上）：
#   cd /root/pm-system
#   /bin/bash infra/ansible/bootstrap.sh
# ======================================================================
set -Eeuo pipefail

VENV_DIR="${ANSIBLE_VENV:-/root/.ansible-venv}"
PIP_INDEX="${PIP_INDEX:-https://pypi.tuna.tsinghua.edu.cn/simple}"
# 与本地验证环境对齐；升级前先确认新版本行为一致
ANSIBLE_CONSTRAINT="${ANSIBLE_CONSTRAINT:-ansible-core}"

log() { echo "[bootstrap] $*"; }
die() { echo "[bootstrap] ERROR: $*" >&2; exit 1; }

# ---------- 1. 确认 python3 可用 ----------
command -v python3 >/dev/null 2>&1 || die "找不到 python3，请先安装（apt install python3）"
log "python3: $(python3 --version 2>&1)"

# ---------- 2. 已装好就直接退出（这段 shell 自己也尽量幂等）----------
if [ -x "$VENV_DIR/bin/ansible-playbook" ]; then
  log "已存在可用的 Ansible，跳过安装"
  "$VENV_DIR/bin/ansible" --version
  exit 0
fi

# ---------- 3. 建 venv ----------
# Debian/Ubuntu 上 `python3 -m venv` 依赖 python3-venv 包，缺失时给出可执行提示。
if ! python3 -m venv "$VENV_DIR" 2>/dev/null; then
  die "创建 venv 失败。多半是缺少 python3-venv：
      apt update && apt install -y python3-venv
   装完再跑一次本脚本。"
fi
log "已创建 venv: $VENV_DIR"

# ---------- 4. 安装 ansible-core ----------
"$VENV_DIR/bin/pip" install -q --upgrade pip -i "$PIP_INDEX"
"$VENV_DIR/bin/pip" install -q "$ANSIBLE_CONSTRAINT" -i "$PIP_INDEX"

# ---------- 5. 自检 ----------
"$VENV_DIR/bin/ansible" --version

cat <<'EOF'

[bootstrap] 完成。下一步（只读采集现状，不改动任何东西）：

  cd /root/pm-system/infra/ansible
  /root/.ansible-venv/bin/ansible-playbook -i inventory.ini playbooks/00-baseline.yml

EOF
