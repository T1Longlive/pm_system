# ======================================================================
# pm-system 前端镜像（多阶段构建）
# ----------------------------------------------------------------------
# 阶段 1 builder：Node 构建出 dist/
# 阶段 2 runtime：nginx 托管静态文件 + 反向代理 /api 到后端
#
# 🔴 基础镜像为什么不用 docker.io：Gitee 云端构建无法访问 Docker Hub
#    （i/o timeout），故基础镜像已镜像到本项目 ACR，见
#    scripts/mirror-base-images.sh
# ======================================================================

# ---------- 阶段 1：构建 ----------
FROM crpi-jzqyelh9p5zb0dmu.cn-chengdu.personal.cr.aliyuncs.com/t1longlive/node:20-alpine AS builder

WORKDIR /build

# 先只拷依赖清单，命中缓存层
COPY frontend/package.json frontend/package-lock.json* ./

# 用国内镜像加速（云端构建环境必备，否则 npm 源经常超时）
RUN npm install --registry=https://registry.npmmirror.com

# 再拷源码
COPY frontend/ ./

# vue-tsc 类型检查 + vite 构建（package.json 的 build 脚本）
RUN npm run build

# ---------- 阶段 2：运行 ----------
FROM crpi-jzqyelh9p5zb0dmu.cn-chengdu.personal.cr.aliyuncs.com/t1longlive/nginx:alpine AS runtime

ENV TZ=Asia/Shanghai

# 用项目自己的 nginx 配置替换默认配置
COPY frontend/nginx.conf /etc/nginx/conf.d/default.conf

# 只取构建产物
COPY --from=builder /build/dist /usr/share/nginx/html

EXPOSE 80
