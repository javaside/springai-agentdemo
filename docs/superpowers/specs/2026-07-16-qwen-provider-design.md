# 通义千问（Qwen）provider 接入 code-tui

> 为 code-tui 增加第五家大模型 provider：阿里云百炼（DashScope）通义千问，走 OpenAI 兼容通路，
> 与智谱同构复用 `spring-ai-openai`。初版已在 `feature/qwen-provider` 分支实现（d420794），
> 本 spec 为补做的 brainstorming 复盘定案：确认选型、收敛设计决策、列出差量与真机验收步骤。

## 目标与范围

- **目标**：`DASHSCOPE_API_KEY` 配了即可在 `/model` 里选千问模型，主 agent 流式对话 + 工具调用
  + 子 agent 阻塞调用全通。
- **场景（用户已确认）**：**国内百炼按量付费**（普通 `sk-` key、默认端点）。Coding Plan
  （`coding.dashscope.aliyuncs.com/v1`、`sk-sp-` key）与国际站不做自动识别——用户可经
  `DASHSCOPE_BASE_URL` 手动覆盖端点，但模型清单不随端点切换（超出本 spec 范围）。
- **非目标**：thinking 模式控制（走百炼各模型默认行为，不传 `enable_thinking`）、视觉能力
  （`capabilities()` 维持 `TEXT_ONLY` 默认，与其余四家现状一致）、DashScope 原生特性（Qwen-Audio 等）。

## 选型：OpenAI 兼容通路（方案 A，维持初版）

百炼提供 OpenAI Chat Completions 兼容模式：请求/响应体一致、SSE 流式、`tools`/`tool_calls`
走 OpenAI 标准格式。与 `ZhipuProvider` 同理，复用已验证的 `OpenAiChatModel` 通路即可。

淘汰的备选：
- **Spring AI Alibaba starter**：引入整套新依赖树且与 Spring AI 2.0 兼容性未验证；本项目 provider
  全是手工装配非 starter，风格冲突；为一家 provider 引入框架级依赖，YAGNI。
- **手写 DashScope 原生 ChatModel**：自研 SSE + tool_calls 解析等于重写 spring-ai-openai 干过的活。

## 设计定案

`QwenProvider implements LlmProvider`，与 `ZhipuProvider` 同构：

| 决策点 | 定案 |
|---|---|
| provider id | `"qwen"` |
| available | `DASHSCOPE_API_KEY` 非空 |
| chatModel | 懒建单例；OpenAI SDK 双 client（sync 子 agent 阻塞 / async 主 agent 流式），均带 `OpenAiTimeouts.of(LlmTimeouts.fromEnv())` 统一 read 超时 |
| baseUrl 默认 | `https://dashscope.aliyuncs.com/compatible-mode/v1`，`DASHSCOPE_BASE_URL` 可覆盖 |
| 模型清单 | `qwen3.7-max`（默认）/ `qwen3.7-plus` / `qwen3.6-flash` / `qwen3-coder-next` |
| 默认模型 | `qwen3.7-max`——对齐本仓库「默认=旗舰」惯例（DeepSeek→v4-pro、智谱→glm-5.2、OpenAI→gpt-5.6-sol） |
| thinking | 不传参，走百炼默认 |
| 装配位置 | registry 偏好序：DeepSeek → 智谱 → **千问** → Anthropic → OpenAI（国产在前） |
| capabilities | 不覆写（TEXT_ONLY） |

**baseUrl 的坑**（与智谱同款）：OpenAI Java SDK 覆盖 baseUrl 后仅追加 `chat/completions`
（不强制拼 `/v1`），故 baseUrl 必须写全到 `/compatible-mode/v1`。

## 错误处理（沿用既有机制，零新代码）

- key 缺失 → `available()=false`：不出现在 `/model`、不阻断启动；全家无 key 的启动提示已含
  `DASHSCOPE_API_KEY`。
- 网络挂死 → 统一 read 超时兜底（`CODETUI_LLM_READ_TIMEOUT_SECONDS`，默认 300s）。
- 流式异常 → CodingAgent 既有的空流守卫 / 取消回滚路径，provider 层不做特殊处理。

## 已实现（d420794，feature/qwen-provider）

- `QwenProvider.java` 新建；`CodeTuiApplication` 装配 + 无 key 提示更新
- `LlmProvider` javadoc id 列表、模块 `README.md`、`config.env.example`、
  `code-tui` / `code-tui.cmd` 脚本注释同步
- `LlmProviderTest` 补 qwen 可用 / 不可用 / baseUrl 覆盖与回落断言（fake key，网络无关）；
  `mvn -pl springai-code-tui test` 全绿（415 用例）

## 差量（本 spec 定案后待做）

1. **根 `README.md` 三处补千问**：L14 综合应用层描述、L16 对话模型列表（附
   [百炼控制台](https://bailian.console.aliyun.com/) 链接）、L49 架构图注。
2. **真机冒烟验收**（用户有 key；MCP 接入的教训：真实抓包才是 ground truth，工具调用流式分片
   是各家兼容层差异最大处）：
   1. 配 `DASHSCOPE_API_KEY` 启动，`/model` 列出 4 个 qwen 模型，切到 `qwen3.7-max`；
   2. 流式对话一轮（验证 SSE 分片渲染）；
   3. 触发一次工具调用（如读文件，验证 `tool_calls` 流式兼容）;
   4. 切 `qwen3-coder-next` 再对话一轮（验证每请求模型覆盖生效）。

   任一步挂 → 回到 provider 层修（优先怀疑 baseUrl 拼接与 tool_calls 分片格式）。

## 测试策略

- **单测**：装配 / 可用性 / options 网络无关断言（已有）。验证命令模块作用域：
  `mvn -pl springai-code-tui test`。
- **真机**：上述冒烟四步作为验收门槛，通过后方可合回 main。
