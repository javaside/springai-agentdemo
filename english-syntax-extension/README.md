# English Syntax Learning —— Chrome 英语句法学习扩展

一个 Manifest V3 Chrome 扩展：把网页中的英文段落替换为**逐句句法拆解卡片**（成分角色 / 英文原文 / 成分中文释义三行对照），点击任意成分再懒加载该成分的详细语法解析。分析由你自己配置的 OpenAI 兼容模型完成（DeepSeek、本地 Ollama、任何兼容 `/chat/completions` 的服务）。

> 本目录是**独立的 npm 项目**，不参与仓库根部的 Maven 构建。

## 环境要求

- Node.js ≥ 20（本项目在 Node 22 上开发验证）
- npm ≥ 10
- Chrome / Chromium ≥ 120（Manifest V3、`storage.setAccessLevel`）

## 安装与构建

```bash
cd english-syntax-extension
npm ci          # 安装依赖
npm run build   # 类型检查 + 产出 dist/
```

## 加载到 Chrome

1. 打开 `chrome://extensions`；
2. 右上角开启「开发者模式」；
3. 点「加载已解压的扩展程序」，选择本项目的 `dist/` 目录；
4. 工具栏出现扩展图标即加载成功。

## 配置模型（选项页）

右键扩展图标 →「选项」，或在弹窗中点「打开设置」。

**示例一：DeepSeek**

| 字段       | 值                            |
| ---------- | ----------------------------- |
| 配置名称   | DeepSeek                      |
| Base URL   | `https://api.deepseek.com/v1` |
| API Key    | 你的 DeepSeek Key             |
| 模型名     | `deepseek-chat`               |
| 超时（秒） | 45                            |

> 建议用 `deepseek-chat`：`deepseek-reasoner` 也能用，但推理模型每个段落要先输出思维链，单块分析约 30 秒起、费用更高，阅读场景不划算（用它时建议把超时调到 120 秒）。

**示例二：本地 Ollama**

| 字段       | 值                                    |
| ---------- | ------------------------------------- |
| 配置名称   | 本地 Ollama                           |
| Base URL   | `http://localhost:11434/v1`           |
| API Key    | `ollama`（Ollama 不校验，但字段必填） |
| 模型名     | `qwen2.5:14b` 等已拉取的模型          |
| 超时（秒） | 120（本地推理较慢）                   |

点「测试连接」会：请求**该模型地址的精确主机权限**（Chrome 弹出授权框，需手动允许）→ 发送一次最小 JSON 探测请求 → 报告该模型是否支持 JSON Schema 结构化输出（不支持会自动使用兼容模式）。

## 使用

1. 打开一篇英文文章页面（`http`/`https`）；
2. 点扩展图标 →「开始学习」；
3. 视口内及附近的英文段落被替换为句法卡片；向下滚动增量分析；
4. 点击卡片中任意成分 → 懒加载该成分的详细解析；
5. 「暂停」停止发起新请求；「停止并恢复网页」把页面完全还原为原始状态；
6. 「重新解析」对可视区域重新发起分析（会产生新的模型费用，有确认框）。

## 权限说明

| 权限                        | 用途                                                                              |
| --------------------------- | --------------------------------------------------------------------------------- |
| `activeTab` + `scripting`   | 仅在你点击「开始学习」后向当前标签页注入内容脚本                                  |
| `storage`                   | 保存模型配置；已设置 `TRUSTED_CONTEXTS`，**内容脚本（网页侧）读不到你的 API Key** |
| `contextMenus`              | 右键菜单入口                                                                      |
| `optional_host_permissions` | 模型地址按需精确授权（保存/测试配置时才弹授权框），不预先索要任何网站权限         |

清单中**没有**预置 `host_permissions`——扩展默认无权访问任何网站或模型地址。

## 发送给模型的内容与隐私

- 发送：被分析段落的**英文句子文本及分词结果**、你的纠错反馈文本；
- 不发送：页面 URL 以外的浏览记录、Cookie、表单内容、页面截图；
- API Key 仅存于扩展的 `chrome.storage.local`（受 TRUSTED_CONTEXTS 保护），**不做加密**——请注意任何能读取你 Chrome 用户目录的本地程序理论上都能拿到它；不要在共享电脑上保存高价值 Key。

## 缓存

分析结果缓存在扩展的 IndexedDB 中（核心/详解/纠错三类，键含模型地址、模型名、提示词版本），同一句子换页重读**零模型调用**。选项页可设置缓存上限（10–200 MB）并一键「清空缓存」（不会删除模型配置）。

## 不支持的页面

`chrome://`、`chrome-extension://`、Chrome 应用商店、PDF 查看器、本地 `file://`（除非你在扩展详情里手动允许）等无法注入内容脚本，弹窗会提示「此页面不支持句法解析」。

## 开发与测试

```bash
npm test              # 单元测试（vitest，278+ 用例）
npm run test:e2e      # 端到端测试（Playwright + 真实 Chromium 加载 dist/，本地模型伪服务，无外网依赖）
npm run build         # 类型检查 + 构建
npm run lint          # ESLint（typescript-eslint typeChecked）
npm run format:check  # Prettier
```

首次跑 E2E 需要 `npx playwright install chromium`。

E2E 说明：MV3 可选主机权限的授权框是**原生对话框**，无法在无头环境自动点击，因此 E2E 构建会把 dist 复制到临时目录并把两个回环地址提升为必需 `host_permissions`；正式 `dist/manifest.json` 保持可选授权不变，且有专门用例断言这一点。测试语料见 `tests/fixtures/teaching-sentences.json`（12 类句型 × 3 句），CI 只校验分词与覆盖不变量，从不断言某个模型的唯一正确拆分。

## 故障排查

| 现象                                                  | 原因与处理                                                                                                                                                                                    |
| ----------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 测试连接提示「鉴权失败」 / 学习卡片显示 `AUTH_FAILED` | API Key 错误或已吊销。修正 Key 保存后点卡片上的「重新解析」；同一无效 Key 会被暂停以避免反复计费请求，**更新 Key 后自动恢复**                                                                 |
| 卡片短暂等待后才出结果，偶发 `RATE_LIMITED`           | 命中限流（HTTP 429）。扩展会按 `Retry-After` 自动重试；频繁出现请降低滚动速度或换用限流更宽松的模型                                                                                           |
| 测试连接提示「网络连接失败」                          | Base URL 写错、服务未启动，或是**浏览器 CORS/私网限制**：远程服务需允许来自扩展的跨域请求（正常的 OpenAI 兼容服务都允许）；本地服务请确认用 `http://localhost` 或 `http://127.0.0.1` 并已授权 |
| 测试连接提示「未获得模型地址访问权限」                | 你在授权框点了拒绝。重新保存配置并在弹出的授权框中选择「允许」                                                                                                                                |
| 卡片显示 `INVALID_MODEL_OUTPUT`                       | 模型返回了无法解析/不完整的 JSON。扩展已自动做过一次结构修复仍失败；点「重新解析」重试，或换用结构化输出更稳定的模型（选项页会探测 JSON Schema 支持）                                         |
| 句子显示 `SENTENCE_TOO_LONG`                          | 单句超过 2000 字符（多为伪正文），该句跳过，不影响其他句子                                                                                                                                    |
| 部分句子成功、个别句子失败                            | 设计如此——失败按句隔离，失败句保留原文并提供「重新解析」按钮                                                                                                                                  |

## 目录结构

```
english-syntax-extension
├── manifest.config.ts      # MV3 清单（构建期生成 dist/manifest.json）
├── src
│   ├── background/         # Service Worker：消息路由、分析服务、缓存、调度、OpenAI 兼容适配器
│   ├── content/            # 内容脚本：扫描、视口观察、学习卡片（Shadow DOM）、原文替换/还原
│   ├── language/           # 分句、分词、模型输出校验
│   ├── options/ popup/     # 选项页与弹窗
│   └── shared/             # 协议、错误码、语法角色等共享类型
└── tests
    ├── e2e/                # Playwright 端到端（真实 Chromium）
    ├── support/            # 本地 OpenAI 兼容伪服务
    └── fixtures/           # 固定页面与教学语料
```
