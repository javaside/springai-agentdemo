# DeepSeek 视觉能力实现原理

## 1. 背景：模型支持，框架适配层却丢失了图片

DeepSeek 官方视觉模型支持在一次对话请求中同时接收文本和图片。但项目使用的
`spring-ai-deepseek` 实现，在序列化消息时主要读取 `Message.getText()`：

- `UserMessage` 中的文本可以正常序列化；
- `UserMessage` 中的 `Media` 不会自动转换成 DeepSeek 需要的图片内容；
- DeepSeek 请求里的 `content` 需要能够表示文本块和图片块的数组，而框架当前的消息对象只能自然地落成字符串。

因此，问题不是“如何让模型认识图片”，而是：

> Spring AI 对象模型里已经有图片，但图片在转换成 DeepSeek HTTP JSON 时被丢掉了。

项目没有重新实现一套 ChatModel，而是保留 Spring AI 原有的消息处理流程，在 HTTP 请求真正发出前补齐图片。

本文只解释 DeepSeek 的特殊适配。图片如何生成 `UserMessage.media`，见[图片处理实现原理](image-processing.md)；其他 provider 如何原生转换同一个 `Media`，见[其他 Provider 视觉能力实现原理](native-vision-providers.md)。

## 2. 一页看懂：图片如何进入 DeepSeek 请求

整个视觉实现先只看两条消息流。暂时不用理解所有 Spring AI 类名，只要看清楚“哪条消息在什么时候携带图片”。

### 2.1 用户直接附图：原 UserMessage 携带图片

用户在输入框中输入：

```text
请分析这个报错界面 docs/bug.png
```

用户图片不经过工具调用，而是在这次用户请求中直接处理。详细过程是：

```text
用户提交消息
  └── 原文中的图片路径保留，并追加 file_reference 图片引用

VisionMaterializer 处理这次请求的 UserMessage
  ├── text  = 用户原文（包含 docs/bug.png）+ 图片引用
  └── media = 从 docs/bug.png 读取并准备好的图片

DeepSeekThinkingChatModel 在序列化前登记 media
  └── media 作为对象层的图片中转载体，不是最终 HTTP 格式

spring-ai-deepseek 序列化 UserMessage
  └── text 被序列化，media 被遗漏

DeepSeek HTTP 层补图
  └── 根据序列化前的登记，把图片补成 image_url/file

包含文本和图片的本次请求发送给大模型
```

把这段过程压缩成一条消息链，就是：

```text
用户输入图片路径
  -> UserMessage(text = 用户原文 + 图片引用)
  -> 原 UserMessage 增加 media
  -> spring-ai-deepseek 序列化时遗漏 media
  -> HTTP 层补 image_url/file
  -> 本次请求发给大模型
```

这里的关键是：`UserMessage.media` 只是序列化前的图片中转载体。它会被 `DeepSeekThinkingChatModel` 先登记；即使随后被 Spring AI 原有序列化逻辑遗漏，HTTP 层仍能从登记中取回图片。

对比补图前后，同一条 user 消息会从：

```json
{
  "role": "user",
  "content": "请分析这个报错界面 docs/bug.png\n\n<file_reference>图片引用</file_reference>"
}
```

变成：

```json
{
  "role": "user",
  "content": [
    {
      "type": "text",
      "text": "请分析这个报错界面 docs/bug.png\n\n<file_reference>图片引用</file_reference>"
    },
    {
      "type": "image_url",
      "image_url": {
        "url": "data:image/png;base64,..."
      }
    }
  ]
}
```

用户原文中的 `docs/bug.png` 和追加的图片引用都没有丢；HTTP 层只是在保留原文本的基础上增加图片块。

关键结论：

> 用户第一次发送图片时，图片就在本次请求中发送给模型；流程中没有模型调用 `Read`，`Read` 只用于以后重新查看历史图片。

### 2.2 工具读取图片：消息链追加一条 UserMessage

这里以 `Read` 读取图片为例；MCP 截图等其他返回图片的工具也走同一条消息链。图片是在工具执行后才进入消息链的，详细过程是：

```text
第 1 次请求发送给大模型
  └── UserMessage：用户最初的问题

大模型返回工具调用
  └── AssistantMessage：tool_calls = Read(screenshot.png)

项目执行 Read
  └── ToolResponseMessage：responseData = 图片引用文本

Spring AI 工具调用循环再次调用 ChatModel
  └── VisionMaterializer 在进入 provider 前追加 UserMessage
      ├── text  = 工具图片说明
      └── media = 工具产生的图片

DeepSeek HTTP 层补图
  └── 把新增 UserMessage.media 改写成 image_url/file

包含上述全部消息的请求发送给大模型
```

把这段过程压缩成一条消息链，就是：

```text
UserMessage
  -> AssistantMessage(tool_calls)
  -> ToolResponseMessage(responseData = 图片引用)
  -> 新增 UserMessage(media = 工具图片)
  -> HTTP 层补 image_url/file
  -> 完整 messages 发给大模型
```

新增的 `UserMessage` 不会替换 `ToolResponseMessage`；原用户消息、assistant 工具调用、tool 结果和新增的图片 user 消息都会保留。省略工具协议中与视觉无关的 id、参数等字段后，发送给 DeepSeek 的 `messages` 可以看成：

```json
{
  "messages": [
    {"role": "user",      "content": "用户最初的问题"},
    {"role": "assistant", "tool_calls": "Read(screenshot.png)"},
    {"role": "tool",      "content": "<file_reference>图片引用</file_reference>"},

    {
      "role": "user",
      "content": [
        {
          "type": "text",
          "text": "以下是工具结果中引用的图片：screenshot.png"
        },
        {
          "type": "image_url",
          "image_url": {
            "url": "data:image/png;base64,..."
          }
        }
      ]
    }
  ]
}
```

前三条消息对应 `UserMessage -> AssistantMessage(tool_calls) -> ToolResponseMessage`，只保留在这里是为了维持完整的工具调用上下文。视觉处理的重点是最后一条新增的 user 消息：它把工具图片说明放进文本块，把真正的图片放进 `image_url` 块。

### 2.3 两条图片消息链的区别

| 图片来源 | 图片挂到哪条消息 | 是否新增消息 | 何时发送 |
| --- | --- | --- | --- |
| 用户直接附图 | 原来的 `UserMessage` | 否，只给原消息增加 `media` | 随用户提交的本次请求发送 |
| 工具读取图片 | 工具结果后新增的 `UserMessage` | 是，`ToolResponseMessage` 保留不变 | 随工具执行结束后的模型请求发送 |

历史图片需要重新查看时，模型调用 `Read`，然后重新走第二条“工具读取图片”消息链。

## 3. 为什么会话只保存图片引用

第 2 节中的 `Media` 和图片 JSON 都只服务于当前请求，不会写回会话历史。会话长期保存的只有文本引用：

```text
用户直接附图：UserMessage.text = 用户原文 + file_reference
工具读取图片：ToolResponseMessage.responseData = file_reference
```

这样设计有三个原因：

- 图片字节不会随着历史消息在每次请求中反复累积；
- 会话恢复和压缩只需处理文本；
- 模型以后需要重看图片时，仍能根据引用里的路径调用 `Read`，重新进入 2.2 的工具读取图片消息链。

引用路径还承担安全边界：

- **项目内图片**直接引用原文件，文件更新后再次读取能看到新内容；
- **项目外图片**先复制到 `.codetui/artifacts/`，再引用项目内副本；
- `FileReferenceParser` 只接受项目根目录内的路径，避免引用文本读取任意系统文件。

因此，会话历史和当前请求的职责不同：

```text
会话历史：保存图片在哪里
当前请求：临时读取并发送图片内容
```

## 4. DeepSeek 如何把 Media 补回 HTTP 请求

第 2 节已经说明图片挂在哪条消息上。本节只解释 `UserMessage.media` 如何穿过不支持它的 DeepSeek 序列化层。

### 4.1 序列化前登记图片

`DeepSeekThinkingChatModel` 在调用原生 DeepSeek delegate 之前，扫描 `Prompt.messages` 中所有 `UserMessage` 的 `media`，把图片登记到 `DeepSeekVisionMediaRegistry`：

```text
DeepSeekThinkingChatModel
  -> 读取 UserMessage.media
  -> 登记“第几个 user 消息的第几张图片”
  -> 调用 spring-ai-deepseek 原生模型
```

登记表是对象层到 HTTP 层的一次性旁路。HTTP 改写器取出图片时会立即删除对应记录，请求结束后再清理未消费记录。纯文本请求不接触注册表。

### 4.2 为什么按 user 消息序号对齐

不能使用 `Prompt.messages` 的绝对下标。`spring-ai-deepseek` 会把一条含多项结果的 `ToolResponseMessage` 展开成多条 JSON tool 消息，因此：

```text
对象层消息下标 != 序列化后 JSON messages 下标
```

但一条 `UserMessage` 始终对应一条 `role=user` JSON 消息，相对顺序不会变化，所以登记和消费两侧都只统计 user 消息：

```text
第 N 条 UserMessage + 第 M 个 Media
        <=>
注册表 key = N:M
        <=>
第 N 个 role=user JSON 消息的第 M 张图片
```

没有图片的 user 消息也必须占一个序号，否则后续图片仍会错位。

### 4.3 HTTP 请求体改写

`DeepSeekThinkingBodyCodec` 遍历已经序列化的 JSON，按出现顺序为每条 `role=user` 消息计算 user 序号。每遇到一条 `role=user`，改写器先计算它是第几条 user 消息，再检查注册表中有没有属于这条消息的 `Media` 登记。

这里不区分历史消息和当前消息。`DeepSeekThinkingChatModel` 会扫描本次出站 Prompt 中的**所有** `UserMessage`：只要某条消息的 `media` 中存在图片字节非空的 `Media`，就会登记；`DeepSeekThinkingBodyCodec` 随后就会给这条消息补图。历史 `UserMessage` 如果仍带有 `Media`，同样会被补进 HTTP 请求。

正常的会话处理流程只把图片引用文本写入历史，不把 `Media` 写回历史，所以历史消息通常不会命中 Registry。这是上游会话和图片处理的结果，不是 Registry 或 HTTP 改写器专门过滤了历史消息。具体的图片处理过程见[图片处理实现原理](image-processing.md)。

后续只有两个分支：

```text
role=user 消息
  -> 计算 user 消息序号
  -> 检查 Registry 中是否有该 user 的 Media 登记
      ├── 没有 Media（Registry 未命中）
      │     -> 不修改这条消息
      │     -> content 仍是原来的字符串
      │
      └── 有 Media（Registry 命中）
            -> 原 content 放进第一个 text 块
            -> 后面依次追加 image_url/file 图片块
            -> 写回 content 数组
```

注册表使用的是一次性读取：Media 登记被取出后立即删除，避免被第二次改写使用。

是否改写只取决于本次出站 Prompt 里这条消息的 `media`，与它是历史消息还是当前消息无关：

```text
任意 UserMessage（历史或当前）
  ├── 没有可登记的 Media
  │     -> Registry 无对应 key
  │     -> HTTP content 保持字符串
  │
  └── 有图片字节非空的 Media
        -> Registry 有对应 key
        -> HTTP content 改成 [text, image]
```

当前项目正常组装 Prompt 时，历史消息只带引用文本，因此通常走第一个分支；如果其他调用方传入了仍带 `Media` 的历史 `UserMessage`，它会走第二个分支。

默认使用 base64 内联：

```json
{
  "type": "image_url",
  "image_url": {
    "url": "data:image/png;base64,..."
  }
}
```

设置 `DEEPSEEK_VISION_TRANSPORT=files` 后，可以改用 Files API：

```json
{
  "type": "file",
  "file_id": "file-..."
}
```

Files API 只优化重复传输的带宽和延迟，不改变消息链，也不节省图片 token。上传失败时自动退回内联格式。

流式请求由 `DeepSeekThinkingClientHttpConnector` 在发送前收集并改写请求体；非流式请求走对应的 request interceptor。两条路径只改请求 JSON，不改变响应处理。

## 5. 关键类索引

理解第 2 节的消息链后，按下面顺序看代码即可：

| 类 | 作用 |
| --- | --- |
| `ui.CodeTuiView.injectAttachments` | 把用户输入的图片路径追加成文本引用 |
| `agent.media.MediaExternalizingCallback` | 把工具读取到的图片保存并替换成工具结果引用 |
| `agent.media.VisionMaterializer` | 从引用读取图片；用户图改原 `UserMessage`，工具图追加新 `UserMessage` |
| `agent.media.VisionMaterializingChatModel` | 在 Prompt 进入 provider 前触发图片兑现 |
| `agent.DeepSeekThinkingChatModel` | 序列化前登记所有 `UserMessage.media` |
| `agent.media.DeepSeekVisionMediaRegistry` | 在对象层和 HTTP 层之间临时传递图片 |
| `agent.DeepSeekThinkingBodyCodec` | 把登记的图片补进 DeepSeek JSON |
| `agent.DeepSeekThinkingClientHttpConnector` | 拦截并改写流式请求体 |
| `agent.media.DeepSeekFileStore` | Files API 模式下上传图片并复用 `file_id` |

## 6. 验证范围与已知边界

当前实现和测试覆盖：

- 用户直接附图，并在本次请求中发送；
- 工具读取图片后生成引用并追加图片 `UserMessage`；
- DeepSeek 内联 base64 请求体改写；
- 流式和非流式请求体处理；
- user 消息序号和 media 序号对齐；
- Files API 的单测路径和上传失败降级。

需要注意：

- DeepSeek 内联通道已做真机视觉验证；
- Files API 通道目前主要由单测覆盖；
- 同一回合多次工具迭代可能重复发送当轮图片，这是无状态请求的固有成本；
- `CODETUI_VISION=off` 会关闭图片兑现，但不会删除会话中的图片引用。
