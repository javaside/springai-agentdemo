# 其他 Provider 视觉能力实现原理

## 1. 这篇文档解决什么问题

[图片处理实现原理](image-processing.md)已经说明，项目如何把用户图片或工具图片变成当前出站 `UserMessage.media`。

本文从这里继续，只回答一个问题：

> 已经带有 `Media` 的 `UserMessage` 进入 provider 后，OpenAI、Anthropic、Qwen 和智谱如何把图片写入 HTTP 请求？

本文不再解释图片路径、artifact、`file_reference`、预算和会话生命周期，也不展开 DeepSeek 的补图实现。DeepSeek 因 Spring AI 原生序列化遗漏 `Media`，需要单独处理，见 [DeepSeek 视觉能力实现原理](deepseek-vision.md)。

## 2. 一页看懂：同一条消息如何进入不同 Provider

进入 provider 前，各家的对象层消息相同：

```text
Prompt.messages
  -> UserMessage
       text  = 图片说明
       media = [Media(data = 图片字节, mimeType = image/png)]
```

进入 provider 后开始分流：

```text
UserMessage(text, media)
  |
  ├── OpenAI
  |     -> OpenAiChatModel 读取 text 和 media
  |     -> 生成 text + image_url 内容块
  |
  ├── Anthropic
  |     -> AnthropicChatModel 读取 text 和 media
  |     -> 生成 text + image 内容块
  |
  ├── Qwen / 智谱
  |     -> 本项目复用 OpenAiChatModel
  |     -> 生成 OpenAI 兼容的 text + image_url 内容块
  |
  └── DeepSeek
        -> DeepSeekChatModel 原生序列化遗漏 media
        -> 由项目 HTTP 层补图
```

因此，“其他 provider 不需要特殊处理”的准确含义是：

> 本项目不需要再写 Registry 或 HTTP 请求体改写器；Spring AI 的 `OpenAiChatModel` 和 `AnthropicChatModel` 已经负责把 `UserMessage.media` 转成各自协议的图片内容块。

这不代表所有模型都支持图片，也不代表所有 OpenAI 兼容端点都一定支持图片透传。

## 3. OpenAI：Spring AI 原生转换 Media

### 3.1 消息如何变化

对象层消息：

```text
UserMessage
  text  = "分析这张截图"
  media = [Media(image/png, byte[])]
```

`OpenAiChatModel` 创建请求时会同时读取文本和 `media`：

```text
UserMessage.text
  -> text 内容块

UserMessage.media
  -> image_url 内容块
```

概念上的 HTTP 内容是：

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

### 3.2 byte[] 为什么可以直接使用

当前项目创建的 `Media.data` 是 `byte[]`。`OpenAiChatModel` 会：

```text
图片 byte[]
  -> Base64
  -> data:<mime>;base64,...
  -> image_url.url
```

如果 `Media.data` 本身是 `URI` 或 URL 字符串，OpenAI 转换层也可以直接把它作为图片 URL。当前项目选择 `byte[]`，是因为图片已经在上游完成读取、格式处理和预算控制。

### 3.3 为什么本项目不需要再改 HTTP

OpenAI 的转换发生在 Spring AI provider 内部：

```text
Prompt
  -> OpenAiChatModel.createRequest(...)
  -> OpenAI SDK 请求对象
  -> HTTP JSON
```

`Media` 没有在序列化过程中丢失，所以本项目只需正常构造 `UserMessage.media`，不需要增加注册表、拦截器或请求体改写器。

## 4. Anthropic：Spring AI 原生转换为图片块

### 4.1 消息如何变化

对象层仍是同一条消息：

```text
UserMessage
  text  = "分析这张截图"
  media = [Media(image/png, byte[])]
```

`AnthropicChatModel` 创建请求时会构造 Anthropic Messages API 的内容块：

```text
UserMessage.text
  -> text block

UserMessage.media
  -> image block
       source = base64 图片或 HTTPS URL
```

概念上的请求内容是：

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

### 4.2 与 OpenAI 的区别在哪里

两家看到的 Spring AI 消息相同，区别只在 provider 适配层：

| Spring AI 输入 | OpenAI 请求 | Anthropic 请求 |
| --- | --- | --- |
| `UserMessage.text` | `text` 内容块 | `text` 内容块 |
| `Media(image/*, byte[])` | `image_url` + data URL | `image` + base64 source |
| `Media(image/*, URL)` | 图片 URL | URL image source |

因此项目上层不需要按 provider 改造 `Prompt.messages`。切换模型后，仍然发送同一种 `UserMessage(text, media)`，由对应 `ChatModel` 转成自己的协议。

## 5. Qwen 和智谱：复用 OpenAI 视觉通路

### 5.1 为什么它们也走 OpenAiChatModel

本项目没有使用独立的 Qwen 或智谱 ChatModel：

```text
QwenProvider
  -> OpenAiChatModel
  -> 百炼 OpenAI 兼容端点

ZhipuProvider
  -> OpenAiChatModel
  -> 智谱 OpenAI 兼容端点
```

因此它们接收 `UserMessage.media` 后，Spring AI 仍按 OpenAI 格式生成：

```text
text + image_url
```

本项目不需要为 Qwen、智谱再写一套图片序列化代码。

### 5.2 协议兼容不等于模型支持视觉

`OpenAiChatModel` 能生成 `image_url`，只说明客户端具备这种请求表达能力。请求最终能否成功，还取决于：

```text
兼容端点是否接受 image_url
  +
所选模型是否支持图片输入
```

所以项目仍由 `VisionModels` 按模型 ID 判断视觉能力：

- Qwen 视觉模型使用 `qwen-vl`、`qwen2-vl`、`qwen2.5-vl`、`qwen3-vl` 等前缀；
- 智谱视觉模型使用 `glm-4v`、`glm-4.1v`、`glm-4.5v` 等前缀；
- 不在名单中的未知模型默认按不支持图片处理。

当前内置的 Qwen 和智谱模型清单主要是文本或编码模型。用户即使通过环境变量加入其他模型 ID，也只有命中视觉名单时，项目才会把 `Media` 交给 provider。

## 6. 为什么 DeepSeek 不能走同一条路

OpenAI 和 Anthropic 的 Spring AI provider 都会主动读取 `UserMessage.media`：

```text
UserMessage.media
  -> provider 请求内容块
```

本项目使用的 `spring-ai-deepseek 2.0.0` 在消息序列化时主要读取文本，`Media` 不会自动进入请求：

```text
UserMessage(text, media)
  -> DeepSeek 原生序列化
  -> role=user + 字符串 content
  -> media 丢失
```

所以 DeepSeek 才需要：

```text
序列化前登记 Media
  -> 原生序列化
  -> HTTP 层把登记的 Media 补回 content
```

这不是项目对所有 provider 的通用视觉方案，而是 DeepSeek 适配层的例外。具体实现见 [DeepSeek 视觉能力实现原理](deepseek-vision.md)。

## 7. 模型能力闸门解决什么问题

所有可用 provider 都会被 `VisionMaterializingChatModel` 包装，但只有 `VisionModels.supportsImage(modelId)` 返回 `true` 时，图片引用才会兑现为 `Media`。

```text
当前模型 id
  -> VisionModels.supportsImage(...)
      ├── false：保留引用，不增加 Media
      └── true ：增加 Media，交给 provider
```

能力按模型判断，不按 provider 判断，因为同一家通常同时提供文本模型和视觉模型。

未知模型默认不支持图片，是为了避免错误方向更昂贵：

- 误判为不支持：用户看到拦截提示，可以换模型；
- 误判为支持：图片完成读取和上传后，provider 可能返回 400。

`CODETUI_VISION=off` 会让所有模型暂时判定为不支持图片，作为关闭视觉上传和费用的全局开关。

### OpenCode Go 为什么仍是纯文本

OpenCode Go 也复用 `OpenAiChatModel`，客户端技术上能够生成 `image_url`。但该聚合网关是否对不同上游稳定透传图片尚未验证，因此 `OpencodeGoProvider` 沿用默认 `TEXT_ONLY`，不会开放视觉兑现。

这再次说明：

```text
客户端能生成图片格式
  != 端点已验证支持图片
  != 当前模型支持图片
```

## 8. 当前验证范围与边界

当前可以确认：

- Spring AI 2.0.0 的 `OpenAiChatModel` 原生读取图片 `Media`，生成 OpenAI 图片内容块；
- Spring AI 2.0.0 的 `AnthropicChatModel` 原生读取图片 `Media`，生成 Anthropic 图片内容块；
- 本项目 Qwen、智谱复用 `OpenAiChatModel`，不需要额外图片请求体改写；
- OpenAI 已有真实模型端到端视觉探针；
- DeepSeek 的内联视觉通道已做真机验证；
- Anthropic、Qwen 视觉模型和智谱视觉模型当前主要依据 Spring AI 原生实现、协议兼容性和单元测试边界，未在本项目中逐家完成真机视觉验证；
- OpenCode Go 未验证图片透传，因此明确保持纯文本能力。

还要注意：Qwen、智谱或自定义 OpenAI 兼容地址是否接受图片，最终由实际端点决定。复用 `OpenAiChatModel` 只能证明请求能按 OpenAI 图片格式组装，不能替远端服务作能力保证。

## 9. 关键类索引

| 类 | 作用 |
| --- | --- |
| `agent.media.VisionModels` | 按模型 ID 和全局开关决定是否兑现图片 |
| `agent.media.VisionMaterializingChatModel` | 在 Prompt 进入 provider 前触发图片兑现 |
| `agent.OpenAiProvider` | 使用原生 `OpenAiChatModel` 发送 OpenAI 请求 |
| `agent.AnthropicProvider` | 使用原生 `AnthropicChatModel` 发送 Anthropic 请求 |
| `agent.QwenProvider` | 使用 `OpenAiChatModel` 访问百炼兼容端点 |
| `agent.ZhipuProvider` | 使用 `OpenAiChatModel` 访问智谱兼容端点 |
| `agent.OpencodeGoProvider` | 使用 OpenAI 兼容通路，但当前不开放视觉能力 |
| `agent.DeepSeekThinkingChatModel` | DeepSeek 序列化前登记 `Media` |
| `agent.DeepSeekThinkingBodyCodec` | DeepSeek HTTP 层补回图片内容块 |

三篇文档的阅读顺序是：

```text
图片处理实现原理
  -> 图片如何变成 UserMessage.media

其他 Provider 视觉能力实现原理（本文）
  -> OpenAI / Anthropic / 兼容端点如何原生序列化 Media

DeepSeek 视觉能力实现原理
  -> DeepSeek 为什么需要额外补图，以及如何补图
```
