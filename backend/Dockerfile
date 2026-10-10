# ======================================================================
# pm-system 后端镜像（多阶段构建）
# ----------------------------------------------------------------------
# 阶段 1 builder：用 Maven + JDK17 编译打包
# 阶段 2 runtime：只带 JRE + jar，镜像小、攻击面小
#
# 🔴 基础镜像为什么不用 docker.io：
#    Gitee 云端构建（build@docker / buildkit）**无法访问 Docker Hub**
#    （registry-1.docker.io 直连 i/o timeout）。
#    因此这些基础镜像已镜像到本项目自己的 ACR，
#    既保证云端可拉取，也顺带把基础镜像的来源固定下来（供应链可控）。
#    镜像到 ACR 的命令见 scripts/mirror-base-images.sh
#
# 关键点：Maven settings.xml 通过 build context 传入，走阿里云镜像加速依赖下载。
# ======================================================================

# ---------- 阶段 1：构建 ----------
FROM crpi-jzqyelh9p5zb0dmu.cn-chengdu.personal.cr.aliyuncs.com/t1longlive/maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /build

# 先只拷 pom，利用 Docker 层缓存：pom 没变时下面的依赖下载层直接命中缓存
COPY backend/pom.xml ./pom.xml
# 项目根的 settings.xml 提供阿里云镜像，加速且稳定（云端构建环境尤其需要）
COPY settings.xml ./settings.xml

# 预下载全部依赖到 /root/.m2 —— 独立成层，pom 不变则永远不重复下载
RUN mvn -s ./settings.xml -B dependency:go-offline

# 再拷源码（源码变动只让最后一层失效，依赖层仍命中缓存）
COPY backend/src ./src

# 编译打包并**运行单元测试**：
#   测试失败 → 本层失败 → 镜像构建失败 → 部署被流水线的 dependsOn 挡住。
#   也就是说"测试门禁"直接复用已有的构建门禁，不需要新增流水线步骤。
#   （原命令带 -DskipTests，注释还写着"测试在 CI 的独立 job 里跑" —— 那个 job 从来不存在。）
#
# 🔴 前提：测试必须**不依赖 Spring 上下文 / 真实 MySQL / Redis**，
#    否则云端构建机会失败。当前测试集合：
#      · RequirementStageServiceImplTest —— 需求阶段派生（纯函数，核心业务规则）
#      · JwtUtilTest                     —— JWT 签发/校验/防篡改（安全性质）
#    原来的 PmApplicationTests 要真实数据库，已 @Disabled（见类注释）。
RUN mvn -s ./settings.xml -B clean package

# ---------- 阶段 2：运行 ----------
FROM crpi-jzqyelh9p5zb0dmu.cn-chengdu.personal.cr.aliyuncs.com/t1longlive/eclipse-temurin:17-jre AS runtime

# 时区设为东八区，否则日志时间戳会是 UTC
ENV TZ=Asia/Shanghai

# ---------- 清掉基础镜像里两个会持续腐化的点 ----------
# 【背景】基础镜像是「快照」，上游修了 CVE 我们不会自动拿到。
# 2026-10-09 ~ 10-10 连续两次被 Trivy 硬门禁拦下，剩下的 6 条**全部来自这一层**，
# 与业务代码无关（应用依赖那 10 条 Jackson 已在 pom 里覆盖解决）。
#
# ① openssl 家族（libssl3t64 / openssl / openssl-provider-legacy）
#    镜像里带的是 3.5.5-1ubuntu3.5，ubuntu 已发布 3.5.5-1ubuntu3.6
#    （CVE-2026-84782，DTLS 握手重传导致信息泄露）。
#    ⇒ 用 apt-get upgrade 拉取 ubuntu 安全更新。
#    ⚠️ 代价：构建不再逐字节可复现（不同时间构建拿到的补丁版本不同）。
#       这里有意接受 —— 安全补丁的时效性优先，而且基础镜像本身的 tag 也是浮动的，
#       真要严格复现得锁 digest，那是另一件事。
#
# ② /usr/bin/pebble 的 Go stdlib
#    pebble 是 Let's Encrypt 的 ACME **测试服务器**，随 Temurin 镜像附带，
#    本应用完全不用它。它由上游用固定 Go 版本编译，我们无法单独升级 ——
#    留着就只能一直等 Temurin 重建镜像，同时白背一个用不到的攻击面。
#    ⇒ 直接删除。顺带缩小镜像体积。
#
# 【复查条件】若将来这里又报出新 CVE：
#   · 先确认 ① 的 upgrade 是否仍在执行（构建日志里搜 "apt-get upgrade"）；
#   · 若报的是 pebble 且路径变了，用下面命令核对实际路径：
#       docker run --rm --entrypoint sh <image> -c 'command -v pebble'
#   · 上游 Temurin 若某天不再打包 pebble，这段 rm 会静默变成空操作，无害。
RUN apt-get update \
 && DEBIAN_FRONTEND=noninteractive apt-get upgrade -y \
 && rm -f /usr/bin/pebble \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# 从构建阶段只取最终的 jar
COPY --from=builder /build/target/*.jar app.jar

# 附件上传目录（compose 里挂具名卷到这里）
RUN mkdir -p /pm-upload

EXPOSE 8080

# 用 exec 形式启动，确保 JVM 能收到 SIGTERM 做优雅停机
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
