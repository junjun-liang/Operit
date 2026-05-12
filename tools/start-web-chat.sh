#!/bin/bash

# Web-Chat 快速启动脚本
# 用于通过 USB 连接启动 web-chat 开发服务器

echo "🚀 Web-Chat 快速启动"
echo "===================="
echo ""

# 1. 检查 ADB 设备
echo "📱 检查 ADB 设备连接..."
ADB_OUTPUT=$(adb devices 2>&1)
if echo "$ADB_OUTPUT" | grep -q "device$"; then
    DEVICE_ID=$(echo "$ADB_OUTPUT" | grep "device$" | head -1 | awk '{print $1}')
    echo "✅ 设备已连接：$DEVICE_ID"
else
    echo "❌ 未找到 ADB 设备"
    echo "请确保："
    echo "  1. 手机通过 USB 连接电脑"
    echo "  2. 已开启 USB 调试模式"
    exit 1
fi

# 2. 设置 ADB 转发
echo ""
echo "🔌 设置 ADB 端口转发..."
adb forward --remove-all 2>/dev/null
FORWARD_OUTPUT=$(adb forward tcp:8094 tcp:8094 2>&1)
if [ $? -eq 0 ]; then
    echo "✅ ADB 转发已设置：tcp:8094 → tcp:8094"
else
    echo "⚠️  ADB 转发可能已存在：$FORWARD_OUTPUT"
fi

# 3. 验证转发
echo ""
echo "🔍 验证连接..."
HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" http://127.0.0.1:8094/api/health 2>/dev/null)
if [ "$HTTP_STATUS" = "401" ]; then
    echo "✅ 手机服务可访问（HTTP 401 - 需要认证，正常）"
elif [ "$HTTP_STATUS" = "000" ]; then
    echo "❌ 无法连接到手机服务"
    echo "请确保手机上 External HTTP 服务已启用"
    exit 1
else
    echo "⚠️  意外状态码：$HTTP_STATUS"
fi

# 4. 启动 Vite 服务器
echo ""
echo "🌐 启动 Vite 开发服务器..."
echo "===================="
cd "$(dirname "$0")/web-chat" || exit 1
npm run dev
