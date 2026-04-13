@echo off
chcp 65001 >nul
echo ======================================
echo   DocFusion 启动脚本 (Windows)
echo ======================================

echo [1/3] 安装前端依赖...
cd frontend
call npm install --silent
cd ..

echo [2/3] 启动后端 (端口 8080)...
cd backend
start "DocFusion Backend" cmd /k "mvn spring-boot:run"
cd ..

echo 等待后端启动 (8秒)...
timeout /t 8 /nobreak >nul

echo [3/3] 启动前端 (端口 5173)...
cd frontend
start "DocFusion Frontend" cmd /k "npm run dev"
cd ..

echo.
echo ======================================
echo   启动完成！
echo   前端地址：http://localhost:5173
echo   后端地址：http://localhost:8080
echo   关闭两个命令行窗口即可停止服务
echo ======================================
pause
