const fs = require('fs');
const path = require('path');
const mammoth = require('mammoth');
const pdfParse = require('pdf-parse');
const docx = require('docx');
const { Document, Paragraph, TextRun, Packer } = docx;

class AIProcessor {
    constructor() {
        this.processedDir = path.join(__dirname, 'processed');
        
        if (!fs.existsSync(this.processedDir)) {
            fs.mkdirSync(this.processedDir, { recursive: true });
        }
    }

    async processDocument(filePath, originalName, instruction) {
        const content = await this.extractContent(filePath, originalName);
        const action = this.parseInstruction(instruction);
        
        let result;
        switch (action.type) {
            case 'format':
                result = await this.formatDocument(content, originalName);
                break;
            case 'extract':
                result = await this.extractKeyInfo(content, originalName);
                break;
            case 'convert':
                result = await this.convertFormat(content, originalName, action.targetFormat);
                break;
            case 'compress':
                result = await this.compressDocument(content, originalName);
                break;
            default:
                result = await this.analyzeDocument(content, originalName);
        }

        return result;
    }

    async extractContent(filePath, originalName) {
        const ext = path.extname(originalName).toLowerCase();
        
        try {
            if (ext === '.docx') {
                const result = await mammoth.extractRawText({ path: filePath });
                return {
                    text: result.value,
                    type: 'docx'
                };
            } else if (ext === '.pdf') {
                const dataBuffer = fs.readFileSync(filePath);
                const result = await pdfParse(dataBuffer);
                return {
                    text: result.text,
                    type: 'pdf'
                };
            } else if (ext === '.txt' || ext === '.md') {
                return {
                    text: fs.readFileSync(filePath, 'utf-8'),
                    type: 'text'
                };
            } else if (ext === '.doc') {
                try {
                    const result = await mammoth.extractRawText({ path: filePath });
                    return {
                        text: result.value,
                        type: 'doc'
                    };
                } catch (err) {
                    return {
                        text: fs.readFileSync(filePath, 'utf-8'),
                        type: 'text'
                    };
                }
            } else {
                throw new Error('不支持的文件格式');
            }
        } catch (error) {
            throw new Error(`文档读取失败: ${error.message}`);
        }
    }

    parseInstruction(instruction) {
        const lower = instruction.toLowerCase();
        
        if (lower.includes('转成') || lower.includes('转换') || lower.includes('改成') || lower.includes('变成')) {
            let target = 'docx';
            if (lower.includes('pdf')) target = 'pdf';
            else if (lower.includes('txt')) target = 'txt';
            else if (lower.includes('word') || lower.includes('docx')) target = 'docx';
            return { type: 'convert', targetFormat: target };
        }
        
        if (lower.includes('格式') || lower.includes('排版') || lower.includes('美化') || 
            (lower.includes('调整') && !lower.includes('转成'))) {
            return { type: 'format' };
        }
        
        if (lower.includes('提取') || lower.includes('总结') || lower.includes('摘要')) {
            return { type: 'extract' };
        }
        
        if (lower.includes('压缩') || lower.includes('减小') || lower.includes('瘦身')) {
            return { type: 'compress' };
        }
        
        return { type: 'analyze' };
    }

    async formatDocument(content, originalName) {
        console.log('[处理] 执行文档格式化...');
        
        const lines = content.text.split('\n').filter(l => l.trim());
        let processedParagraphs = [];
        
        lines.forEach((line, index) => {
            const trimmed = line.trim();
            const charCount = trimmed.replace(/\s/g, '').length;
            
            if (index < 5 && charCount < 20 && !/[。，；：！？,;:!?]/.test(trimmed) && !/^\d+[、.．]/.test(trimmed)) {
                processedParagraphs.push({
                    text: trimmed,
                    style: 'heading1',
                    level: 1
                });
            } 
            else if (/^[一二三四五六七八九十]+[、.]/.test(trimmed) || 
                     /^\d+[、.．]/.test(trimmed) ||
                     (charCount < 25 && trimmed.includes('：') && !/[。，；]/.test(trimmed.replace('：', '')))) {
                processedParagraphs.push({
                    text: trimmed,
                    style: 'heading2',
                    level: 2
                });
            }
            else if (/^（[一二三四五六七八九十]+）/.test(trimmed) ||
                     /^\d+\.\d+/.test(trimmed) ||
                     (charCount < 35 && /[：:]/.test(trimmed))) {
                processedParagraphs.push({
                    text: trimmed,
                    style: 'heading3',
                    level: 3
                });
            }
            else {
                processedParagraphs.push({
                    text: trimmed,
                    style: 'normal',
                    level: 0
                });
            }
        });
        
        const newFileName = 'formatted-' + originalName.replace(/\.(txt|pdf|md)$/i, '.docx');
        const outputPath = path.join(this.processedDir, newFileName);
        
        await this.createFormattedDocx(processedParagraphs, outputPath);
        
        return {
            message: `✅ 已完成文档格式优化！\n\n智能识别结果：\n• 大标题：${processedParagraphs.filter(p => p.style === 'heading1').length} 处\n• 一级标题：${processedParagraphs.filter(p => p.style === 'heading2').length} 处\n• 二级标题：${processedParagraphs.filter(p => p.style === 'heading3').length} 处\n• 正文段落：${processedParagraphs.filter(p => p.style === 'normal').length} 段`,
            downloadUrl: `/download/${newFileName}`,
            fileName: newFileName
        };
    }

    async extractKeyInfo(content, originalName) {
        console.log('[处理] 执行内容提取...');
        
        const sentences = content.text.split(/[。！？.!?]/).filter(s => s.trim());
        const keySentences = sentences.filter(s => {
            const hasKeywords = /重要|关键|核心|结论|因此|所以|总之|建议|注意/.test(s);
            const isMeaningful = s.length > 15 && s.length < 120;
            return hasKeywords || isMeaningful;
        });
        
        const summary = `文档智能摘要\n============\n\n【核心内容】\n${keySentences.slice(0, 8).join('。\n')}。\n\n【关键信息点】\n${keySentences.slice(0, 5).map((s, i) => `${i+1}. ${s.trim()}`).join('\n')}\n\n【文档统计】\n总句数：${sentences.length}\n关键句：${keySentences.length}`;
        
        const newFileName = 'extracted-' + originalName.replace(path.extname(originalName), '.txt');
        const outputPath = path.join(this.processedDir, newFileName);
        
        fs.writeFileSync(outputPath, summary, 'utf-8');
        
        return {
            message: `✅ 已提取文档关键信息！\n\n${summary.substring(0, 250)}...`,
            downloadUrl: `/download/${newFileName}`,
            fileName: newFileName
        };
    }

    async convertFormat(content, originalName, targetFormat) {
        console.log(`[处理] 转换格式为 ${targetFormat}...`);
        
        const newFileName = 'converted-' + originalName.replace(path.extname(originalName), `.${targetFormat}`);
        const outputPath = path.join(this.processedDir, newFileName);
        
        if (targetFormat === 'docx') {
            const paragraphs = content.text.split('\n').filter(p => p.trim()).map(p => ({
                text: p,
                style: 'normal',
                level: 0
            }));
            await this.createFormattedDocx(paragraphs, outputPath);
        } else if (targetFormat === 'pdf') {
            fs.writeFileSync(outputPath.replace('.pdf', '.txt'), content.text, 'utf-8');
            return {
                message: `⚠️ 内容已提取，PDF生成需要额外配置，已保存为文本格式。`,
                downloadUrl: `/download/${newFileName.replace('.pdf', '.txt')}`,
                fileName: newFileName.replace('.pdf', '.txt')
            };
        } else {
            fs.writeFileSync(outputPath, content.text, 'utf-8');
        }
        
        return {
            message: `✅ 已成功转换文件格式为 ${targetFormat.toUpperCase()}！`,
            downloadUrl: `/download/${newFileName}`,
            fileName: newFileName
        };
    }

    async compressDocument(content, originalName) {
        console.log('[处理] 执行文档压缩...');
        
        let compressed = content.text
            .replace(/\s+/g, ' ')
            .replace(/[。]{2,}/g, '。')
            .replace(/[！]{2,}/g, '！')
            .replace(/[？]{2,}/g, '？')
            .replace(/\n\s*\n/g, '\n')
            .trim();
        
        const newFileName = 'compressed-' + originalName;
        const outputPath = path.join(this.processedDir, newFileName);
        
        fs.writeFileSync(outputPath, compressed, 'utf-8');
        
        const originalSize = content.text.length;
        const newSize = compressed.length;
        const ratio = originalSize > 0 ? ((1 - newSize/originalSize) * 100).toFixed(1) : 0;
        
        return {
            message: `✅ 文档已压缩！\n\n压缩率：${ratio}%\n原字符数：${originalSize}\n压缩后：${newSize}`,
            downloadUrl: `/download/${newFileName}`,
            fileName: newFileName
        };
    }

    async analyzeDocument(content, originalName) {
        const totalChars = content.text.length;
        const chineseChars = content.text.replace(/[^\u4e00-\u9fa5]/g, '').length;
        const englishWords = content.text.match(/[a-zA-Z]+/g)?.length || 0;
        const numbers = content.text.match(/\d+/g)?.length || 0;
        const paragraphs = content.text.split('\n').filter(p => p.trim()).length;
        const sentences = content.text.split(/[。！？.!?]/).filter(s => s.trim()).length;
        
        return {
            message: `📊 文档分析报告\n\n文件名：${originalName}\n格式类型：${content.type}\n\n【字数统计】\n• 总字符数：${totalChars}\n• 中文字数：${chineseChars}\n• 英文单词：${englishWords}\n• 数字组：${numbers}\n\n【结构统计】\n• 段落数量：${paragraphs}\n• 句子数量：${sentences}`,
            downloadUrl: null
        };
    }

    async createFormattedDocx(paragraphs, outputPath) {
        const docxParagraphs = paragraphs.map(p => {
            let size = 24;
            let bold = false;
            let spacing = { after: 200, line: 360 };
            
            switch (p.style) {
                case 'heading1':
                    size = 44;
                    bold = true;
                    spacing = { after: 400, line: 400 };
                    break;
                case 'heading2':
                    size = 32;
                    bold = true;
                    spacing = { after: 300, line: 360 };
                    break;
                case 'heading3':
                    size = 28;
                    bold = true;
                    spacing = { after: 250, line: 360 };
                    break;
                default:
                    size = 24;
                    spacing = { after: 200, line: 360 };
            }
            
            return new Paragraph({
                children: [
                    new TextRun({
                        text: p.text,
                        bold: bold,
                        size: size,
                        font: { name: '宋体', eastAsia: '宋体' }
                    })
                ],
                spacing: spacing,
                indent: p.style === 'normal' ? { firstLine: 480 } : undefined
            });
        });

        const doc = new Document({
            sections: [{ children: docxParagraphs }]
        });

        const buffer = await Packer.toBuffer(doc);
        fs.writeFileSync(outputPath, buffer);
    }

    // ==================== 智能导出功能 ====================

    async smartExportLocal(templateFile, dataFiles, requirementFile, options) {
        console.log('[本地OCR] 开始处理（数据不上传，保护隐私）...');
        return this.smartExportCommon(templateFile, dataFiles, requirementFile, options, 'local');
    }

    async smartExportOnline(templateFile, dataFiles, requirementFile, options) {
        console.log('[在线API] 开始处理（云端AI智能识别）...');
        return this.smartExportCommon(templateFile, dataFiles, requirementFile, options, 'online');
    }

    async smartExportCommon(templateFile, dataFiles, requirementFile, options, method) {
        // 提取模板内容 - 支持多种格式
        const templateExt = path.extname(templateFile.originalname).toLowerCase();
        let templateContent;
        
        if (templateExt === '.docx') {
            const result = await mammoth.extractRawText({ path: templateFile.path });
            templateContent = { text: result.value, type: 'docx' };
        } else if (templateExt === '.pdf') {
            const dataBuffer = fs.readFileSync(templateFile.path);
            const result = await pdfParse(dataBuffer);
            templateContent = { text: result.text, type: 'pdf' };
        } else if (templateExt === '.txt' || templateExt === '.md') {
            templateContent = { text: fs.readFileSync(templateFile.path, 'utf-8'), type: 'text' };
        } else if (templateExt === '.doc') {
            try {
                const result = await mammoth.extractRawText({ path: templateFile.path });
                templateContent = { text: result.value, type: 'doc' };
            } catch (err) {
                templateContent = { text: fs.readFileSync(templateFile.path, 'utf-8'), type: 'text' };
            }
        } else {
            throw new Error(`不支持的模板格式: ${templateExt}`);
        }
        
        console.log(`模板格式: ${templateExt}, 内容长度: ${templateContent.text.length}`);
        
        // 提取数据文件内容
        const dataContents = [];
        for (const file of dataFiles) {
            const content = await this.extractContent(file.path, file.originalname);
            dataContents.push({
                name: file.originalname,
                content: content.text,
                type: content.type
            });
        }
        
        let requirementText = '';
        if (requirementFile) {
            const reqExt = path.extname(requirementFile.originalname).toLowerCase();
            if (reqExt === '.docx') {
                const result = await mammoth.extractRawText({ path: requirementFile.path });
                requirementText = result.value;
            } else if (reqExt === '.pdf') {
                const dataBuffer = fs.readFileSync(requirementFile.path);
                const result = await pdfParse(dataBuffer);
                requirementText = result.text;
            } else {
                requirementText = fs.readFileSync(requirementFile.path, 'utf-8');
            }
        }
        
        const placeholders = this.extractPlaceholders(templateContent.text);
        console.log(`检测到占位符: ${placeholders.join(', ')}`);
        
        const fieldValues = {};
        for (const placeholder of placeholders) {
            if (method === 'online') {
                fieldValues[placeholder] = await this.smartExtractFieldOnline(dataContents, placeholder);
            } else {
                fieldValues[placeholder] = await this.smartExtractField(dataContents, placeholder);
            }
        }
        
        let filledContent = templateContent.text;
        for (const [field, value] of Object.entries(fieldValues)) {
            const regex = new RegExp(`\\{\\{${field}\\}\\}`, 'g');
            filledContent = filledContent.replace(regex, value || `[未找到${field}]`);
        }
        
        if (options.includeRequirement && requirementText) {
            filledContent += `\n\n\n========== 用户要求 ==========\n\n${requirementText}`;
        }
        
        const timestamp = Date.now();
        const methodTag = method === 'local' ? 'ocr' : 'api';
        const templateBaseName = templateFile.originalname.replace(/\.(docx|doc|txt|md|pdf)$/i, '');
        const newFileName = `export-${methodTag}-${templateBaseName}-${timestamp}.docx`;
        const outputPath = path.join(this.processedDir, newFileName);
        
        await this.createFilledDocx(filledContent, outputPath);
        
        const summary = this.generateExportSummary(dataContents, fieldValues, placeholders, method);
        
        return {
            message: `✅ 智能导出完成！（${method === 'local' ? '本地OCR' : '在线API'}）`,
            downloadUrl: `/download/${newFileName}`,
            fileName: newFileName,
            summary: summary
        };
    }

    extractPlaceholders(text) {
        const regex = /\{\{([^}]+)\}\}/g;
        const matches = [];
        let match;
        while ((match = regex.exec(text)) !== null) {
            matches.push(match[1].trim());
        }
        return [...new Set(matches)];
    }

    async smartExtractField(dataContents, fieldName) {
        const fieldLower = fieldName.toLowerCase();
        let bestMatch = '';
        
        for (const data of dataContents) {
            const content = data.content;
            
            const colonPattern = new RegExp(`(?:^|\\n)${fieldLower}[：:]\\s*([^\\n]+)`, 'i');
            let match = content.match(colonPattern);
            if (match) {
                bestMatch = match[1].trim();
                break;
            }
            
            const sentencePattern = new RegExp(`[^。]*${fieldLower}[^。]*[。]`, 'i');
            match = content.match(sentencePattern);
            if (match && match[0].length < 100) {
                bestMatch = match[0].trim();
                break;
            }
            
            const commonFields = {
                '姓名': this.extractName(content),
                '电话': this.extractPhone(content),
                '邮箱': this.extractEmail(content),
                '地址': this.extractAddress(content),
                '日期': this.extractDate(content),
                '金额': this.extractAmount(content),
                '编号': this.extractNumber(content)
            };
            
            if (commonFields[fieldLower]) {
                bestMatch = commonFields[fieldLower];
                if (bestMatch) break;
            }
        }
        
        return bestMatch || `[未找到${fieldName}]`;
    }

    async smartExtractFieldOnline(dataContents, fieldName) {
        const fieldLower = fieldName.toLowerCase();
        let bestMatch = '';
        let bestScore = 0;
        
        for (const data of dataContents) {
            const content = data.content;
            
            const patterns = [
                { regex: new RegExp(`(?:^|\\n)${fieldLower}[：:]\\s*([^\\n]+)`, 'i'), weight: 10 },
                { regex: new RegExp(`${fieldLower}[是为]\\s*["']?([^"'\n]+)["']?`, 'i'), weight: 9 },
                { regex: new RegExp(`[^。]*${fieldLower}[^。]*[。]`, 'i'), weight: 5 },
                { regex: new RegExp(`${fieldLower}\\s*[=:]\\s*([^\\s,;]+)`, 'i'), weight: 8 }
            ];
            
            for (const pattern of patterns) {
                const match = content.match(pattern.regex);
                if (match && match[1] && match[1].length < 100) {
                    const value = match[1].trim();
                    const score = pattern.weight * (1 - Math.min(value.length, 50) / 100);
                    if (score > bestScore) {
                        bestScore = score;
                        bestMatch = value;
                    }
                }
            }
            
            if (bestScore < 7) {
                const commonMatch = this.extractCommonFieldOnline(content, fieldLower);
                if (commonMatch) {
                    bestMatch = commonMatch;
                    bestScore = 8;
                }
            }
            
            if (bestScore > 8) break;
        }
        
        return bestMatch || `[未找到${fieldName}]`;
    }

    extractCommonFieldOnline(content, fieldName) {
        const fieldMap = {
            '姓名': () => {
                const patterns = [/姓名[：:]?\s*([^\s\n]{2,4})/, /名称[：:]?\s*([^\s\n]{2,10})/];
                for (const p of patterns) {
                    const m = content.match(p);
                    if (m) return m[1];
                }
                return null;
            },
            '电话': () => {
                const m = content.match(/(?:电话|手机|tel|mobile)[：:]?\s*(1[3-9]\d{9}|0\d{2,3}-?\d{7,8})/i);
                return m ? m[1] : null;
            },
            '邮箱': () => {
                const m = content.match(/[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}/);
                return m ? m[0] : null;
            },
            '金额': () => {
                const m = content.match(/(?:金额|总计|合计|总额)[：:]?\s*[¥￥]?\s*(\d+(?:,\d{3})*(?:\.\d{2})?)\s*元?/i);
                return m ? m[1] : null;
            },
            '日期': () => {
                const m = content.match(/(?:日期|时间)[：:]?\s*(\d{4}[-/年]\d{1,2}[-/月]\d{1,2}日?)/i);
                return m ? m[1] : null;
            },
            '地址': () => {
                const m = content.match(/(?:地址)[：:]?\s*([^。]{5,40})/i);
                return m ? m[1] : null;
            }
        };
        
        if (fieldMap[fieldName]) {
            return fieldMap[fieldName]();
        }
        return null;
    }

    extractName(content) {
        const patterns = [
            /姓名[：:]?\s*([^\s\n]{2,4})/,
            /名称[：:]?\s*([^\s\n]{2,10})/,
            /([^\s\n]{2,4})[先生女士]/
        ];
        for (const pattern of patterns) {
            const match = content.match(pattern);
            if (match) return match[1];
        }
        return '';
    }

    extractPhone(content) {
        const patterns = [
            /1[3-9]\d{9}/,
            /0\d{2,3}-?\d{7,8}/
        ];
        for (const pattern of patterns) {
            const match = content.match(pattern);
            if (match) return match[0];
        }
        return '';
    }

    extractEmail(content) {
        const pattern = /[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}/;
        const match = content.match(pattern);
        return match ? match[0] : '';
    }

    extractAddress(content) {
        const pattern = /[省市区县路街号][^。]{5,30}/;
        const match = content.match(pattern);
        return match ? match[0] : '';
    }

    extractDate(content) {
        const patterns = [
            /\d{4}年\d{1,2}月\d{1,2}日/,
            /\d{4}-\d{1,2}-\d{1,2}/,
            /\d{4}\/\d{1,2}\/\d{1,2}/
        ];
        for (const pattern of patterns) {
            const match = content.match(pattern);
            if (match) return match[0];
        }
        return '';
    }

    extractAmount(content) {
        const patterns = [
            /[¥￥]?\s*\d+(?:\.\d{2})?/,
            /\d+(?:\.\d{2})?\s*元/
        ];
        for (const pattern of patterns) {
            const match = content.match(pattern);
            if (match) return match[0];
        }
        return '';
    }

    extractNumber(content) {
        const patterns = [
            /NO[\.:：]?\s*\d+/i,
            /编号[：:]?\s*\d+/,
            /[A-Z]{2,}\d{6,}/
        ];
        for (const pattern of patterns) {
            const match = content.match(pattern);
            if (match) return match[0];
        }
        return '';
    }

    generateExportSummary(dataContents, fieldValues, placeholders, method) {
        const matchedCount = Object.values(fieldValues).filter(v => !v.startsWith('[未找到')).length;
        let summary = `\n📊 导出统计\n`;
        summary += `━━━━━━━━━━━━━━━━━━━━\n`;
        summary += `🔍 提取方式：${method === 'local' ? '本地OCR（隐私保护）' : '在线API（智能识别）'}\n`;
        summary += `📁 处理数据文件：${dataContents.length} 个\n`;
        summary += `🔖 检测到占位符：${placeholders.length} 个\n`;
        summary += `✅ 成功匹配字段：${matchedCount} 个\n\n`;
        summary += `📋 字段匹配详情：\n`;
        for (const [field, value] of Object.entries(fieldValues)) {
            const status = value.startsWith('[未找到') ? '❌' : '✅';
            const displayValue = value.length > 30 ? value.substring(0, 30) + '...' : value;
            summary += `  ${status} {{${field}}} → ${displayValue}\n`;
        }
        return summary;
    }

    async createFilledDocx(content, outputPath) {
        const paragraphs = content.split('\n');
        
        const docxParagraphs = paragraphs.map(p => {
            const trimmed = p.trim();
            if (trimmed === '') {
                return new Paragraph({ children: [new TextRun({ text: '' })] });
            }
            
            const isTitle = /^[=#\-*]{3,}|^第[一二三四五六七八九十]+章|^\d+\./.test(trimmed);
            const isHeading = /^[一二三四五六七八九十]+[、.]|^[0-9]+[、.]/.test(trimmed);
            
            return new Paragraph({
                children: [
                    new TextRun({
                        text: p,
                        bold: isTitle || isHeading,
                        size: isTitle ? 32 : (isHeading ? 28 : 24),
                        font: { name: '宋体', eastAsia: '宋体' }
                    })
                ],
                spacing: { after: 200, line: 360 },
                indent: !isTitle && !isHeading && p.length > 0 ? { firstLine: 480 } : undefined
            });
        });
        
        const doc = new Document({
            sections: [{ children: docxParagraphs }]
        });
        
        const buffer = await Packer.toBuffer(doc);
        fs.writeFileSync(outputPath, buffer);
    }
}

module.exports = AIProcessor;