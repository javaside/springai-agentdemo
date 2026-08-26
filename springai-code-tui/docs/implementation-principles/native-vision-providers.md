# 其他 Provider 视觉能力实现原理

## 1. 这篇文档解决什么问题

[图片处理实现原理](image-processing.md)已经说明，项目如何把用户图片或工具图片变成当前出站 `UserMessage.media`。

本文从这里继续，只回答一个问题：

> 已经带有 `Media` 的 `UserMessage` 进入 provider 后，OpenAI、Anthropic、Qwen 和智谱如何把图片写入 HTTP 请求？

本文不再解释图片路径、artifact、`file_reference`、预算和会话生命周期。它们属于前一阶段，由[图片处理实现原理](image-processing.md)负责。

## 2. 一页看懂：Prompt.messages 如何进入 Provider

进入 provider 前，各家的 `Prompt.messages` 完全相同。图片来自用户还是工具，只决定 `Media` 挂在哪条 `UserMessage`；provider 看到的都是普通的 Spring AI 消息列表。

### 2.1 用户直接附图：原 UserMessage 携带 Media

用户图片随用户提交的本次请求发送。进入 provider 前，消息是：

```text
Prompt.messages
  └── UserMessage
        text  = 用户原文 + 图片引用
        media = 用户图片
```

provider 读取这条消息后，按各自协议生成图片内容块：

```text
UserMessage(text, media)
  ├── OpenAiChatModel
  │     -> text 块 + image_url 块
  │
  ├── AnthropicChatModel
  │     -> text 块 + image 块
  │
  └── Qwen / 智谱
        -> 复用 OpenAiChatModel
        -> text 块 + image_url 块
```

这里没有新增消息，也不需要模型先调用 `Read`。原 `UserMessage` 中的文本和 `Media` 一起交给 provider。

### 2.2 工具读取图片：新增 UserMessage 携带 Media

工具图片是在工具执行后进入消息链的。进入 provider 前，完整消息列表是：

```text
Prompt.messages
  ├── UserMessage
  │     text = 用户最初的问题
  │
  ├── AssistantMessage
  │     tool_calls = Read(screenshot.png)
  │
  ├── ToolResponseMessage
  │     responseData = 图片引用文本
  │
  └── 新增 UserMessage
        text  = 工具图片说明
        media = 工具读取的图片
```

provider 会遍历并转换全部消息：

```text
UserMessage
  -> 正常转换为 user 消息

AssistantMessage(tool_calls)
  -> 正常转换为 assistant 工具调用消息

ToolResponseMessage
  -> 正常转换为 tool 结果消息

新增 UserMessage(media = 工具图片)
  -> 转换为包含文本块和图片块的 user 消息
```

原来的 `ToolResponseMessage` 不会被图片消息替换。工具调用、工具结果和新增的图片 `UserMessage` 都会保留，以维持完整的工具调用上下文。

以 OpenAI 格式为例，省略与视觉无关的工具参数后，最终 `messages` 可以看成：

```json
{
  "messages": [
    {"role": "user",      "content": "用户最初的问题"},
    {"role": "assistant", "tool_calls": "Read(screenshot.png)"},
    {"role": "tool",      "content": "<file_reference>图片引用</file_reference>"},
    {
      "role": "user",
      "content": [
        {"type": "text", "text": "以下是工具结果中引用的图片：screenshot.png"},
        {
          "type": "image_url",
          "image_url": {"url": "data:image/png;base64,..."}
        }
      ]
    }
  ]
}
```

Anthropic 同样保留完整的用户提问、工具调用、工具结果和图片消息语义，但会按 Anthropic Messages API 重新表示这些消息；其中带 `Media` 的 user 消息会使用 Anthropic `image` 内容块。上面的 `role=tool` JSON 只用于展示 OpenAI 通路，不能直接当作 Anthropic 的最终 wire 格式。

### 2.3 Provider 处理所有带 Media 的 UserMessage

`OpenAiChatModel` 和 `AnthropicChatModel` 都会遍历本次 `Prompt.messages`。它们不是只看最后一条 user 消息，也不判断一条消息属于历史还是当前回合：

```text
遍历 Prompt.messages
  ├── system / assistant / tool
  │     -> 按各自消息类型转换
  │
  └── UserMessage
        ├── 没有 Media -> 普通文本 user 消息
        └── 带有 Media -> 文本块 + 图片块
```

因此，如果本次出站 Prompt 中有多条 `UserMessage` 带 `Media`，provider 会分别转换这些消息。哪些消息会在本次 Prompt 中带 `Media`，由上游图片处理层决定，不属于本文范围。

### 2.4 两条图片消息链的区别

| 图片来源 | 带 `Media` 的消息 | 是否新增消息 | provider 如何处理 |
| --- | --- | --- | --- |
| 用户直接附图 | 原来的 `UserMessage` | 否 | 把原消息转成文本块和图片块 |
| 工具读取图片 | 工具结果后新增的 `UserMessage` | 是，原 `ToolResponseMessage` 保留 | 把新增消息转成文本块和图片块 |

从 provider 适配层看，两条路径最终没有区别：

> provider 不关心图片来自用户还是工具，只转换本次 `Prompt.messages` 中实际带 `Media` 的 `UserMessage`。

## 3. OpenAI 如何把 Media 转成 image_url

### 3.1 从 Spring AI 消息到 OpenAI 请求消息

`OpenAiChatModel` 创建请求时遍历 `Prompt.messages`。遇到带 `Media` 的 `UserMessage` 后，它会新建 OpenAI SDK 请求对象：

```text
Spring AI UserMessage
  text  = "分析这张截图"
  media = [Media(image/png, byte[])]
        |
        | OpenAiChatModel.createRequest(...)
        v
OpenAI user 请求消息
  content = [text 块, image_url 块]
```

转换只发生在 provider 请求对象中，不会把 Spring AI 的 `UserMessage` 原地改成 JSON，也不会把 `content` 数组写回 `Prompt.messages`。

### 3.2 图片字节如何写入 OpenAI 请求

当前项目交给 `OpenAiChatModel` 的 `Media` 包含两项数据：

```text
mimeType = image/png
数据      = PNG 文件的 byte[]
```

HTTP JSON 不能直接放 Java 的 `byte[]`。`OpenAiChatModel` 创建 OpenAI 请求对象时，会把图片字节编码成 Base64 字符串，再与 MIME 类型拼成 data URL：

```text
data:image/png;base64,<图片字节的 Base64 字符串>
```

然后把整个 data URL 写入图片内容块的 `image_url.url` 字段。也就是：

```text
Media 中的 mimeType + byte[]
  -> OpenAiChatModel 编码并拼成 data URL
  -> 写入 OpenAI 请求的 image_url.url
```

最终消息是：

```json
{
  "role": "user",
  "content": [
    {"type": "text", "text": "分析这张截图"},
    {
      "type": "image_url",
      "image_url": {
        "url": "data:image/png;base64,..."
      }
    }
  ]
}
```

如果 `Media.data` 本身是 `URI` 或 URL 字符串，OpenAI 转换层也可以直接把它作为图片 URL。当前项目使用 `byte[]`，是因为图片已经由上游完成读取和准备。

## 4. Anthropic 如何把 Media 转成 image block

### 4.1 从 Spring AI 消息到 Anthropic 请求消息

`AnthropicChatModel` 同样遍历 `Prompt.messages`。遇到带 `Media` 的 `UserMessage` 后，它会新建 Anthropic Messages API 的内容块：

```text
Spring AI UserMessage
  text  = "分析这张截图"
  media = [Media(image/png, byte[])]
        |
        | AnthropicChatModel.createRequest(...)
        v
Anthropic user 请求消息
  content = [text block, image block]
```

这里同样只是创建 provider 请求对象，不修改原来的 `Prompt.messages`。

### 4.2 图片字节如何写入 Anthropic 请求

`AnthropicChatModel` 收到的 `Media` 同样包含 MIME 类型和图片字节：

```text
mimeType = image/png
数据      = PNG 文件的 byte[]
```

它也会把 `byte[]` 编码成 Base64 字符串，但 Anthropic 请求不使用 data URL，而是把信息拆开放入 `image` 内容块的 `source`：

```text
Media.mimeType
  -> source.media_type = image/png

Media 中的 byte[]
  -> AnthropicChatModel 做 Base64 编码
  -> source.data = <图片字节的 Base64 字符串>

source.type = base64
  -> 告诉 Anthropic source.data 使用的是 Base64 数据
```

最终消息可以看成：

```json
{
  "role": "user",
  "content": [
    {"type": "text", "text": "分析这张截图"},
    {
      "type": "image",
      "source": {
        "type": "base64",
        "media_type": "image/png",
        "data": "..."
      }
    }
  ]
}
```

Anthropic 转换层也支持 HTTPS 图片 URL，此时会生成 URL image source，而不是 base64 source。

### 4.3 与 OpenAI 的区别

两家接收的 Spring AI 消息相同，只是 provider 请求格式不同：

| Spring AI 输入 | OpenAI 请求 | Anthropic 请求 |
| --- | --- | --- |
| `UserMessage.text` | `text` 内容块 | `text` 内容块 |
| `Media(image/*, byte[])` | `image_url` + data URL | `image` + base64 source |
| `Media(image/*, URL)` | 图片 URL | URL image source |

因此项目上层不需要按 provider 改造 `Prompt.messages`。切换 provider 后，仍然发送相同的 `UserMessage(text, media)`，由对应 `ChatModel` 转成自己的协议。

## 5. Qwen 和智谱为什么复用 OpenAI 通路

本项目没有使用独立的 Qwen 或智谱 ChatModel：

```text
QwenProvider
  -> OpenAiChatModel
  -> 百炼 OpenAI 兼容端点

ZhipuProvider
  -> OpenAiChatModel
  -> 智谱 OpenAI 兼容端点
```

所以两家使用的对象和消息转换过程都与第 3 节一致：

```text
Prompt.messages
  -> OpenAiChatModel 遍历全部消息
  -> 带 Media 的 UserMessage 转成 text + image_url
  -> SDK 请求发往对应的 OpenAI 兼容端点
```

本项目不需要为 Qwen、智谱再写图片序列化代码。但 `OpenAiChatModel` 能生成 `image_url`，只说明客户端能表达图片请求；远端兼容端点和所选模型仍必须真正接受这种格式。

## 6. 协议支持、端点支持和模型支持

图片能否最终到达模型，需要同时满足三层条件：

```text
客户端能够生成图片请求格式
  +
远端端点接受该图片请求格式
  +
所选模型支持图片输入
```

三层含义不同：

- **客户端支持**：`OpenAiChatModel` 或 `AnthropicChatModel` 能把 `Media` 转成请求内容块；
- **端点支持**：OpenAI 兼容网关实际接受并转发 `image_url`；
- **模型支持**：当前模型本身具备视觉能力。

图片处理层通过当前 provider 的 `capabilities(modelId)` 决定是否给本次 Prompt 增加 `Media`；原生 provider 的默认实现名单来自 `VisionModels`，聚合网关可以收紧为网关实测子集。具体能力闸门、全局开关和未知模型策略见[图片处理实现原理](image-processing.md)。

### OpenCode Go 只开放官方视觉模型

OpenCode Go 也复用 `OpenAiChatModel`，客户端能够生成 `image_url`。OpenCode Go 官方文档目前明确列出 `deepseek-v4-flash-vision-exp`，并说明图片会按尺寸折算为输入 token，因此 `OpencodeGoProvider` 只为这个模型开放视觉兑现。

其他 OpenCode Go 内置模型及通过 `OPENCODE_GO_MODELS` 配置的自定义模型仍保持 `TEXT_ONLY`。这里不直接复用完整的全局视觉前缀名单，因为“模型本身支持图片”不等于“Go 网关已验证并稳定透传该模型的图片请求”。

## 7. 当前验证范围与已知边界

当前可以确认：

- Spring AI 2.0.0 的 `OpenAiChatModel` 原生遍历消息并把图片 `Media` 转成 OpenAI 图片内容块；
- Spring AI 2.0.0 的 `AnthropicChatModel` 原生遍历消息并把图片 `Media` 转成 Anthropic 图片内容块；
- 本项目 Qwen、智谱复用 `OpenAiChatModel`，不需要额外图片请求体改写；
- OpenAI 已有真实模型端到端视觉探针；
- Anthropic、Qwen 视觉模型和智谱视觉模型尚未在本项目中逐家完成真机视觉验证；
- OpenCode Go 官方声明 `deepseek-v4-flash-vision-exp` 支持图片，本项目据此开放该模型；当前改动未使用真实 Go key 执行端到端探针。

复用 `OpenAiChatModel` 只能证明请求能够按 OpenAI 图片格式组装，不能替远端兼容端点和具体模型作能力保证。

## 8. 关键类索引

| 类 | 作用 |
| --- | --- |
| `agent.OpenAiProvider` | 使用 Spring AI `OpenAiChatModel` 发送 OpenAI 请求 |
| `agent.AnthropicProvider` | 使用 Spring AI `AnthropicChatModel` 发送 Anthropic 请求 |
| `agent.QwenProvider` | 使用 `OpenAiChatModel` 访问百炼兼容端点 |
| `agent.ZhipuProvider` | 使用 `OpenAiChatModel` 访问智谱兼容端点 |
| `agent.OpencodeGoProvider` | 使用 OpenAI 兼容通路，仅为官方声明的 `deepseek-v4-flash-vision-exp` 开放视觉能力 |
| `org.springframework.ai.openai.OpenAiChatModel.createRequest` | 遍历 Spring AI 消息并生成 OpenAI SDK 请求对象 |
| `org.springframework.ai.anthropic.AnthropicChatModel.createRequest` | 遍历 Spring AI 消息并生成 Anthropic SDK 请求对象 |

本文从 `UserMessage.media` 开始，到 OpenAI、Anthropic 和 OpenAI 兼容端点的请求内容块结束。前一阶段见[图片处理实现原理](image-processing.md)。
