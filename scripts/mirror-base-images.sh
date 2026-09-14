#!/usr/bin/env bash
# ======================================================================
# 把 4 个基础镜像转存到本项目自己的 ACR
# ----------------------------------------------------------------------
# 【为什么需要】
#   Gitee 云端构建（build@docker / buildkit）**无法访问 Docker Hub**，
#   Dockerfile 里直接写 FROM maven:... 会报：
#     dial tcp 128.242.245.253:80: i/o timeout
#   把这些基础镜像转存到本项目 ACR 后，云端就能拉取 —— 而 ACR 的
#   可达性是已经确定的（我们本来就在往它推镜像）。
#   顺带好处：基础镜像来源被固定下来，供应链可控。
#
# 【在哪执行】
#   在**阿里云服务器**上执行（服务器的 Docker 已配镜像加速，能拉到
#   Docker Hub 的镜像）。
#
# 【什么时候执行】
#   只需在基础镜像的版本发生变化时重跑。日常部署不需要。
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
  echo "        可以回 Gitee 重新运行流水线了。"
else
  echo "✗ 有镜像处理失败，请按上面提示排查后重跑本脚本"
  exit 1
fi
