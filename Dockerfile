# syntax=docker/dockerfile:1
# =============================================================================
#  exam-tracker —— 多阶段构建
#  ---------------------------------------------------------------------------
#  为什么用多阶段：构建需要 Maven + 完整 JDK + 本地依赖仓库（几百 MB），
#  而运行只需要一个 JRE。把产物复制进干净的基础镜像后，
#  最终镜像从 ~600 MB 降到 ~180 MB，更重要的是**构建工具链不会进入生产镜像** ——
#  少一份东西就少一个攻击面。
#
#  构建：
#    docker build -t exam-tracker:1.0.0 .
#  运行（需要先有一个可连的 MySQL，且已执行 sql/schema.sql）：
#    docker run --rm -p 127.0.0.1:8090:8090 \
#      -e DB_HOST=host.docker.internal -e DB_PORT=3308 \
#      -e DB_NAME=exam_tracker -e DB_USERNAME=dev -e DB_PASSWORD=dev123456 \
#      -e JWT_SECRET="$(openssl rand -base64 48)" \
#      exam-tracker:1.0.0
#
#  ⚠️ 本文件**尚未在真实 Docker 上构建验证过**（开发机 Docker 起不来，
#     原因见工作区 docs/runtime.md）。语法与做法是按官方文档写的，
#     但「能跑」这件事请以你自己构建一次的结果为准。见 README「Docker」一节。
# =============================================================================

# -----------------------------------------------------------------------------
#  阶段 1：构建
# -----------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /build

# 先只复制 pom.xml 并把依赖预热到本地仓库。
# 只要 pom 没变，这一层就命中缓存 —— 改 Java 代码不会触发几百 MB 的依赖重下。
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src

# 镜像构建里**不跑测试**：测试需要数据库，属于 CI 的职责，
# 不该让每一次镜像构建都依赖一个外部服务。
# （测试在本地和 CI 用 `./mvnw test` 跑，见 README「测试与验证」）
RUN mvn -B -q -DskipTests package

# -----------------------------------------------------------------------------
#  阶段 2：运行
# -----------------------------------------------------------------------------
FROM eclipse-temurin:17-jre-alpine

# 时区：应用和数据库都按 Asia/Shanghai，容器默认是 UTC，不钉的话日志时间会差 8 小时。
# tzdata 装完就删，不让它留在最终镜像里。
RUN apk add --no-cache tzdata \
    && cp /usr/share/zoneinfo/Asia/Shanghai /etc/localtime \
    && echo "Asia/Shanghai" > /etc/timezone \
    && apk del tzdata

# 不用 root 跑应用进程
RUN addgroup -S app && adduser -S -G app app

WORKDIR /app
COPY --from=build --chown=app:app /build/target/exam-tracker-1.0.0.jar app.jar

USER app

EXPOSE 8090

# ★ 容器内必须绑 0.0.0.0，否则 -p 端口映射进不来。
#   （应用默认是 127.0.0.1，那是给本地开发用的，见 application.yml 的说明。）
ENV SERVER_ADDRESS=0.0.0.0

# -XX:MaxRAMPercentage 而不是写死 -Xmx：这样 `docker run --memory=512m` 时
# JVM 会自动跟着调整堆大小，不用改镜像。
# -Dfile.encoding=UTF-8：JDK 17 在非 UTF-8 环境下默认编码可能不是 UTF-8，
# 会让中文日志变成乱码（而且不报错，只是毁掉排错信息）。
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC -Dfile.encoding=UTF-8"

# ★ 用 /api/health 而不是 /actuator/health：
#   前者不查任何外部依赖，只回答「进程还在处理请求吗」。
#   数据库短暂抖动时不应该让编排系统把容器判死并重启 —— 那会引发雪崩。
#   要「依赖是否都正常」的语义请用 /actuator/health，两者用途不同。
HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=3 \
    CMD wget -qO- http://127.0.0.1:8090/api/health || exit 1

# 用 exec 形式启动，让 java 成为 PID 1 —— 这样 docker stop 发的 SIGTERM
# 才会直接到 JVM，配合 application.yml 里的 `server.shutdown: graceful`
# 才能把手上正在处理的请求做完再退出。
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
