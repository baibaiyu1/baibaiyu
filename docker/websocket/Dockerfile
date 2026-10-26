ARG MAVEN_IMAGE=maven:3.9-eclipse-temurin-21
ARG JRE_IMAGE=eclipse-temurin:21-jre
# ============================================================
# 阶段一：Maven 构建
# ============================================================
FROM ${MAVEN_IMAGE} AS build

WORKDIR /build

# 复制 Maven 私有仓库配置
COPY docker/maven/settings.xml /root/.m2/settings.xml

# 复制源码并构建
COPY . .
RUN mvn package -pl GMD-FAQ-RUNNER-WEBSOCKET -am -DskipTests -q

# ============================================================
# 阶段二：运行镜像
# ============================================================
FROM ${JRE_IMAGE}

WORKDIR /app

# 复制构建产物
COPY --from=build /build/GMD-FAQ-RUNNER-WEBSOCKET/target/GMD-FAQ-RUNNER-WEBSOCKET-*.jar app.jar

EXPOSE 3064

ENV TZ=Asia/Shanghai
# ---------- 启动脚本 ----------
# 复制 entrypoint.sh（动态 JVM 堆计算 + root 直接启动）
COPY docker/websocket/entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh

# 以 root 启动（WebSocket 服务不执行用户脚本，只做消息中转）
ENTRYPOINT ["/app/entrypoint.sh"]
