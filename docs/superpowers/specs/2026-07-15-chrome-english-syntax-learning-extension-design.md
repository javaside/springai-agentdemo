# Chrome 英语句法学习扩展设计

**日期：** 2026-07-15  
**状态：** 已批准  
**目标浏览器：** Chrome 120 及以上，Manifest V3

## 1. 产品目标

本项目构建一个个人自用的 Chrome 扩展，帮助用户在阅读英文网页时学习句子结构。它不是把英文替换成中文的普通翻译器，而是把每个句子拆成可对照的学习单元：句子成分名称在上，带下划线的英文成分居中，对应的中文翻译在下。用户点击某个成分后，可以继续查看从句、非谓语、时态、语态等详细解释。

首版成功标准是：不打断正常阅读、不破坏网页、尽量减少模型费用，并且在模型返回不可靠结果时拒绝展示伪装成正确答案的不完整分析。

## 2. 已确认的产品决策

- 产品面向个人自用，不需要账户、云服务或 Spring Boot 后端。
- 扩展直接调用用户配置的 OpenAI-compatible API。
- 用户点击扩展后才解析当前可视区域；滚动时按需解析新进入附近区域的正文。
- 默认显示核心成分，点击成分时才按需请求详细语法。
- 成分标签只显示中文。
- 每个成分单独显示中文翻译，不在句末重复整句翻译。
- 默认智能识别正文；右键菜单可以解析选中文本或当前指向的正文区域。
- 默认启用本地缓存，用户可以查看容量并一键清空。
- API Key 持久保存在扩展本地存储中，并明确提示只适合个人可信设备。
- 每句提供带纠错要求的“重新分析”，首版不提供拖动边界或直接编辑结果。
- 支持保存和切换多个模型配置；切换模型不自动重新计算已显示内容。

## 3. 首版范围

### 3.1 包含

- 普通英文文章、博客、新闻和 Wiki 风格页面。
- 标题、段落、列表项和引用中的正文。
- OpenAI-compatible Chat Completions 接口。
- HTTPS 远程 API，以及 `localhost`、`127.0.0.1` 上的 HTTP 本地 API。
- 核心句法分析、分成分翻译、按需详细语法解释。
- 多模型配置、连接测试、模型切换。
- 可视区域解析、滚动增量解析、手动区域解析。
- 单句重试和带自然语言纠错要求的重新分析。
- 本地核心结果缓存和详细结果缓存。
- 中止、暂停、恢复网页、进度和可操作错误提示。

### 3.2 不包含

- 自动解析整个网页。
- Anthropic、Gemini 等非 OpenAI-compatible 原生协议。
- 云端账户、密钥托管、跨设备同步和服务端缓存。
- 手动拖动句法边界、直接编辑标签或译文。
- 生词本、记忆卡、测验和学习统计。
- PDF、`chrome://` 页面、Chrome 应用商店页面。
- 编辑器、在线文档、表格应用等复杂 Web App 的正文改写。
- Firefox 和 Safari 兼容。

## 4. 用户体验

### 4.1 启动与停止

用户在英文网页上点击扩展图标，Popup 显示当前模型和“开始学习”按钮。开始后，扩展获得当前标签页的临时访问权，扫描当前可视区域及上下各约一个视口的正文。

扩展在某句的核心结果准备完成前保留原文，不用空白或加载块替换正文。结果通过校验后，原正文块才被学习块替换。Popup 提供暂停和“停止并恢复网页”：

- 暂停不再派发新请求，已经发出的请求允许完成。
- 停止会取消等待队列和可取消的请求，删除全部学习块并恢复原正文。
- 页面跳转、标签页关闭或 Content Script 卸载时，关联任务被取消。

### 4.2 三层成分排版

每个核心成分渲染为一个三层单元：

```text
       主语               谓语                 宾语
     learners       need to notice        the structure
      学习者              需要注意              这个结构
```

- 上层为简短中文句法标签。
- 中层为英文原文，并使用统一、同色的实线下划线。
- 下层为该成分对应的中文翻译。
- 标点保持原顺序，不创建独立翻译单元。
- 成分单元可以在单元之间换行；单个长成分内部也允许换行，不能横向溢出正文容器。
- 默认不使用多色区分成分，避免持续阅读时信息过载。
- 不在句末重复完整中文译文。

### 4.3 详细语法

点击一个成分后才请求或读取详细结果。展开区显示：

- 当前成分的短语或从句类型；
- 内部主语、谓语、宾语等结构；
- 相关时态、语态、非谓语形式；
- 该结构在整句中的作用；
- 简短、面向学习者的中文说明。

同一时刻一个句子只展开一个成分。展开区不遮挡原文，支持键盘操作，并通过 `aria-expanded` 表达状态。

### 4.4 纠错重新分析

每句的操作菜单提供“重新分析”。用户可以输入一句可选纠错要求，例如“这里应该是定语从句”。该请求携带原句、当前结果和纠错要求，但不携带网页其他内容。

纠错结果使用包含页面 URL、句子实例和纠错要求摘要的独立缓存键，不覆盖相同英文在其他网页上的普通缓存。AI 结果旁始终提供简短提示：分析由 AI 生成，可能存在错误。

### 4.5 Popup

Popup 至少显示：

```text
当前模型：DeepSeek
已完成：18 句
处理中：4 句
缓存命中：11 句
失败：1 句

[暂停] [停止并恢复网页]
```

Popup 可以切换已保存模型。切换只影响随后发出的请求；当前结果继续保留。用户可以主动选择“用当前模型重新解析可视区域”，操作前明确说明会产生新的模型费用。

### 4.6 Options

设置页支持新增、编辑、删除、测试和选择模型配置。每个配置包含：

- 显示名称；
- Base URL；
- API Key；
- Model；
- 可选自定义请求头；
- 请求超时时间，默认 45 秒。

自定义请求头不能覆盖 `Authorization`、`Host`、`Content-Length`、`Origin` 和扩展内部追踪头。界面不在日志或错误详情中显示完整密钥。API Key 输入框默认遮挡，设置页明确提示 `chrome.storage.local` 不是系统级加密保险箱，只应在个人可信设备使用。

设置页还提供缓存条目数、估算占用、10/50/100/200 MB 上限选择和一键清空；默认上限为 50 MB。

## 5. 系统架构

扩展作为仓库中的独立 TypeScript 模块实现，不依赖现有 Java 模块。

```text
Popup / Options
       │
       ▼
Manifest V3 Service Worker
  ├─ 配置与权限
  ├─ OpenAI-compatible Adapter
  ├─ 请求队列、取消与重试
  ├─ 响应解析与结果校验
  └─ IndexedDB 缓存
       │ chrome.runtime 消息
       ▼
Content Script
  ├─ 正文候选识别
  ├─ 可视区域监听
  ├─ 分句、分词与稳定 ID
  ├─ 学习块渲染
  └─ 页面恢复
```

### 5.1 Popup 与 Options

Popup 只管理当前标签页的学习会话，不直接调用模型。Options 负责配置，但模型密钥不通过消息发给 Content Script。

### 5.2 Service Worker

Service Worker 是唯一允许读取 API Key 和调用模型的组件。它负责：

- 规范化配置并申请模型源权限；
- 对来自 Content Script 的消息做类型和来源校验；
- 查询缓存并对未命中句子去重；
- 按 Token 预算组成批次；
- 限制并发、设置超时、重试和取消；
- 解析 JSON、校验成分覆盖并进行一次格式修复；
- 只把经过校验的领域对象返回 Content Script。

Service Worker 可能被 Chrome 回收，因此 Content Script 持有页面会话的权威状态。双方使用带 `requestId`、`tabId`、`documentId` 和协议版本的消息。连接中断后，Content Script 可以重建端口并只重提仍未完成的句子；Service Worker 通过进行中去重和缓存避免重复收费。

### 5.3 Content Script

Content Script 不持有模型配置或密钥。它只负责网页读取、局部语言处理、渲染和用户交互。它不使用 `innerHTML` 插入模型内容，不执行模型返回的字符串，也不把页面 DOM 对象交给 Service Worker。

### 5.4 Shared Domain

共享模块定义：

- Popup、Options、Service Worker、Content Script 的消息联合类型；
- 模型请求和经过验证的模型响应类型；
- 句法角色枚举与中文显示名；
- 会话、批次和单句状态机；
- 可序列化错误码；
- 提示词版本、缓存协议版本和消息协议版本。

## 6. 权限与安全

Manifest 声明以下固定权限：

- `activeTab`：用户明确操作后临时访问当前标签页；
- `scripting`：按需注入 Content Script 和样式；
- `storage`：保存配置；
- `contextMenus`：解析选中文本或右键指向的正文块。

模型源使用可选 Host 权限。保存配置或测试连接时，扩展从 Base URL 提取精确 origin，并通过 `chrome.permissions.request()` 请求该 origin。远程地址必须是 HTTPS；HTTP 仅接受 `localhost` 和 `127.0.0.1`。不接受带用户名或密码的 URL。

用户点击扩展图标、键盘命令或右键菜单后才获得 `activeTab`。扩展不在安装时申请持久读取所有网页。此做法遵循 Chrome 的 [activeTab 建议](https://developer.chrome.com/docs/extensions/develop/concepts/activeTab)；跨域模型请求在 Service Worker 中执行，并需要相应 [Host 权限](https://developer.chrome.com/docs/extensions/develop/concepts/network-requests)。

配置保存在 `chrome.storage.local`，启动时调用 `chrome.storage.local.setAccessLevel({ accessLevel: "TRUSTED_CONTEXTS" })`，防止 Content Script 直接访问。该能力和本地 10 MB 配额记录在 Chrome 的 [Storage API 文档](https://developer.chrome.com/docs/extensions/reference/api/storage)。分析缓存不放入 `chrome.storage.local`，而是放入 Service Worker 可访问的 IndexedDB。

额外安全规则：

- 日志、Telemetry 和错误对象必须脱敏 URL 查询参数、Authorization 头和 API Key。
- API 返回值在进入缓存和页面前必须通过类型与长度校验。
- 所有展示文本使用 `textContent` 或框架的文本节点能力。
- 不加载远程 JavaScript，不使用 `eval`、`new Function` 或内联可执行模型内容。
- 请求只包含用户触发范围内的英文句子和必要的 Token 编号，不发送 Cookie、表单值、完整 HTML 或登录信息。
- Options 在首次保存密钥前展示第三方模型会接收所选英文文本的隐私说明。

## 7. 正文识别与可逆渲染

### 7.1 自动候选

自动模式优先检查 `article`、`main` 和 `[role="main"]`，再使用文本密度选择主要正文容器。候选块限定为 `h1`–`h6`、`p`、`li` 和 `blockquote`。

候选必须：

- 在布局中可见且与当前预取区域相交；
- 不位于 `nav`、`aside`、`footer`、`form`、`pre`、`code`、`script`、`style`、`noscript`、`template`、`svg`、`canvas`、`iframe` 或 `[contenteditable]` 内；
- 不包含按钮、输入控件、音视频或复杂可编辑内容；
- 自动模式下至少包含 20 个可见字符，并以英文词为主；
- 尚未被当前文档会话处理或排队。

右键“解析所选文本”绕过正文容器和最短长度限制，但仍拒绝密码框、可编辑区域和受保护页面。右键“解析此区域”使用最近的安全候选块。

### 7.2 分句与分词

使用 `Intl.Segmenter("en", { granularity: "sentence" })` 做初始分句，并用确定性的本地规则处理常见缩写、引号和尾随标点。分词器为每个 Token 保存：

- 连续整数 ID；
- 原字符串；
- 在规范化句子中的 UTF-16 起止偏移；
- 是否为标点；
- 原始前导空白。

规范化只统一 Unicode 空白和换行，不改变单词大小写、撇号、连字符或标点。稳定句子 ID 由文档会话 ID、块 ID、规范化原句和块内顺序生成。

### 7.3 渲染策略

扩展不改写原正文节点的 `innerHTML`。核心结果就绪后：

1. 在原块旁插入一个学习块宿主节点；
2. 通过扩展样式类隐藏原块，但保留原节点、属性、事件监听器和运行状态；
3. 在学习块中用纯文本重新创建句子与成分单元；
4. 学习块使用 Shadow DOM 隔离内部样式，并继承原块的字体族、字号和文字颜色；
5. 停止时删除学习块和隐藏类，使原节点原样恢复。

原文直到整个块中可显示的句子均准备就绪后才被隐藏，避免同一段落一半原文、一半学习块。若某句最终失败，该句在学习块中保持原文并提供重试，不伪造分析。

首版学习副本不保留段落内部链接、粗体、斜体和脚注交互。含图片、按钮、复杂嵌入或交互控件的块直接跳过。网页通过前端框架移除原块时，MutationObserver 同步清理对应学习块；网页只改变原块文字时，将旧结果标记失效并等待它重新进入扫描流程。

## 8. 模型协议

### 8.1 Provider 配置

首版只有 `OpenAiCompatibleAdapter`。Base URL 规范化后调用 Chat Completions 端点；配置层明确展示最终请求 URL，避免 Base URL 是否包含 `/v1` 的歧义。连接测试发送最小请求，并分别验证网络、鉴权、模型存在性和 JSON 指令遵循能力。

适配器记录配置是否支持 `response_format: { type: "json_schema" }`：

- 支持时使用严格 JSON Schema；
- 服务明确拒绝该参数时，回退到 JSON Object 或严格 JSON 提示词；
- 回退能力按模型配置缓存，用户可在连接测试时重新探测。

首版不使用流式响应。默认 `temperature` 为 `0`，不在普通设置中暴露；开发者级配置可以在后续版本增加。

### 8.2 核心请求

客户端发送句子 ID、原句和编号 Token。模型返回：

```json
{
  "schemaVersion": 1,
  "sentences": [
    {
      "sentenceId": "s-102",
      "components": [
        {
          "startToken": 0,
          "endToken": 6,
          "role": "ADVERBIAL_CLAUSE",
          "translation": "虽然在线工具能让阅读变得更容易"
        },
        {
          "startToken": 8,
          "endToken": 8,
          "role": "SUBJECT",
          "translation": "学习者"
        }
      ]
    }
  ]
}
```

`startToken` 和 `endToken` 均为闭区间。核心角色枚举固定为：

- `SUBJECT`：主语
- `PREDICATE`：谓语
- `OBJECT`：宾语
- `PREDICATIVE`：表语
- `ATTRIBUTE`：定语
- `ADVERBIAL`：状语
- `COMPLEMENT`：补语
- `APPOSITIVE`：同位语
- `SUBJECT_CLAUSE`：主语从句
- `OBJECT_CLAUSE`：宾语从句
- `PREDICATIVE_CLAUSE`：表语从句
- `ATTRIBUTIVE_CLAUSE`：定语从句
- `ADVERBIAL_CLAUSE`：状语从句
- `INDEPENDENT_ELEMENT`：独立成分

模型不能创建其他核心角色。更细的教学术语只能出现在详细结果中。

### 8.3 核心结果校验

每句结果必须同时满足：

- `sentenceId` 与请求一致；
- 至少包含一个成分；
- Token 区间存在、按原文升序排列且不重叠；
- 除标点外，每个 Token 恰好被一个核心成分覆盖；
- 成分可以包含内部标点，但不能只包含标点；
- 角色属于固定枚举；
- 中文翻译去除首尾空白后非空，且不超过对应英文长度的八倍或 500 个字符中的较大者；
- 返回批次不包含请求之外的句子。

第一次校验失败时，Service Worker 把错误列表、原 Token 和无效 JSON 发给同一模型，要求只修复结构。修复仍失败则生成 `INVALID_MODEL_OUTPUT`，保留原文并允许单句重试。

### 8.4 详细请求

详细请求只包含当前句子的 Token、已验证核心结构和用户点击的核心成分范围。结果包含：

- 焦点 Token 区间；
- 详细结构数组，每项仍使用 Token 闭区间和受控详细角色；
- 时态、语态、非谓语等语法点；
- 当前成分在整句中的中文作用说明。

详细结果只能引用原句 Token，不能改变核心成分边界或核心翻译。详细结果校验失败不会影响已显示的核心结果。

## 9. 请求调度与状态

### 9.1 限制

- 自动扫描范围为可视区域上下各约一个视口。
- 每批最多 6 句，同时还受 4,000 输入 Token 的软预算约束。
- 最多 2 个模型请求并发。
- 同一缓存键同时只存在一个进行中请求。
- 默认超时 45 秒。
- 远程错误最多重试两次。
- 单句规范化文本超过 2,000 个字符时不进入普通批次，提示用户选取更小范围。

DOM 扫描和学习块组装分片执行，优先使用 `requestIdleCallback`，不可用时以短 `setTimeout` 分片回退。每片同步工作预算不超过约 8 毫秒，避免长任务阻塞页面滚动。

### 9.2 单句状态机

```text
discovered → cache-check → queued → requesting → validating → ready
                              │             │
                              └─────────────┴→ failed → retrying

ready → detail-requesting → detail-ready
  │             └──────────→ detail-failed
  └→ stale → cache-check
```

状态更新带单调版本号；旧请求晚到时不能覆盖用户重试、模型切换或页面变化后产生的新状态。

### 9.3 重试规则

- `401/403`：不重试，暂停该配置的新请求并提示检查密钥或模型权限。
- `429`：优先采用 `Retry-After`，否则使用带抖动的指数退避，最多两次。
- `5xx`、断网和超时：带抖动重试，最多两次。
- 无效 JSON 或结构校验失败：执行一次模型结构修复，不计入网络重试次数。
- 明确的用户取消、页面离开和权限撤销：不重试。

批次允许部分成功。合法句子进入缓存和页面，无效句子单独失败；一条坏结果不能回滚整批成功结果。

## 10. 缓存设计

配置放在 `chrome.storage.local`；核心与详细分析结果放在 IndexedDB。

普通核心缓存键包含：

```text
SHA-256(
  normalizedSentence
  + providerOrigin
  + modelName
  + promptVersion
  + schemaVersion
)
```

密钥、完整 Authorization 头和配置显示名称不进入缓存键。详细缓存键在核心键基础上增加焦点 Token 范围与详细提示词版本。纠错缓存额外增加页面 URL、句子实例 ID 和纠错要求摘要。

每条记录保存结果、创建时间、最近访问时间、估算字节数和来源模型。写入后异步执行 LRU 清理，直到估算占用不超过用户上限。清空缓存不删除模型配置；删除模型配置也不自动删除历史缓存，设置页提供按模型清理。

## 11. 错误与用户提示

共享错误码至少包含：

- `CONFIG_MISSING`
- `HOST_PERMISSION_DENIED`
- `AUTH_FAILED`
- `MODEL_NOT_FOUND`
- `RATE_LIMITED`
- `NETWORK_ERROR`
- `REQUEST_TIMEOUT`
- `INVALID_MODEL_OUTPUT`
- `UNSUPPORTED_PAGE`
- `UNSAFE_CONTENT_BLOCK`
- `SENTENCE_TOO_LONG`
- `REQUEST_CANCELLED`

Popup 显示会话级摘要，学习块只显示与当前句子相关的简短错误和重试按钮。技术详情可以复制，但必须脱敏。错误提示必须告诉用户下一步，例如“检查 API Key”“重新测试连接”“缩小选中文本”，不能只显示状态码。

## 12. 测试策略

### 12.1 单元测试

- `Intl.Segmenter` 后处理、缩写、引号和尾随标点；
- Token ID、UTF-16 偏移、空白和重建原句；
- 核心成分排序、覆盖、重叠、角色和译文校验；
- 详细结果不能修改核心结构；
- 缓存键稳定性、模型隔离、纠错隔离和 LRU 清理；
- URL 规范化、HTTPS/localhost 规则和禁止请求头；
- 401、429、5xx、超时、取消和结构修复策略；
- 状态版本防止旧响应覆盖新状态。

### 12.2 DOM 固定场景

固定 HTML 覆盖新闻、博客和 Wiki 风格正文，以及：

- 普通段落、标题、列表和引用；
- 内联链接、粗体、斜体和脚注；
- 隐藏元素、导航、评论、代码、表单和 contenteditable；
- 含图片、按钮和复杂交互的跳过规则；
- 动态增加、修改和删除正文节点；
- 学习模式停止后原节点、可见性和交互恢复。

### 12.3 模型协议测试

使用本地模拟 OpenAI-compatible 服务覆盖：

- JSON Schema 成功；
- 服务拒绝 `response_format` 后回退；
- 正常批次与部分成功；
- 401、403、404 模型、429、5xx、断网和超时；
- 非 JSON、Markdown 包裹 JSON、缺少句子、重复句子；
- Token 越界、重叠、缺口、未知角色、空翻译；
- 带 HTML、脚本标签和异常超长文本的恶意结果；
- 一次结构修复成功和再次失败。

### 12.4 Chromium 端到端测试

Playwright 加载未打包 Manifest V3 扩展和本地模型模拟服务，验证：

- 保存配置、申请 Host 权限和测试连接；
- 点击启动后仅解析当前预取区域；
- 滚动触发增量解析；
- 三层排版与按成分翻译；
- 点击成分后懒加载详细语法；
- 缓存命中不产生网络请求；
- 切换模型不重算现有内容；
- 带纠错要求的单句重试；
- 暂停、取消、页面跳转和停止恢复；
- Content Script 不能读取 API Key；
- 键盘操作、200% 缩放、窄视口和深色页面。

### 12.5 教学句型样本

建立确定版本的英语句型样本集，包含基本主谓宾、主系表、被动语态、定语、状语、补语、各类从句、非谓语、倒装、省略、强调、插入语、引号、缩写和长句。

CI 不依赖真实大模型输出的唯一答案。它验证 Token 覆盖、协议约束、渲染与错误隔离；真实模型只作为人工或显式启用的烟雾测试，避免非确定性和外部费用进入默认测试。

## 13. 技术栈

- Chrome Manifest V3；
- TypeScript 严格模式；
- Vite 多入口构建；
- 原生 Web Components 与 Shadow DOM；
- IndexedDB；
- Vitest；
- Playwright Chromium 扩展测试；
- ESLint 与 Prettier。

不引入 React 或其他大型 UI 框架。Popup 和 Options 的规模有限，原生组件能减少包体、构建复杂度和运行时依赖。

## 14. 实施分解

本设计适合按以下纵向能力分阶段实施，每一阶段都能独立测试：

1. 扩展骨架、共享协议、配置和最小权限；
2. 正文识别、分句分词和可逆学习块；
3. OpenAI-compatible 核心分析、校验和三层渲染；
4. IndexedDB 缓存、请求调度和错误隔离；
5. 详细语法懒加载和纠错重新分析；
6. 多模型切换、Popup 状态和缓存管理；
7. 完整安全、性能、DOM 与端到端验证。

详细文件结构、接口签名、测试代码和逐步提交顺序由后续实施计划定义。

