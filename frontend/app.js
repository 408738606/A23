// ==================== 全局状态 ====================
let currentFile = null;
let managedFiles = JSON.parse(localStorage.getItem('managedFiles') || '[]');
let requirementFile = null;
let templateFile = null;
let currentExportMethod = null;

// ==================== 标签页切换 ====================
function switchTab(tabName) {
    document.querySelectorAll('.nav-item').forEach(item => {
        item.classList.remove('active');
    });
    document.querySelector(`[data-tab="${tabName}"]`).classList.add('active');

    document.querySelectorAll('.tab-content').forEach(content => {
        content.classList.remove('active');
    });
    document.getElementById(tabName + 'Tab').classList.add('active');

    if (tabName === 'files') {
        renderFileList();
    } else if (tabName === 'export') {
        updateMatchedFiles();
    }
}

// ==================== 对话功能 ====================
function triggerUpload() {
    document.getElementById('fileInput').click();
}

function handleFileSelect(event) {
    const file = event.target.files[0];
    if (file) {
        currentFile = file;
        updateFileStatus(file);
        addMessage(`已上传文件：${file.name}`, 'user');
        addToFileManage(file);
        
        setTimeout(() => {
            addMessage(`收到文件 "${file.name}"，请告诉我您想如何处理这个文档？比如：\n• 调整文档格式\n• 提取关键内容\n• 转换文件格式\n• 压缩文档大小`, 'ai');
        }, 500);
    }
}

function updateFileStatus(file) {
    const statusEl = document.getElementById('fileStatus');
    const size = (file.size / 1024).toFixed(1);
    statusEl.innerHTML = `
        <div class="file-card">
            <span class="file-icon">📄</span>
            <div class="file-info">
                <div class="file-name">${file.name}</div>
                <div class="file-size">${size} KB</div>
            </div>
        </div>
    `;
    statusEl.classList.add('has-file');
}

async function sendMessage() {
    const input = document.getElementById('messageInput');
    const message = input.value.trim();
    
    if (!message) return;
    
    addMessage(message, 'user');
    input.value = '';
    showLoading();
    
    try {
        if (currentFile) {
            const formData = new FormData();
            formData.append('file', currentFile);
            formData.append('instruction', message);
            
            const response = await fetch('http://localhost:3000/api/process-document', {
                method: 'POST',
                body: formData
            });
            
            const result = await response.json();
            
            if (result.success) {
                addMessage(result.message, 'ai');
                if (result.downloadUrl) {
                    setTimeout(() => addDownloadCard(result.downloadUrl, result.fileName), 500);
                }
            } else {
                addMessage('抱歉，处理文档时出错了：' + result.error, 'ai');
            }
        } else {
            setTimeout(() => {
                addMessage('请先上传文件，我才能帮您处理文档哦！点击右上角"上传文件"按钮即可。', 'ai');
            }, 500);
        }
    } catch (error) {
        addMessage('网络连接失败，请检查后端服务是否启动（运行 npm start）。', 'ai');
    } finally {
        hideLoading();
    }
}

function addMessage(text, type) {
    const container = document.getElementById('chatContainer');
    const messageDiv = document.createElement('div');
    messageDiv.className = `message ${type}-message`;
    
    const avatar = type === 'ai' ? '🤖' : '👤';
    const content = text.replace(/\n/g, '<br>');
    
    messageDiv.innerHTML = `
        <div class="avatar">${avatar}</div>
        <div class="message-content">${content}</div>
    `;
    
    container.appendChild(messageDiv);
    container.scrollTop = container.scrollHeight;
}

function addDownloadCard(url, fileName) {
    const container = document.getElementById('chatContainer');
    const cardDiv = document.createElement('div');
    cardDiv.className = 'message ai-message';
    
    cardDiv.innerHTML = `
        <div class="avatar">📥</div>
        <div class="message-content">
            <p>文档已处理完成！</p>
            <a href="${url}" download="${fileName}" style="
                display: inline-block;
                margin-top: 8px;
                padding: 8px 16px;
                background: #52c41a;
                color: white;
                text-decoration: none;
                border-radius: 4px;
                font-size: 14px;
            ">下载处理后的文件</a>
        </div>
    `;
    
    container.appendChild(cardDiv);
    container.scrollTop = container.scrollHeight;
}

function handleKeyPress(event) {
    if (event.key === 'Enter') {
        sendMessage();
    }
}

function startNewChat() {
    currentFile = null;
    document.getElementById('chatContainer').innerHTML = `
        <div class="message ai-message">
            <div class="avatar">🤖</div>
            <div class="message-content">
                <p>你好！我是文档智能处理助手。请上传文件并告诉我你想要对文档进行的操作，例如："调整文档格式"、"提取文档内容"等。</p>
            </div>
        </div>
    `;
    document.getElementById('fileStatus').textContent = '未上传文件';
    document.getElementById('fileStatus').classList.remove('has-file');
    document.getElementById('fileInput').value = '';
    switchTab('chat');
}

// ==================== 文件管理功能 ====================
function triggerFileManageUpload() {
    document.getElementById('fileManageInput').click();
}

function handleFileManageUpload(event) {
    const files = Array.from(event.target.files);
    files.forEach(file => addToFileManage(file));
    renderFileList();
    updateMatchedFiles();
}

function addToFileManage(file) {
    const exists = managedFiles.some(f => f.name === file.name && f.size === file.size);
    if (exists) return;

    const fileData = {
        id: Date.now() + Math.random().toString(36).substr(2, 9),
        name: file.name,
        size: file.size,
        type: file.type,
        lastModified: file.lastModified,
        addedAt: new Date().toISOString(),
        _fileObj: file
    };

    managedFiles.push(fileData);
    saveManagedFiles();
}

function removeFromFileManage(id) {
    managedFiles = managedFiles.filter(f => f.id !== id);
    saveManagedFiles();
    renderFileList();
    updateMatchedFiles();
}

function saveManagedFiles() {
    const metadata = managedFiles.map(({ _fileObj, ...rest }) => rest);
    localStorage.setItem('managedFiles', JSON.stringify(metadata));
}

function renderFileList() {
    const container = document.getElementById('fileList');
    const totalEl = document.getElementById('totalFiles');
    const totalSizeEl = document.getElementById('totalSize');

    if (totalEl) totalEl.textContent = managedFiles.length;
    
    const totalSize = managedFiles.reduce((sum, f) => sum + f.size, 0);
    if (totalSizeEl) totalSizeEl.textContent = formatFileSize(totalSize);

    if (managedFiles.length === 0) {
        container.innerHTML = '<div class="empty-state">暂无文件，请点击"导入文件"添加</div>';
        return;
    }

    container.innerHTML = managedFiles.map(file => `
        <div class="file-item" data-id="${file.id}">
            <span class="file-item-icon">${getFileIcon(file.name)}</span>
            <div class="file-item-info">
                <div class="file-item-name">${file.name}</div>
                <div class="file-item-meta">${formatFileSize(file.size)} · ${new Date(file.addedAt).toLocaleDateString()}</div>
            </div>
            <div class="file-item-actions">
                <button class="file-item-btn" onclick="useFileInChat('${file.id}')">使用</button>
                <button class="file-item-btn delete" onclick="removeFromFileManage('${file.id}')">删除</button>
            </div>
        </div>
    `).join('');
}

function searchFiles() {
    const keyword = document.getElementById('fileSearch').value.toLowerCase();
    const items = document.querySelectorAll('.file-item');
    
    items.forEach(item => {
        const name = item.querySelector('.file-item-name').textContent.toLowerCase();
        item.style.display = name.includes(keyword) ? 'flex' : 'none';
    });
}

function useFileInChat(id) {
    const file = managedFiles.find(f => f.id === id);
    if (file && file._fileObj) {
        currentFile = file._fileObj;
        updateFileStatus(file._fileObj);
        switchTab('chat');
        addMessage(`已从文件管理加载：${file.name}`, 'user');
        setTimeout(() => {
            addMessage(`文件 "${file.name}" 已加载，请告诉我您想如何处理？`, 'ai');
        }, 300);
    }
}

function getFileIcon(filename) {
    const ext = filename.split('.').pop().toLowerCase();
    const icons = {
        'doc': '📝', 'docx': '📝',
        'pdf': '📕',
        'txt': '📄', 'md': '📄',
        'xls': '📊', 'xlsx': '📊', 'csv': '📊'
    };
    return icons[ext] || '📄';
}

function formatFileSize(bytes) {
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
    return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
}

// ==================== 导出数据功能 ====================

function selectExportMethod(method) {
    currentExportMethod = method;
    document.getElementById('localExport').checked = (method === 'local');
    document.getElementById('onlineExport').checked = (method === 'online');
    checkExportReady();
}

function handleTemplateUpload(event) {
    const file = event.target.files[0];
    if (file) {
        const validExtensions = ['.docx', '.doc', '.txt', '.md', '.pdf'];
        const ext = '.' + file.name.split('.').pop().toLowerCase();
        
        if (!validExtensions.includes(ext)) {
            alert('请上传支持的文件格式：.docx, .doc, .txt, .md, .pdf');
            return;
        }
        
        templateFile = file;
        const infoDiv = document.getElementById('templateFileInfo');
        
        let icon = '📋';
        if (ext === '.pdf') icon = '📕';
        else if (ext === '.txt' || ext === '.md') icon = '📄';
        else if (ext === '.doc' || ext === '.docx') icon = '📝';
        
        infoDiv.innerHTML = `
            <div class="uploaded-file">
                <span>${icon} ${file.name} (${formatFileSize(file.size)})</span>
                <button class="remove-file-btn" onclick="removeTemplateFile()">✕</button>
            </div>
        `;
        infoDiv.style.display = 'block';
        updateMatchedFiles();
        checkExportReady();
    }
}

function removeTemplateFile() {
    templateFile = null;
    document.getElementById('templateFileInfo').style.display = 'none';
    document.getElementById('templateUploadInput').value = '';
    updateMatchedFiles();
    checkExportReady();
}

function handleRequirementUpload(event) {
    const file = event.target.files[0];
    if (file) {
        requirementFile = file;
        const infoDiv = document.getElementById('requirementFileInfo');
        infoDiv.innerHTML = `
            <div class="uploaded-file">
                <span>📝 ${file.name} (${formatFileSize(file.size)})</span>
                <button class="remove-file-btn" onclick="removeRequirementFile()">✕</button>
            </div>
        `;
        infoDiv.style.display = 'block';
        checkExportReady();
    }
}

function removeRequirementFile() {
    requirementFile = null;
    document.getElementById('requirementFileInfo').style.display = 'none';
    document.getElementById('requirementUploadInput').value = '';
    checkExportReady();
}

function updateMatchedFiles() {
    const container = document.getElementById('matchedFilesList');
    
    if (!templateFile) {
        container.innerHTML = '<div class="empty-state-small">请先上传模板，系统将自动匹配文件</div>';
        return;
    }
    
    const templateBaseName = templateFile.name.replace(/\.(docx|doc|txt|md|pdf)$/i, '').toLowerCase();
    
    const matchedFiles = managedFiles.filter(file => {
        const fileName = file.name.replace(/\.(docx|doc|pdf|txt|md|xlsx|xls|csv)$/i, '').toLowerCase();
        return fileName.includes(templateBaseName) || templateBaseName.includes(fileName);
    });
    
    if (matchedFiles.length === 0) {
        container.innerHTML = `
            <div class="empty-state-small">
                ⚠️ 未找到匹配的文件<br>
                <small>模板名称：${templateFile.name}</small><br>
                <small>请在"文件管理"中导入与模板名称对应的数据文件</small>
                <small>支持格式：.docx, .doc, .pdf, .txt, .md, .xlsx, .xls, .csv</small>
            </div>
        `;
        return;
    }
    
    container.innerHTML = `
        <div class="matched-info">
            <p>✅ 找到 ${matchedFiles.length} 个匹配的数据文件：</p>
            <div class="matched-list">
                ${matchedFiles.map(file => `
                    <div class="matched-item">
                        <span class="matched-icon">${getFileIcon(file.name)}</span>
                        <span class="matched-name">${file.name}</span>
                        <span class="matched-size">${formatFileSize(file.size)}</span>
                        <button class="matched-use-btn" onclick="useMatchedFileInChat('${file.id}')">使用</button>
                    </div>
                `).join('')}
            </div>
        </div>
    `;
}

function useMatchedFileInChat(id) {
    const file = managedFiles.find(f => f.id === id);
    if (file && file._fileObj) {
        currentFile = file._fileObj;
        updateFileStatus(file._fileObj);
        switchTab('chat');
        addMessage(`已加载数据文件：${file.name}`, 'user');
        setTimeout(() => {
            addMessage(`文件 "${file.name}" 已加载。您可以开始处理这个文档了！`, 'ai');
        }, 300);
    }
}

function checkExportReady() {
    const exportBtn = document.getElementById('exportBtn');
    const hasTemplate = templateFile !== null;
    const hasMethod = currentExportMethod !== null;
    exportBtn.disabled = !(hasTemplate && hasMethod);
}

async function executeSmartExport() {
    if (!templateFile) {
        alert('请先上传模板文件');
        return;
    }
    
    if (!currentExportMethod) {
        alert('请选择提取方式（OCR本地提取 或 API在线提取）');
        return;
    }
    
    const templateBaseName = templateFile.name.replace(/\.(docx|doc|txt|md|pdf)$/i, '').toLowerCase();
    const matchedFiles = managedFiles.filter(file => {
        const fileName = file.name.replace(/\.(docx|doc|pdf|txt|md|xlsx|xls|csv)$/i, '').toLowerCase();
        return fileName.includes(templateBaseName) || templateBaseName.includes(fileName);
    });
    
    if (matchedFiles.length === 0) {
        alert('未找到与模板匹配的数据文件，请先在"文件管理"中导入对应的数据文件');
        return;
    }
    
    const autoMatch = document.getElementById('autoMatchCheckbox').checked;
    const includeRequirement = document.getElementById('includeRequirementCheckbox').checked;
    
    const methodName = currentExportMethod === 'local' ? 'OCR本地提取' : 'API在线提取';
    const methodDesc = currentExportMethod === 'local' ? '数据不上传，保护隐私' : '云端AI智能识别，准确度高';
    
    showLoading(`正在使用 ${methodName} 进行智能提取...\n${methodDesc}`);
    
    try {
        const formData = new FormData();
        formData.append('template', templateFile);
        formData.append('method', currentExportMethod);
        formData.append('autoMatch', autoMatch);
        formData.append('includeRequirement', includeRequirement);
        
        if (requirementFile) {
            formData.append('requirement', requirementFile);
        }
        
        matchedFiles.forEach((file, index) => {
            if (file._fileObj) {
                formData.append(`dataFile_${index}`, file._fileObj);
            }
        });
        
        const response = await fetch('http://localhost:3000/api/smart-export', {
            method: 'POST',
            body: formData
        });
        
        const result = await response.json();
        
        if (result.success) {
            switchTab('chat');
            addMessage(`✅ 智能导出完成！\n\n🔍 提取方式：${methodName}\n📋 模板：${templateFile.name}\n📊 处理数据文件：${matchedFiles.length} 个\n${result.summary || ''}`, 'ai');
            
            if (result.downloadUrl) {
                setTimeout(() => addDownloadCard(result.downloadUrl, result.fileName), 500);
            }
        } else {
            alert('导出失败：' + result.error);
        }
    } catch (error) {
        alert('导出请求失败：' + error.message);
    } finally {
        hideLoading();
    }
}

// ==================== 工具函数 ====================
function showLoading(text = '处理中...') {
    const loadingText = document.getElementById('loadingText');
    if (loadingText) loadingText.textContent = text;
    document.getElementById('loadingOverlay').style.display = 'flex';
}

function hideLoading() {
    document.getElementById('loadingOverlay').style.display = 'none';
}

// 初始化
document.addEventListener('DOMContentLoaded', () => {
    switchTab('chat');
    renderFileList();
});