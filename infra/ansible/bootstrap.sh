#!/usr/bin/env bash
# ======================================================================
# Ansible 控制端引导安装（在服务器上执行，可反复执行 —— 幂等）
# ----------------------------------------------------------------------
# 这是个「鸡生蛋」问题：想用 Ansible 管理服务器，前提是服务器上先有 Ansible。
# 所以这一步刻意用最朴素的 shell 完成 —— 之后所有变更都交给 playbook。
#
# 为什么装进 venv，而不是 `apt/dnf install ansible`？
#   · 不污染系统 Python（这台机器只有 1.8GiB 内存，别的服务也在共用）
#   · 版本可控；要回滚直接删目录，不留残渣
#   · 版本钉死后，本机与 CI 行为一致，不会"这边跑得通那边跑不通"
#
# ----------------------------------------------------------------------
# 🔴 为什么必须钉死版本区间，而不是 `pip install ansible-core`
# ----------------------------------------------------------------------
# 控制端 ansible-core 对 Python 有**硬性区间要求**，而 pip 把它当作
# "解析约束"而不是"错误"：解释器太旧时 pip 会**静默降级**到某个老版本，
# exit code 0、无任何警告。官方文档明确记录过这种情形：
#   「在 Python 3.11 上 pip install ansible → 得到 ansible-core 2.19.12，
#     一个已 EOL 的社区版本，静默发生」
# 所以这里用区间把它钉住 —— **宁可让 pip 报错，也不要默默装个旧版本**。
#
# 版本对照（控制端 Python 区间是硬约束，顺序不能颠倒）：
#   ansible-core 2.21 → 控制端 Python 3.12 – 3.14
#   ansible-core 2.19 → 控制端 Python 3.11 – 3.13
#
# 🔴 CVE 下界：ansible-galaxy role install 存在参数注入导致代码执行的漏洞
#    （CVE-2026-11332，HIGH，2026-06-05 披露）
#    修复版本：2.21.1 / 2.20.7 / 2.19.11 / 2.18.18
#    → 区间下界不能低于 2.19.11。
#
# ----------------------------------------------------------------------
# 用法（在服务器上）
# ----------------------------------------------------------------------
#   cd /root/pm-system
#   /bin/bash infra/ansible/bootstrap.sh
#
# 若提示缺少解释器，按提示装完再跑一次即可。
# ======================================================================
set -Eeuo pipefail

VENV_DIR="${ANSIBLE_VENV:-/root/.ansible-venv}"
PIP_INDEX="${PIP_INDEX:-https://pypi.tuna.tsinghua.edu.cn/simple}"

log() { echo "[bootstrap] $*"; }
die() { echo "[bootstrap] ERROR: $*" >&2; exit 1; }

# ----------------------------------------------------------------------
# 0. 路径白名单
# ----------------------------------------------------------------------
# 本脚本后面会 rm -rf / mv 这个目录。**白名单比黑名单可靠**：
# 只允许操作 /root 下明确符合命名约定的 venv 目录，其余一律拒绝。
case "$VENV_DIR" in
  /root/.ansible-venv|/root/*ansible*venv) ;;
  *) die "拒绝操作非预期路径：$VENV_DIR（白名单只放行 /root 下的 ansible venv 目录）" ;;
esac

# ----------------------------------------------------------------------
# 1. 先确定解释器，再确定版本区间 —— 这个顺序不能颠倒
# ----------------------------------------------------------------------
choose_interpreter() {
  for candidate in python3.13 python3.12 python3.11; do
    if command -v "$candidate" >/dev/null 2>&1; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done
  return 1
}

constraint_for() {
  case "$1" in
    python3.13|python3.12) printf '%s\n' 'ansible-core>=2.21.1,<2.22' ;;
    python3.11)            printf '%s\n' 'ansible-core>=2.19.11,<2.20' ;;
    *) return 1 ;;
  esac
}

prefix_for() {
  case "$1" in
    python3.13|python3.12) printf '%s\n' '2.21' ;;
    python3.11)            printf '%s\n' '2.19' ;;
    *) return 1 ;;
  esac
}

# 读出已有 venv 里 ansible-core 的版本；读不到就返回空串。
# 有两个来源，顺序不能反：
#   1) 包元数据 —— 最准确
#   2) 解析 ansible --version 首行 —— 回退
# 为什么要回退：`importlib.metadata` 是 Python **3.8** 才进标准库的，
# 而本机旧的 venv 建在 3.6 上 —— 于是首次运行时它读不出来，
# 只能显示 <未知>。功能上没问题（反正要重建），但一个"未知"会让人怀疑
# 是不是检测逻辑坏了。能确定的就显示确定的值。
installed_ansible_version() {
  local venv="$1"
  local version
  version="$("$venv/bin/python" -c \
    'import importlib.metadata as m; print(m.version("ansible-core"))' 2>/dev/null || true)"
  if [ -z "$version" ]; then
    version="$("$venv/bin/ansible" --version 2>/dev/null \
      | head -1 | grep -oE '[0-9]+\.[0-9]+(\.[0-9]+)?' | head -1 || true)"
  fi
  printf '%s\n' "$version"
}

# ----------------------------------------------------------------------
# 🔴 为什么「装 python3.11」这件事必须留在这里，而不做成 playbook
# ----------------------------------------------------------------------
# 这是个**循环依赖**：跑 playbook 需要 Ansible，Ansible 需要较新的解释器。
# 所以「解释器本身」永远只能由引导脚本负责 —— 它不可能被它自己要跑的
# playbook 管理。把它写成 playbook 是逻辑上自洽不了的。
#
# 本机默认的 python3 是 3.6.8（Alinux3 自带），它只能配到 ansible-core 2.11
# （2021 年发布、2022 年即 EOL），所以必须另装一个较新的解释器。
# 下面尝试自动安装 —— 这一步的意义是：
#   **在一台全新的机器上，只要仓库在手，一条命令就能把环境准备好。**
# 不想让它自动装包，可以先手工装好再跑本脚本（届时这段会被跳过）。
if ! INTERPRETER="$(choose_interpreter)"; then
  log "未找到 python3.11 / 3.12 / 3.13 —— 尝试自动安装 python3.11"
  command -v dnf >/dev/null 2>&1 \
    || die "本机没有 dnf，无法自动安装。请手工装好 python3.11/3.12/3.13 后重跑本脚本。"
  dnf install -y python3.11 python3.11-pip \
    || die "dnf 安装 python3.11 失败。请手工安装后重跑。"
  INTERPRETER="$(choose_interpreter)" \
    || die "装完之后仍未找到 python3.11，请人工检查。"
  log "已安装：$("$INTERPRETER" --version 2>&1)"
fi

CONSTRAINT="$(constraint_for "$INTERPRETER")"
EXPECTED_PREFIX="$(prefix_for "$INTERPRETER")"
log "控制端解释器 : $INTERPRETER ($("$INTERPRETER" --version 2>&1))"
log "钉死的区间   : $CONSTRAINT"

# ----------------------------------------------------------------------
# 2. 已有 venv 是否已满足期望？满足即退出（幂等）
# ----------------------------------------------------------------------
if [ -x "$VENV_DIR/bin/ansible" ]; then
  installed="$(installed_ansible_version "$VENV_DIR")"
  case "$installed" in
    "$EXPECTED_PREFIX".*)
      log "已满足期望：ansible-core $installed，无需变更"
      "$VENV_DIR/bin/ansible" --version
      exit 0
      ;;
    *)
      log "现有 ansible-core ${installed:-<未知>} 与期望 $EXPECTED_PREFIX.x 不符 → 需要重建"
      ;;
  esac
fi

# ----------------------------------------------------------------------
# 3. 重建 venv
# ----------------------------------------------------------------------
# 🔴 删除前先证明"我删的确实是我建的那个东西"：
#    venv 一定含 pyvenv.cfg。没有它就说明该路径被挪作他用，
#    宁可报错让人工来看，也不要 rm -rf 一个来历不明的目录。
if [ -d "$VENV_DIR" ]; then
  [ -f "$VENV_DIR/pyvenv.cfg" ] \
    || die "$VENV_DIR 存在但不是 venv（缺少 pyvenv.cfg），拒绝删除。请人工检查该目录。"
  # 改成"改名保留"而不是直接删 —— 万一新版本有问题，一条 mv 就能回滚
  log "把旧 venv 改名为 ${VENV_DIR}.old（不直接删除，留作回滚点）"
  rm -rf "${VENV_DIR}.old"
  mv "$VENV_DIR" "${VENV_DIR}.old"
fi

"$INTERPRETER" -m venv "$VENV_DIR" \
  || die "创建 venv 失败。若报 ensurepip 相关错误，先执行： dnf install -y $INTERPRETER-pip"
log "已创建 venv: $VENV_DIR"

"$VENV_DIR/bin/pip" install -q --upgrade pip -i "$PIP_INDEX"
# 引号很关键：不加引号时 shell 会把 > 当成重定向写进文件，
# 装出来的东西完全不是你要的 —— 这类错误不会报错，只会静默装错。
"$VENV_DIR/bin/pip" install -q "$CONSTRAINT" -i "$PIP_INDEX" \
  || die "安装 $CONSTRAINT 失败。若提示 Requires-Python 不满足，说明区间与解释器不匹配。"

# ----------------------------------------------------------------------
# 4. 自检 —— 这一步不能省
# ----------------------------------------------------------------------
# 既然 pip 会静默降级，唯一可靠的确认方式就是**装完把版本打出来**。
installed="$(installed_ansible_version "$VENV_DIR")"
case "$installed" in
  "$EXPECTED_PREFIX".*)
    log "✅ 版本核对通过：ansible-core $installed（期望 $EXPECTED_PREFIX.x）"
    ;;
  *)
    die "装出来的版本是 $installed，与期望 $EXPECTED_PREFIX.x 不符 —— 请勿继续使用。"
    ;;
esac

"$VENV_DIR/bin/ansible" --version

cat <<'EOF'

[bootstrap] 完成。

playbook 分两类，**目录名就是安全等级**：
    playbooks/audit/   只读 —— 只采集与审计，不改动系统。随时可跑。
    playbooks/apply/   会改系统 —— 必须「先演练、后执行」，且跑第二遍必须 changed=0。

  只读探针（随便跑）：
      cd /root/pm-system/infra/ansible
      /root/.ansible-venv/bin/ansible-playbook -i inventory.ini playbooks/audit/00-baseline.yml
      /root/.ansible-venv/bin/ansible-playbook -i inventory.ini playbooks/audit/02-log-audit.yml
      /root/.ansible-venv/bin/ansible-playbook -i inventory.ini playbooks/audit/03-disk-audit.yml

  会改系统的（照这个三步走）：
      /root/.ansible-venv/bin/ansible-playbook -i inventory.ini playbooks/apply/<名字>.yml --check --diff   # ① 演练
      /root/.ansible-venv/bin/ansible-playbook -i inventory.ini playbooks/apply/<名字>.yml                 # ② 执行
      /root/.ansible-venv/bin/ansible-playbook -i inventory.ini playbooks/apply/<名字>.yml                 # ③ 幂等验收

  编号不连续是**有意的**：数字 = 加入仓库的先后顺序，是稳定引用；
  重新编号会让历史提交、文档、记忆里的路径全部对不上。

EOF
