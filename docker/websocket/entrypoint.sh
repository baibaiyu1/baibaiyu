#!/bin/sh
set -e
# ============================================================
# runner-websocket 容器启动脚本
# 功能：
#   1. 以 root 身份直接启动 Java 进程（WebSocket 服务只做消息中转，不执行用户脚本）
#   2. 通过 -XX:MaxRAMPercentage 让 JVM 自动按容器内存上限比例分配堆
# 注意：脚本必须保持 LF 换行；CRLF 会导致 Linux 容器 shebang 解析失败
# ============================================================

# JVM 堆占容器内存上限的百分比，默认 50%（消息中转服务，堆需求低于 console/runtime）
# -XX:+UseContainerSupport（Java 10+ 默认开启）让 JVM 自动读取 cgroup 限制
HEAP_PERCENTAGE=${WEBSOCKET_JAVA_HEAP_PERCENTAGE:-50.0}

echo "runner-websocket JVM MaxRAMPercentage=${HEAP_PERCENTAGE}%"
exec java \
    -XX:MaxRAMPercentage=${HEAP_PERCENTAGE} \
    -XX:InitialRAMPercentage=${HEAP_PERCENTAGE} \
    -XX:+UseG1GC \
    -XX:+UseContainerSupport \
    -XX:+ExitOnOutOfMemoryError \
    -jar /app/app.jar
