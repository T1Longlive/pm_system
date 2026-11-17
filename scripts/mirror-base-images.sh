#!/usr/bin/env bash
# ======================================================================
# 把 4 个基础镜像转存到本项目自己的 ACR
# ----------------------------------------------------------------------
# 【为什么需要】
#   Dockerfile 的 FROM 指向本项目 ACR，而不是 Docker Hub —— 避免构建时
#   依赖 Docker Hub 的可用性，同时把基础镜像来源固定下来，供应链可控。
#   这些基础镜像必须先转存进 ACR，构建才能拉取。
#
# 【首选方式】
#   GitHub Actions 的 .github/workflows/refresh-base-images.yml 每月 1 号
#   自动转存（也可手动 workflow_dispatch）。本脚本是手工兜底，
#   只在当前机器能访问 Docker Hub 时使用。
#
# 【什么时候执行】
#   只需在基础镜像的版本发生变化或需要拉取上游安全更新时重跑。
#   日常部署不需要。
#
# 【前置】
#   docker login crpi-jzqyelh9p5zb0dmu.cn-chengdu.personal.cr.aliyuncs.com
#   且 ACR 中已建好命名空间 t1longlive
# ======================================================================
set -Eeuo pipefail

ACR="crpi-jzqyelh9p5zb0dmu.cn-chengdu.personal.cr.aliyuncs.com"
NS="t1longlive"

# 源镜像（Docker Hub 官方镜像）→ 转存后保持同名同 tag，
# 这样两个 Dockerfile 里的 FROM 只是多了一段仓库前缀。
IMAGES=(
  "maven:3.9-eclipse-temurin-17"
  "eclipse-temurin:17-jre"
  "node:20-alpine"
  "nginx:alpine"
)

echo ">> 目标仓库: ${ACR}/${NS}"
echo ">> 若下方 push 报无权限，请先执行: docker login ${ACR}"
echo

fail=0
for img in "${IMAGES[@]}"; do
  target="${ACR}/${NS}/${img}"
  echo "--------------------------------------------------"
  echo ">> ${img}"
  echo "   -> ${target}"

  if docker pull "$img"; then
    docker tag "$img" "$target"
    if docker push "$target"; then
      echo "   ✓ 完成"
    else
      echo "   ✗ push 失败"
      fail=1
    fi
  else
    echo "   ✗ pull 失败（服务器能否访问该镜像？检查 daemon.json 的 registry-mirrors）"
    fail=1
  fi
  echo
done

echo "=================================================="
if [ "$fail" -eq 0 ]; then
  echo "✓ 全部 4 个基础镜像已转存到 ACR"
  echo
  echo "下一步：两个 Dockerfile 的 FROM 已指向 ${ACR}/${NS}/...，"
  echo "        可以回 GitHub Actions 手动重跑构建流水线了。"
else
  echo "✗ 有镜像处理失败，请按上面提示排查后重跑本脚本"
  exit 1
fi
