#!/bin/bash

# claude-web 重启脚本

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "正在重启 claude-web..."
echo ""

# 停止服务
"$SCRIPT_DIR/stop.sh"
echo ""

# 等待片刻
sleep 1

# 启动服务
"$SCRIPT_DIR/start.sh"
