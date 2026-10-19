#!/bin/bash

# phil-demo 启动/重启脚本

APP_NAME="phil-demo"
JAR_FILE="phil-demo.jar"
LOG_FILE="app.log"
PID_FILE="app.pid"
# 指定 Java 21 路径，不影响系统默认 Java 8
JAVA_HOME="/usr/lib/jvm/java-21-openjdk-21.0.0.35-1.rolling.oe2203.aarch64"
JAVA_CMD="$JAVA_HOME/bin/java"

# 检查是否已经运行
check_running() {
    if [ -f "$PID_FILE" ]; then
        PID=$(cat "$PID_FILE")
        if ps -p $PID > /dev/null 2>&1; then
            return 0  # 正在运行
        fi
    fi
    return 1  # 未运行
}

# 停止服务
stop_service() {
    if [ -f "$PID_FILE" ]; then
        PID=$(cat "$PID_FILE")
        echo "停止 $APP_NAME (PID: $PID)..."
        kill $PID
        sleep 3

        # 如果还在运行，强制停止
        if ps -p $PID > /dev/null 2>&1; then
            echo "强制停止..."
            kill -9 $PID
        fi

        rm -f $PID_FILE
        echo "已停止"
    fi
}

# 启动服务
start_service() {
    echo "启动 $APP_NAME..."
    echo "使用 Java: $JAVA_CMD"
    $JAVA_CMD -version

    # 后台运行
    nohup $JAVA_CMD -jar $JAR_FILE > $LOG_FILE 2>&1 &

    # 保存 PID
    echo $! > $PID_FILE

    sleep 2

    # 检查是否启动成功
    if ps -p $(cat $PID_FILE) > /dev/null 2>&1; then
        echo "启动成功！PID: $(cat $PID_FILE)"
        echo "日志文件: $LOG_FILE"
        echo "访问地址: http://127.0.0.1:3010"
    else
        echo "启动失败，请查看日志: $LOG_FILE"
        exit 1
    fi
}

# 主逻辑
main() {
    if check_running; then
        echo "$APP_NAME 正在运行，执行重启..."
        stop_service
    fi

    start_service
}

# 执行
main