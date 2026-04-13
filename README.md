# DocFusion - 基于大语言模型的文档理解与多源数据融合系统

> 第十七届中国大学生服务外包创新创业大赛 A23 赛题实现

---

## 系统功能

| 模块 | 功能 |
|------|------|
| 📚 知识库管理 | 批量上传 docx / xlsx / md / txt，自动提取全文 |
| 💬 智能对话 | SSE 流式输出，关联知识库文档进行 RAG 问答 |
| 📋 表格自动填写 | 上传模板表格，AI 从知识库提取数据自动填写 |
| ⚙️ 模型配置 | 支持 OpenAI、Ollama 本地、DeepSeek、智谱、通义等 |
| 📥 文件下载 | 输出内容可下载为 TXT/MD，填写好的表格可下载 |

---

## 环境要求

| 依赖 | 版本 |
|------|------|
| JDK  | 17+  |
| Maven | 3.8+ |
| Node.js | 18+ |
| npm  | 9+   |

---

## 快速启动

### 1. 启动后端

```bash
cd backend
mvn spring-boot:run
```

后端启动后访问：http://localhost:8080  
H2 数据库控制台：http://localhost:8080/h2-console（用户名 `sa`，密码留空）

### 2. 启动前端

```bash
cd frontend
npm install
npm run dev
```

前端地址：http://localhost:5173

---

## 首次使用

1. 打开浏览器访问 http://localhost:5173
2. 点击左侧【模型配置】，添加大模型（OpenAI / Ollama 等）
3. 点击左侧【知识库】，批量上传测试文档（docx/xlsx/md/txt）
4. 等待文档处理完成（状态变为"✓ 已处理"）
5. 进入【智能对话】，勾选文档，即可提问
6. 进入【表格填写】，上传模板表格，选择数据来源，点击填写

---

## 支持的大模型

| 服务商 | Provider值 | Base URL |
|--------|-----------|----------|
| OpenAI | openai | https://api.openai.com/v1 |
| Ollama（本地） | ollama | http://localhost:11434 |
| DeepSeek | deepseek | https://api.deepseek.com/v1 |
| Moonshot (Kimi) | moonshot | https://api.moonshot.cn/v1 |
| 智谱 GLM | zhipu | https://open.bigmodel.cn/api/paas/v4 |
| 通义千问 | qianwen | https://dashscope.aliyuncs.com/compatible-mode/v1 |
| 自定义 | custom | 任意 OpenAI 兼容接口 |

### Ollama 本地部署示例
```bash
# 安装 Ollama
curl https://ollama.ai/install.sh | sh

# 拉取模型
ollama pull llama3:8b
# 或中文模型
ollama pull qwen2:7b

# 启动（默认端口 11434）
ollama serve
```

---

## 测试集使用说明（赛题评测）

测试集包含：
- 5 个 ≥500KB 的 docx 文件
- 3 个 ≥15KB 的 md 文件  
- 5 个 ≥500KB 的 xlsx 文件
- 3 个 ≥15KB 的 txt 文件

**评测流程：**
1. 进入【知识库】，将测试集所有文档一次性批量上传
2. 等待全部文档处理完成（状态显示"✓ 已处理"）
3. 进入【表格填写】，上传第 1 个模板表格
4. 勾选所有知识库文档作为数据来源
5. 点击「开始智能填写」，等待完成（≤90秒）
6. 下载填写好的表格文件
7. 重复步骤 3-6，依次完成 5 个模板表格

---

## 项目结构

```
docfusion/
├── backend/                     # Spring Boot 3.2 + Java 17
│   ├── pom.xml
│   └── src/main/java/com/docfusion/
│       ├── DocFusionApplication.java
│       ├── config/              # CORS、目录初始化
│       ├── model/               # 实体类 + Repository
│       ├── service/
│       │   ├── DocumentExtractService.java  # 多格式文本提取
│       │   ├── LlmService.java              # LLM 调用（OpenAI/Ollama）
│       │   └── TableFillService.java        # 表格自动填写
│       └── controller/
│           ├── ChatController.java          # SSE 流式对话
│           ├── KnowledgeBaseController.java # 知识库管理
│           ├── TableFillController.java     # 表格填写
│           ├── LlmConfigController.java     # 模型配置
│           └── FileDownloadController.java  # 文件下载
│
└── frontend/                    # Vue 3 + Element Plus + Vite
    └── src/
        ├── views/
        │   ├── ChatView.vue     # 智能对话（SSE 流式）
        │   ├── KnowledgeBase.vue # 知识库管理
        │   ├── TableFill.vue    # 表格自动填写
        │   └── Settings.vue     # 模型配置
        ├── api/index.js         # Axios API 封装
        ├── store/index.js       # Pinia 状态管理
        └── router/index.js      # Vue Router
```

---

## API 接口说明

| 接口 | 方法 | 说明 |
|------|------|------|
| /api/knowledge/upload | POST | 上传单个文档到知识库 |
| /api/knowledge/upload/batch | POST | 批量上传文档 |
| /api/knowledge/list | GET | 获取知识库文档列表 |
| /api/chat/stream | GET (SSE) | 流式对话 |
| /api/chat/send | POST | 非流式对话 |
| /api/table-fill/fill | POST | 表格自动填写 |
| /api/llm-config/save | POST | 保存模型配置 |
| /api/file/download/output/{id} | GET | 下载输出文件 |

---

## 技术栈

**后端：** Spring Boot 3.2 · JPA + H2 · Apache POI · OkHttp · Apache Tika  
**前端：** Vue 3 · Vite · Element Plus · Pinia · marked.js  
**AI：** OpenAI API 兼容协议 · Ollama 本地推理
