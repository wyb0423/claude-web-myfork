#!/bin/bash

# claude-web 停止脚本

PID_FILE="/tmp/claude-web.pid"
LOG_FILE="/tmp/claude-web.log"

# 查找 PID
if [ -f "$PID_FILE" ]; then
    PID=$(cat "$PID_FILE")
    if ps -p "$PID" > /dev/null 2>&1; then
        echo "正在停止 claude-web (PID: $PID)..."
        kill "$PID"

        # 等待进程停止
        for i in {1..10}; do
            if ! ps -p "$PID" > /dev/null 2>&1; then
                echo "服务已成功停止"
                rm -f "$PID_FILE"
                exit 0
            fi
            sleep 1
        done

        # 若仍在运行则强制终止
        echo "正在强制停止服务..."
        kill -9 "$PID" 2>/dev/null
        rm -f "$PID_FILE"
        echo "服务已停止"
    else
        echo "服务未运行（PID 文件已过期）"
        rm -f "$PID_FILE"
    fi
else
    # 尝试通过进程名查找
    PID=$(ps aux | grep "claude-web" | grep -v grep | awk '{print $2}' | head -1)
    if [ -n "$PID" ]; then
        echo "正在停止 claude-web (PID: $PID)..."
        kill "$PID"
        sleep 2
        if ps -p "$PID" > /dev/null 2>&1; then
            kill -9 "$PID" 2>/dev/null
        fi
        echo "服务已停止"
    else
        echo "服务未运行"
    fi
fi
