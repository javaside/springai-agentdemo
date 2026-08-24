# DeepSeek 视觉能力实现原理：Prompt.messages 消息处理链

## 1. 先说结论：图片经历了两种完全不同的表示

这个项目的视觉能力，核心不是把图片一直放在某个对象里，而是让图片在消息链的不同阶段使用不同表示：

```text
图片文件
  -> file_reference 文本
  -> Spring AI Message 中的文本
  -> 出站时临时生成 Media
  -> DeepSeek HTTP JSON 中的图片块
```

必须先区分两件事：

- **图片引用**：一段普通文本，包含图片路径、类型、尺寸、交付状态等信息；
- **图片 Media**：真正包含图片字节的 Spring AI `Media` 对象，只有在出站前才临时生成。

因此，用户输入图片后：

1. 图片路径不会丢；
2. 图片引用不是放在 `UserMessage.context` 里，而是作为文本进入 `UserMessage.text`；
3. 图片字节不会进入会话历史；
4. 真正发给模型时，系统才根据引用读取图片，临时把它放进 `UserMessage.media`；
5. DeepSeek 的适配层最后再把 `Media` 改写成 API 要求的 JSON 图片块。

整条链路围绕的就是同一组消息在不同阶段如何变化。

## 2. Spring AI 中的 Prompt 和 messages

Spring AI 发起一次模型调用时，核心对象可以抽象成：

```text
Prompt
  ├── messages / instructions: List<Message>
  └── options: ChatOptions
```

项目里真正要发送给模型的历史、当前用户消息、assistant 的工具调用、工具返回结果，最终都会组成这份消息列表：

```text
Prompt.messages
  ├── SystemMessage
  ├── UserMessage
  ├── AssistantMessage
  │     └── tool_calls
  ├── ToolResponseMessage
  └── ...
```

与视觉相关的消息类型有三个：

| 消息类型 | 图片相关内容 | 在本项目中的作用 |
| --- | --- | --- |
| `UserMessage` | 有文本，也可以有 `Media` | 用户消息；也是最终承载图片 `Media` 的消息 |
| `AssistantMessage` | 文本、tool calls | 模型输出的工具调用；不承载本项目要发送的输入图片 |
| `ToolResponseMessage` | 每个 `ToolResponse` 主要是 `id`、`name`、`responseData` | 工具结果；没有可直接放图片的 `media` 字段 |

这里特别容易误解：项目没有把图片引用放入 `UserMessage.context`。当前实现把引用直接放在消息文本中：

```text
UserMessage
  ├── text: 用户原文 + file_reference 文本
  ├── media: 通常为空（会话阶段）
  └── metadata/context: 不承担图片引用的持久化职责
```

出站阶段会基于这条消息创建一个改写后的消息副本，副本才会增加 `media`。

## 3. 一次完整回合的消息链

先看一轮带图片、并触发工具调用的抽象过程：

```text
输入框
  |
  | 用户输入：请分析这张图 docs/bug.png
  v
提交文本
  |
  | 路径被识别为图片，并注入 file_reference 文本
  v
UserMessage(text = 用户原文 + 引用，media = 空)
  |
  | SessionMemoryAdvisor 等消息机制组装 Prompt
  v
Prompt.messages
  |
  | 模型返回 assistant(tool_calls)
  v
AssistantMessage(tool_calls)
  |
  | 执行工具
  v
ToolResponseMessage(responseData = 文本或 file_reference)
  |
  | 下一次模型调用前，VisionMaterializer 处理当前回合图片
  v
出站 Prompt.messages
  ├── 原始 UserMessage 的文本引用 + 临时 Media
  ├── AssistantMessage(tool_calls)
  ├── ToolResponseMessage(仍然是文本引用)
  └── 可能追加 synthetic UserMessage(工具图片清单 + 临时 Media)
  |
  | DeepSeekThinkingChatModel 登记 Media
  v
spring-ai-deepseek 序列化出的普通 JSON
  |
  | HTTP 层补回图片块
  v
DeepSeek API JSON
```

要点是：**工具结果仍然是 `ToolResponseMessage`，图片不会被塞进工具结果对象；真正带图片的消息是出站阶段追加或改写的 `UserMessage`。**

## 4. 用户发消息携带图片：路径如何处理

### 4.1 输入框阶段：图片路径仍然属于用户原文的一部分

用户输入：

```text
请分析这个报错界面 docs/bug.png
```

输入框会识别出 `docs/bug.png` 是图片路径，但不会把它从用户文本中删除。项目会把图片信息以引用块的形式注入到待发送文本中，概念上类似：

```text
请分析这个报错界面 docs/bug.png

<file_reference>
kind: image
path: docs/bug.png
mime_type: image/png
dimensions: 1440x900
delivery: not_in_view
</file_reference>
```

因此，用户原文中的路径有两份语义：

- 原文中的路径仍然让模型知道用户提到了哪个文件；
- 引用块提供结构化信息，供后续解析器定位和兑现图片。

这也是为什么取消附件时只取消“图片附件语义”，不会自动删除用户输入里的路径：用户可能只是想讨论这个路径，而不是上传图片。

### 4.2 项目内路径和项目外路径

图片引用里的 `path` 必须最终指向项目根目录内的文件，因为后续 `FileReferenceParser` 会进行路径包含校验。

因此：

```text
项目内图片：直接引用原路径
项目外图片：先复制到 .codetui/artifacts/，再引用副本
```

项目外图片如果直接把原始绝对路径写进引用，后续解析器会拒绝整个引用块，图片就无法兑现。复制到 artifacts 是让路径既安全又可被后续消息链继续处理。

### 4.3 提交阶段：引用作为文本进入 UserMessage

`CodeTuiView` 最终提交的是注入引用后的字符串，而不是一个单独的“图片消息对象”。之后 `CodingAgent` 和会话消息机制把这段文本加入当前回合，形成 Spring AI 的 `UserMessage`。

此时的消息可以抽象为：

```text
UserMessage {
    text  = "请分析这个报错界面 ... <file_reference> ..."
    media = []
}
```

此时图片路径没有丢，图片字节也还没有进入消息。

会话持久化保存的也是这类文本消息。换句话说，会话文件里保留的是：

```text
用户原文 + file_reference 文本
```

而不是：

```text
用户原文 + 图片字节
```

## 5. 用户图片何时变成 Media

### 5.1 不在会话阶段生成 Media

项目没有在用户按回车时就把图片字节塞进会话。原因是此时还不适合决定：

- 当前 provider 和模型是否支持图片；
- 图片是否超过本次请求的张数和 token 预算；
- 图片格式是否需要缩放、转码或拒绝；
- 图片是否属于当前回合，而不是历史图片。

所以视觉处理被放在 `VisionMaterializingChatModel`，也就是 ChatModel 真正接收 Prompt、但尚未把请求交给 provider 的位置。

### 5.2 VisionMaterializer 如何扫描 Prompt.messages

出站时，`VisionMaterializer` 接收一份即将发送的 `Prompt`，然后处理它的 `messages`：

1. 找到最后一条**非合成** `UserMessage`，作为当前回合锚点；
2. 解析这条用户消息文本中的全部图片引用；
3. 对历史消息中的工具结果，继续向后扫描当前锚点之后的 `ToolResponseMessage`；
4. 去重并按用户图、工具图分别应用预算；
5. 读取图片文件并进行格式、尺寸、像素数检查；
6. 必要时缩放或转码，得到可以发送的字节；
7. 用这些字节创建 Spring AI `Media`；
8. 返回一份新的出站 `Prompt`。

用户消息的出站形态变成：

```text
UserMessage（会话中）
  text  = 用户原文 + file_reference(delivery: not_in_view)
  media = []

UserMessage（出站副本）
  text  = 用户原文 + file_reference(delivery: delivered)
  media = [Media(byte[] 图片内容)]
```

这里的 `delivery` 文本也会在出站副本中更新：

- 成功兑现：`delivered`；
- 当前模型不支持：`reference_only`；
- 当前请求或回合预算不足：对应的预算状态。

这些文本更新只作用于出站副本，不会把临时状态回写成会话历史。会话中的引用仍然是稳定的文件引用。

### 5.3 为什么只兑现当前回合

`Prompt.messages` 里可能包含很多历史图片引用。如果每次请求都把历史图片重新读出并放入 `Media`，会造成：

- 每一轮都重复发送旧图片；
- 图片不断占用模型上下文；
- 工具循环中请求体越来越大。

所以项目用位置规则定义当前回合：

```text
当前回合起点 = 最后一条非合成 UserMessage
当前回合图片 = 该消息及其之后消息里的图片引用
```

历史图片不会自动兑现。模型需要重新查看历史图片时，可以调用 `Read` 读取引用路径；这次读取产生的新工具结果属于当前回合，又会进入视觉处理链。

## 6. 工具调用：为什么图片不能直接放进 ToolResponseMessage

### 6.1 Spring AI 的工具消息形状

模型决定调用工具时，消息链通常是：

```text
AssistantMessage
  tool_calls = [
    {id: "call-1", name: "Read", arguments: "{...}"}
  ]

ToolResponseMessage
  responses = [
    {id: "call-1", name: "Read", responseData: "..."}
  ]
```

`ToolResponseMessage.ToolResponse` 的结果本质上是文本数据。它没有像 `UserMessage` 那样的 `media` 列表，不能直接表达：

```text
ToolResponseMessage + [Media(image bytes)]
```

这不是 DeepSeek 专属问题，而是当前 Spring AI 工具结果消息模型的结构限制。因此，项目不能简单地把工具返回的图片字节放进 `ToolResponseMessage`。

### 6.2 工具结果先经过媒体外置

工具执行完成后，`MediaExternalizingCallback` 检查工具原始返回值：

```text
工具原始返回
  ├── 普通文本：原样保留
  ├── MCP 图片内容块：保存成 artifact
  ├── Read 返回的图片/二进制文件：定位原文件或复制到 artifacts
  └── 无法外置的疑似二进制：从会话内容中移除并给出提示
```

对于图片，处理结果是：

```text
图片字节 -> MediaArtifactStore 保存
         -> 生成 file_reference 文本
         -> 放进 ToolResponse.responseData
```

所以进入 `Prompt.messages` 的工具结果是：

```text
ToolResponseMessage {
    responses = [
        ToolResponse {
            id           = "call-1"
            name         = "Read"
            responseData = "<file_reference> ... </file_reference>"
        }
    ]
    media = 不存在
}
```

这里要特别强调：**工具调用阶段图片没有丢，而是从二进制变成了 artifact 文件，并在 `responseData` 中留下了文本引用。**

### 6.3 为什么工具结果不能直接保留二进制

这样处理有三个原因：

1. `ToolResponseMessage` 本身没有 `Media` 字段；
2. 图片字节直接进入工具结果会让会话和后续每次 Prompt 都膨胀；
3. 文本引用可以保留“这张图来自哪个工具、对应哪个文件”的关系。

文本工具结果仍然原样保留。例如 `Read` 读取源代码时，文本是模型的工作材料，不能像图片一样外置，否则后续 `Edit` 的精确匹配和跨回合复用会受影响。

## 7. 工具图片如何重新进入出站 Prompt

### 7.1 VisionMaterializer 扫描工具结果引用

在下一次模型调用前，`VisionMaterializer` 扫描当前回合锚点之后的消息：

```text
UserMessage                -> 读取用户消息中的图片引用
AssistantMessage           -> 跳过
ToolResponseMessage        -> 读取 responseData 中的图片引用
```

必须跳过 `AssistantMessage`。模型可能在自己的文字回复中复述一段看起来像引用的文本；如果无差别扫描，就会把模型编造或复述的路径当成真实图片来源。

### 7.2 合成一条带 Media 的 UserMessage

由于 `ToolResponseMessage` 没有 `media` 字段，工具图片不能原地添加到工具消息。项目采取的方式是：

```text
ToolResponseMessage（保留原样）
  responseData = 图片引用文本

        |
        | VisionMaterializer 读取引用、准备图片
        v

synthetic UserMessage
  text     = "以下是上面工具结果中引用的图片：\n- screenshot.png"
  media    = [Media(image bytes)]
  metadata = {"codetui.synthetic": true}
```

这条 synthetic `UserMessage` 是为了让图片拥有一个 Spring AI 能够承载 `Media` 的消息位置。它不是用户新输入，也不是工具结果的替代品。

合成消息的文本只列出真正兑现的图片名称，作用是帮助模型把 `Media` 和工具结果中的文件引用对应起来。原来的 `ToolResponseMessage` 不改，因为它仍然是 assistant tool call 的合法响应，修改它会破坏工具调用消息配对。

### 7.3 synthetic 标记为什么放 metadata

合成消息本身也是 `UserMessage`。如果下一次处理 Prompt 时只按类型找最后一个 `UserMessage`，这条合成消息可能被误认为新的用户回合锚点。

因此项目在 metadata 中写入：

```text
codetui.synthetic = true
```

锚点判断时只认“最后一条非合成 `UserMessage`”。这个标记是项目自己的消息约定，不依赖 Spring AI advisor 的内部行为。

在正常链路中，这条合成消息是给当前出站 Prompt 使用的临时消息，不是把图片字节写回会话历史。会话仍然保存原来的：

```text
AssistantMessage(tool_calls)
ToolResponseMessage(responseData = file_reference)
```

这就同时满足了两点：

- 模型本次请求能看到图片；
- 下一轮历史不会永久积累 `Media` 字节。

## 8. DeepSeek 看到的 Prompt.messages 与实际 HTTP JSON

### 8.1 进入 DeepSeek ChatModel 前的 Prompt

经过视觉兑现后，DeepSeek ChatModel 接收到的 Prompt 可能是：

```text
Prompt.messages
  ├── UserMessage
  │     text  = "请分析 ... <file_reference ...>"
  │     media = [Media(png bytes)]
  ├── AssistantMessage
  │     tool_calls = [...]
  ├── ToolResponseMessage
  │     responseData = "<file_reference ...>"
  └── UserMessage (synthetic)
        text  = "以下是上面工具结果中引用的图片：\n- screenshot.png"
        media = [Media(png bytes)]
```

此时 Spring AI 的对象模型已经正确表达了图片，但 DeepSeek 的适配层仍然存在一个问题：它序列化消息时主要使用文本，`UserMessage.media` 不会自动变成 DeepSeek 的图片内容数组。

### 8.2 `DeepSeekThinkingChatModel` 登记 Media

`DeepSeekThinkingChatModel` 在把 Prompt 交给真正的 DeepSeek delegate 前，扫描所有 `UserMessage`：

```text
UserMessage.media[0] -> key = user消息序号:0
UserMessage.media[1] -> key = user消息序号:1
```

图片字节暂存到 `DeepSeekVisionMediaRegistry`，然后让原有的 DeepSeek 序列化流程继续运行。

这里使用“第几个 user 消息”而不是 `Prompt.messages` 的绝对下标：

```text
对象层绝对下标  !=  序列化后 JSON 数组下标
```

因为序列化时某些 `ToolResponseMessage` 响应可能被展开成多条 JSON 消息，其他角色的数量变化会让绝对下标漂移。但每一条 `UserMessage` 都对应一条 `role=user` 消息，所以两边只按 user 消息计数即可对齐。

注册表概念上是：

```text
"2:0" -> 第 3 条 user 消息的第 1 张图片
"2:1" -> 第 3 条 user 消息的第 2 张图片
```

### 8.3 Spring AI DeepSeek 序列化后的 JSON

原有序列化器可能得到类似这样的 JSON：

```json
{
  "messages": [
    {
      "role": "user",
      "content": "请分析 ... <file_reference ...>"
    },
    {
      "role": "assistant",
      "tool_calls": []
    },
    {
      "role": "tool",
      "content": "<file_reference ...>"
    },
    {
      "role": "user",
      "content": "以下是上面工具结果中引用的图片：\\n- screenshot.png"
    }
  ]
}
```

注意：这份 JSON 里仍然看得到图片的**引用文本**，但看不到图片字节。`Media` 在普通序列化阶段被忽略，这就是需要 DeepSeek 专属改写层的原因。

## 9. HTTP 层如何把 Media 补回 DeepSeek JSON

### 9.1 请求体改写位置

`DeepSeekThinkingClientHttpConnector` 或非流式请求拦截器在 HTTP body 发出前拦截请求：

```text
Prompt.messages
  -> spring-ai-deepseek 序列化
  -> JSON byte[]
  -> DeepSeekThinkingBodyCodec
  -> 修改后的 JSON byte[]
  -> DeepSeek API
```

流式请求只改写请求体，响应仍然沿用原来的流式处理。

### 9.2 只改写 role=user

`DeepSeekThinkingBodyCodec.decorateVision` 扫描 JSON 的 `messages` 数组，只处理 `role=user`：

1. 按顺序统计当前是第几个 user 消息；
2. 如果 `content` 是字符串，先转换成文本块；
3. 按 `user序号:media序号` 从注册表取图片；
4. 把图片块追加到 `content` 数组；
5. 消费一个删除一个注册表 key。

例如，序列化后的：

```json
{
  "role": "user",
  "content": "请分析这张图"
}
```

被改写为：

```json
{
  "role": "user",
  "content": [
    {"type": "text", "text": "请分析这张图"},
    {
      "type": "image_url",
      "image_url": {
        "url": "data:image/png;base64,..."
      }
    }
  ]
}
```

工具结果对应的图片也会以同样方式追加到 synthetic `role=user` 消息中。DeepSeek HTTP 层不需要知道这张图片原来来自用户输入还是工具调用；它只看到某个 user 消息拥有需要追加的图片。

### 9.3 两种 DeepSeek 图片块

默认使用内联 base64：

```json
{
  "type": "image_url",
  "image_url": {
    "url": "data:image/png;base64,..."
  }
}
```

也可以启用 `DEEPSEEK_VISION_TRANSPORT=files`，使用：

```json
{
  "type": "file",
  "file_id": "file-..."
}
```

Files API 上传失败会退回内联，因为 Files API 只是传输优化，不应该成为视觉请求的必要依赖。

## 10. 四个容易混淆的问题

### 10.1 用户发消息里的图片路径会丢吗？

不会。

路径会保留在用户文本和结构化 `file_reference` 中。项目内路径引用原文件，项目外路径引用 artifacts 副本。只有图片二进制不会进入会话消息。

### 10.2 引用信息是在 `UserMessage.context` 里吗？

不是。

当前实现把引用作为文本注入 `UserMessage.text`。`UserMessage.media` 在会话阶段通常为空，出站阶段才由 `VisionMaterializer` 临时补上。图片引用和图片字节是两条不同的信息通道。

### 10.3 工具调用结果里包含图片吗？

工具结果里不直接包含 `Media` 图片。

`ToolResponseMessage` 的 `responseData` 里包含的是图片引用文本。图片文件已经落到 artifact 存储；下一次出站时，`VisionMaterializer` 读取引用并合成一条带 `Media` 的 synthetic `UserMessage`。

### 10.4 图片什么时候真正进入大模型请求？

分三个时刻看：

```text
进入会话：图片是引用文本
进入 Spring AI 出站 Prompt：图片是 UserMessage.media
进入 DeepSeek HTTP JSON：图片是 content 数组中的 image_url/file 块
```

只有最后一个阶段的请求体才包含符合 DeepSeek API 要求的图片表示。

## 11. 设计取舍

### 11.1 引用持久化，Media 临时化

会话只保存文本引用，避免图片字节污染历史和上下文；出站时临时读取，保证模型需要时能看到图片。

### 11.2 ToolResponseMessage 保持合法配对

`AssistantMessage(tool_calls)` 和 `ToolResponseMessage` 必须保持原有配对关系。工具图片不能通过修改工具消息结构解决，只能在旁边追加一个合法的 user 消息承载 `Media`。

### 11.3 用户图片优先于工具图片

当前回合用户图片最多 3 张，工具图片最多 1 张，并且还有视觉 token 和单回合累计限制。用户图片代表当前任务意图，因此优先于工具循环产生的截图。

### 11.4 纯文本请求保持原样

没有可注册 `Media` 时：

- 不写入视觉注册表；
- 不改写普通文本请求体；
- 不清理其他请求可能使用的注册表内容；
- DeepSeek 原有文本和思考逻辑保持不变。

## 12. 最小代码索引

按消息链阅读代码时，建议按以下顺序：

| 顺序 | 位置 | 关注点 |
| --- | --- | --- |
| 1 | `ui.CodeTuiView.injectAttachments` | 图片路径如何变成用户文本中的引用 |
| 2 | `agent.CodingAgent.submit` | 用户文本如何进入回合，以及历史文件外置时机 |
| 3 | `agent.media.MediaExternalizingCallback` | 工具返回的图片如何保存并转成引用 |
| 4 | `agent.media.SessionFileExternalizer` | 过往工具结果中的二进制文件如何换成引用 |
| 5 | `agent.media.VisionMaterializer` | 引用如何变成出站 `UserMessage.media` |
| 6 | `agent.media.VisionMaterializingChatModel` | 在 ChatModel 出站边界调用兑现逻辑 |
| 7 | `agent.DeepSeekThinkingChatModel.registerMedia` | `Media` 如何登记到一次性注册表 |
| 8 | `agent.media.DeepSeekVisionMediaRegistry` | user 序号与 media 序号的临时映射 |
| 9 | `agent.DeepSeekThinkingBodyCodec.decorateVision` | JSON 如何补成 DeepSeek 图片格式 |
| 10 | `agent.DeepSeekThinkingClientHttpConnector` | 流式请求体如何在 HTTP 前被改写 |

最终只需要记住这一条消息处理链：

```text
用户路径
  -> UserMessage.text 中的 file_reference
  -> ToolResponseMessage.responseData 中的 file_reference
  -> VisionMaterializer 生成临时 UserMessage.media
  -> DeepSeekVisionMediaRegistry
  -> DeepSeek HTTP JSON 的 content 图片块
```

> DeepSeek 视觉支持的本质，不是把图片永久塞进 Spring AI 消息，而是让图片在“引用文本 → 出站 Media → HTTP 图片块”这三个表示阶段之间安全、准确地完成转换。

## 13. 验证范围与边界

当前实现和测试覆盖了：

- 用户直接附图；
- 工具返回图片后转成引用；
- 工具图片合成 synthetic `UserMessage`；
- DeepSeek 内联 base64 请求改写；
- 流式和非流式请求体处理；
- user 序号与 media 序号对齐；
- Files API 的单测路径和失败降级路径。

需要注意：

- DeepSeek 内联通道已做真机视觉验证；
- Files API 通道目前主要由单测覆盖；
- 同一回合多次工具迭代可能重复发送当轮图片，这是无状态请求的固有成本；
- `CODETUI_VISION=off` 会关闭图片兑现，但不会删除消息中的引用文本。
