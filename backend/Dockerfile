# ======================================================================
# pm-system 后端镜像（多阶段构建）
# ----------------------------------------------------------------------
# 阶段 1 builder：用 Maven + JDK17 编译打包
# 阶段 2 runtime：只带 JRE + jar，镜像小、攻击面小
#
# 关键点：Maven settings.xml 通过 build context 传入（见 docker-compose
# 的 build.args / Dockerfile 的 COPY），走阿里云镜像加速依赖下载。
# ======================================================================

# ---------- 阶段 1：构建 ----------
FROM maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /build

# 先只拷 pom，利用 Docker 层缓存：pom 没变时下面的依赖下载层直接命中缓存
COPY backend/pom.xml ./pom.xml
# 项目根的 settings.xml 提供阿里云镜像，加速且稳定（云端构建环境尤其需要）
COPY settings.xml ./settings.xml

# 预下载全部依赖到 /root/.m2 —— 独立成层，pom 不变则永远不重复下载
RUN mvn -s ./settings.xml -B dependency:go-offline

# 再拷源码（源码变动只让最后一层失效，依赖层仍命中缓存）
COPY backend/src ./src

# 编译打包，跳过测试（测试在 CI 的独立 job 里跑，见 .workflow 流水线）
RUN mvn -s ./settings.xml -B clean package -DskipTests

# ---------- 阶段 2：运行 ----------
FROM eclipse-temurin:17-jre AS runtime

# 时区设为东八区，否则日志时间戳会是 UTC
ENV TZ=Asia/Shanghai

WORKDIR /app

# 从构建阶段只取最终的 jar
COPY --from=builder /build/target/*.jar app.jar

# 附件上传目录（compose 里挂具名卷到这里）
RUN mkdir -p /pm-upload

EXPOSE 8080

# 用 exec 形式启动，确保 JVM 能收到 SIGTERM 做优雅停机
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
