#!/bin/bash
set -e

echo "======================================"
echo "  DocFusion 启动脚本"
echo "======================================"

# Check Java
if ! command -v java &> /dev/null; then
    echo "[ERROR] 未找到 Java，请安装 JDK 17+"
    exit 1
fi

JAVA_VERSION=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}' | cut -d'.' -f1)
if [ "$JAVA_VERSION" -lt 17 ] 2>/dev/null; then
    echo "[WARN] Java 版本过低，建议使用 JDK 17+"
fi

# Check Node
if ! command -v node &> /dev/null; then
    echo "[ERROR] 未找到 Node.js，请安装 Node.js 18+"
    exit 1
fi

# Check Maven
if ! command -v mvn &> /dev/null; then
    echo "[ERROR] 未找到 Maven，请安装 Maven 3.8+"
    exit 1
fi

echo ""
echo "[1/3] 安装前端依赖..."
cd frontend
npm install --silent
cd ..

echo "[2/3] 启动后端 (端口 8080)..."
cd backend
mvn spring-boot:run -q &
BACKEND_PID=$!
cd ..

echo "[3/3] 等待后端启动..."
sleep 8

echo "启动前端开发服务器 (端口 5173)..."
cd frontend
npm run dev &
FRONTEND_PID=$!
cd ..

echo ""
echo "======================================"
echo "  ✅ 启动完成！"
echo "  前端地址：http://localhost:5173"
echo "  后端地址：http://localhost:8080"
echo "  按 Ctrl+C 停止所有服务"
echo "======================================"

trap "kill $BACKEND_PID $FRONTEND_PID 2>/dev/null; echo '已停止所有服务'" INT TERM

wait
