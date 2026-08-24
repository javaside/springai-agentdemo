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

## 2. 一页看懂：图片如何进入 DeepSeek 请求

整个视觉实现先只看两条消息流。暂时不用理解所有 Spring AI 类名，只要看清楚“哪条消息在什么时候携带图片”。

### 2.1 用户直接附图：本次请求就发送

用户输入一段文字，并带上一张图片：

```text
请分析这个报错界面 docs/bug.png
```

这里要区分“项目内部的出站消息”和“最终发出的 HTTP JSON”。图片不是一出现 `media` 就已经完成了 DeepSeek 格式的发送，而是要经过两个转换阶段。

#### 阶段一：项目内部先构造出站 Prompt

```text
用户输入
  |
  v
用户文本 + 图片引用
  |
  v
本次出站 Prompt 中的 UserMessage
  ├── text  = 用户原文（图片路径仍在）+ 图片引用
  └── media = 用户附带的图片（兑现成功时）
```

这里的 `media` 只是项目内部的图片载体，供 `DeepSeekThinkingChatModel` 在 Spring AI 序列化前读取；它**不是** DeepSeek HTTP 请求里的最终图片格式。

#### 阶段二：如果不做 DeepSeek 专属改写，图片会被丢掉

`spring-ai-deepseek` 原有序列化逻辑主要读取消息文本。因此同一条消息直接序列化后，大致只有：

```json
{
  "role": "user",
  "content": "请分析这个报错界面 docs/bug.png\\n\\n<file_reference>\\nkind: image\\npath: docs/bug.png\\n...\\n</file_reference>"
}
```

这份 JSON 里保留了用户原文、图片路径和引用文本，但没有图片字节。`UserMessage.media` 在这一步没有被正确转换出来。

#### 阶段三：HTTP 层把刚才暂存的图片补回最终 JSON

在序列化之前，`DeepSeekThinkingChatModel` 已经把 `media` 登记到了视觉注册表；HTTP 改写器再根据这份登记，把图片追加到对应 user 消息的 `content` 数组：

最终发给 DeepSeek 的消息大致是：

```json
{
  "role": "user",
  "content": [
    {"type": "text", "text": "请分析这个报错界面 docs/bug.png\n\n<file_reference>\nkind: image\npath: docs/bug.png\n...\n</file_reference>"},
    {
      "type": "image_url",
      "image_url": {
        "url": "data:image/png;base64,..."
      }
    }
  ]
}
```

所以完整过程不是“`media` 已经直接发出，又补了一次图片”，而是：

```text
UserMessage.text + UserMessage.media
  -> media 被 DeepSeek 原有序列化逻辑遗漏
  -> HTTP 改写层依据序列化前登记的信息补回图片块
  -> 最终 DeepSeek JSON
```

关键结论：

> 用户第一次发送图片时，图片就在这一次请求中发送给模型；但它先以项目内部的 `Media` 暂存，再由 HTTP 层转换成 DeepSeek 最终认识的图片格式，不需要等模型调用工具。

### 2.2 工具产生图片：消息链追加一条 UserMessage

工具图片不是用户第一次发消息时就已经存在的图片，而是工具执行后才产生的。详细过程是：

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

### 2.3 两条路径放在一起看

| 图片来源 | 消息处理方式 | 图片在哪次请求发送 |
| --- | --- | --- |
| 用户直接附图 | 原来的 `UserMessage` 增加 `media` | 用户消息对应的本次请求 |
| 工具产生图片 | 工具循环再次调用 ChatModel 时，保留 `ToolResponseMessage` 并追加带 `media` 的 `UserMessage` | 这次 ChatModel 调用对应的请求 |
| 模型重新读取历史图片 | `Read` 产生工具结果，再按工具图片路径处理 | `Read` 返回后工具循环再次调用 ChatModel 对应的请求 |

这张表是整份文档的主线。

## 3. 还要区分：会话历史、出站消息和 HTTP 请求

同一张图片在系统里会有三种表示，不能混在一起。

### 3.1 会话历史：保存文本引用

会话历史保存的是可以恢复的文本消息：

```text
UserMessage
  text  = 用户原文 + file_reference
  media = 不保存图片字节
```

工具图片则保存为：

```text
AssistantMessage
  tool_calls = ...

ToolResponseMessage
  responseData = file_reference
```

会话历史里不会保存：

- 图片二进制；
- 临时生成的 `Media`；
- DeepSeek 的 `image_url` JSON。

### 3.2 出站 Prompt：本次请求临时补上 Media

真正调用模型前，系统根据消息里的引用读取图片，构造本次请求专用的消息副本：

用户图片：

```text
UserMessage
  text  = 用户原文 + file_reference
  media = 用户图片
```

工具图片：

```text
ToolResponseMessage
  responseData = file_reference

UserMessage（临时新增）
  text  = 工具图片说明
  media = 工具图片
```

这一步只改变本次出站 Prompt，不把图片字节写回会话历史。

### 3.3 DeepSeek HTTP JSON：再把 Media 改成图片块

Spring AI 对象层中的 `Media` 还不是 DeepSeek API 的最终格式。HTTP 层会把它改写成：

```json
{
  "role": "user",
  "content": [
    {"type": "text", "text": "文字内容"},
    {"type": "image_url", "image_url": {"url": "data:image/png;base64,..."}}
  ]
}
```

所以完整关系是：

```text
会话历史
  UserMessage(text = 引用, media = 无)
        |
        | 出站前读取引用
        v
本次出站 Prompt
  UserMessage(text = 引用, media = 图片)
        |
        | HTTP 层改写
        v
DeepSeek 请求
  content = [文本块, 图片块]
```

## 4. 用户图片流程：原来的 UserMessage 携带图片

### 4.1 输入框不会删除图片路径

用户输入：

```text
请分析这个报错界面 docs/bug.png
```

输入框识别出图片路径后，会把图片信息追加成结构化文本引用，概念上类似：

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

这里发生的是“增加图片引用”，不是“把路径替换掉”：

- 用户原文中的 `docs/bug.png` 仍然存在；
- 引用块额外提供了程序需要的路径、类型和尺寸；
- 图片字节还没有进入消息。

### 4.2 引用文本进入用户消息

提交后，这段文本进入当前回合的用户消息。抽象表示为：

```text
UserMessage
  text  = 用户原文 + file_reference
  media = 空
```

引用不是放在 `UserMessage.context` 中。当前实现的设计是：

> 让引用作为普通文本进入用户消息；需要发送图片时，再从文本中解析引用。

这样做有一个直接好处：用户看见的内容、会话保存的内容和模型能理解的图片说明，使用的是同一段文本。

### 4.3 本次请求前，把同一条 UserMessage 补上图片

在本次模型请求真正发出前，视觉处理逻辑会：

1. 解析用户消息文本中的 `file_reference`；
2. 根据引用里的路径找到图片；
3. 检查模型是否支持视觉；
4. 检查图片格式、尺寸和本次视觉预算；
5. 必要时缩放或转码；
6. 把图片字节生成 `Media`；
7. 把 `Media` 挂到这条用户消息上。

于是，本次出站消息变成：

```text
UserMessage
  text  = 用户原文 + file_reference
  media = [用户附带的图片]
```

此时图片已经准备好在本次请求发送给大模型。模型是否随后调用 `Read`，与这次发送无关。

### 4.4 为什么以后还可能调用 Read

用户图片本次请求已经发给模型，但图片不会自动永久留在后续每一轮请求里。历史消息只保存引用文本，后续请求默认不重复兑现历史图片。

如果模型下一轮需要重新查看这张历史图片，它可以调用：

```text
Read(file_reference.path)
```

这次 `Read` 是“重新取回历史图片”的行为，不是用户第一次附图的替代步骤。

`Read` 返回后，会按照工具图片流程处理：

```text
Read 返回图片
  -> ToolResponseMessage(responseData = 引用)
  -> Spring AI 工具调用循环再次调用 ChatModel
  -> VisionMaterializer 追加 UserMessage(media = 图片)
  -> DeepSeek HTTP 层补图并发送请求
```

### 4.5 项目内外路径的区别

项目内图片直接引用原文件：

```text
docs/bug.png -> 仍然指向原文件
```

这样用户更新文件后，模型下一次重新读取时可以看到新内容。

项目外图片先复制到 artifacts：

```text
~/Desktop/bug.png
  -> .codetui/artifacts/<content-hash>.png
  -> 引用 artifacts 内的副本
```

引用解析器只允许项目根目录内的路径。项目外文件如果不先复制，引用会被安全边界拒绝，图片无法在出站阶段兑现。

## 5. 工具图片流程：新增一条 UserMessage 携带图片

### 5.1 第一次请求只产生工具调用

模型第一次请求发现需要读取文件时，先返回工具调用：

```text
AssistantMessage
  tool_calls = [Read(screenshot.png)]
```

此时还没有工具结果，也就没有工具产生的图片。

### 5.2 工具返回结果只能先放文本引用

工具执行后可能得到图片。项目不会把图片二进制直接塞进工具消息，而是先外置：

```text
工具返回图片字节
  -> MediaExternalizingCallback
  -> 保存到 .codetui/artifacts/
  -> 生成 file_reference
  -> 放入 ToolResponseMessage.responseData
```

于是消息链变成：

```text
AssistantMessage
  tool_calls = [Read(screenshot.png)]

ToolResponseMessage
  responseData = <file_reference path=".codetui/artifacts/screenshot.png" ...>
```

`ToolResponseMessage` 里保存的是引用文本，不是 `Media`。这不是图片消失，而是先把图片放到 artifact 文件中，用引用替代消息里的二进制。

### 5.3 为什么不能直接给 ToolResponseMessage 加图片

项目使用的工具结果消息主要包含：

```text
工具调用 id
工具名
工具返回的 responseData 文本
```

它没有像用户消息那样的 `media` 位置。因此不能简单表示为：

```text
ToolResponseMessage
  responseData = 文本
  media = 图片       // 当前消息结构没有这个位置
```

同时，原来的 `AssistantMessage(tool_calls)` 和 `ToolResponseMessage` 必须保持合法配对。直接改造工具消息会破坏模型 API 对工具调用结果的要求。

### 5.4 Spring AI 再次调用 ChatModel 时追加 UserMessage

工具执行完成后，Spring AI 的工具调用循环把 `AssistantMessage(tool_calls)` 和 `ToolResponseMessage` 放进本轮消息列表，并再次调用 ChatModel。这个调用进入 provider 前，`VisionMaterializer` 扫描 `responseData` 中的图片引用：

1. 读取引用对应的 artifact 文件；
2. 检查格式、尺寸和预算；
3. 生成图片 `Media`；
4. 新增一条临时 `UserMessage`。

消息链变成：

```text
AssistantMessage
  tool_calls = [Read(screenshot.png)]

ToolResponseMessage
  responseData = 图片引用文本

UserMessage（临时新增）
  text  = "以下是工具结果中引用的图片：screenshot.png"
  media = [工具返回的图片]
```

这条新增的 `UserMessage` 不是用户又发了一条消息，而是给当前出站请求提供一个合法的图片承载位置。

### 5.5 这条临时 UserMessage 是否写入历史

不会把图片字节写回历史。

历史中保留的是：

```text
AssistantMessage(tool_calls)
ToolResponseMessage(responseData = 图片引用文本)
```

临时 `UserMessage` 只用于构造本次出站 Prompt。下一轮如果仍然需要这张图片，系统可以根据历史引用重新决定是否兑现；默认不会把所有历史图片自动重复发送。

## 6. 两条流程最后汇合：DeepSeek HTTP 层统一补图

前面两种来源最后都会形成带 `Media` 的 `UserMessage`：

```text
用户图片：
  原来的 UserMessage.media = 用户图片

工具图片：
  新增的 UserMessage.media = 工具图片
```

从 DeepSeek HTTP 层看，它不需要区分图片来自用户还是工具。它只需要处理：

```text
某条 UserMessage 有 text
某条 UserMessage 有 media
```

### 6.1 Spring AI 的图片对象为什么还不够

到达 DeepSeek ChatModel 时，Spring AI 对象层已经表达了图片：

```text
UserMessage
  text  = 图片说明和引用
  media = 图片字节
```

但 `spring-ai-deepseek` 序列化时主要读取文本，得到的 JSON 可能仍然只有：

```json
{
  "role": "user",
  "content": "图片说明和引用"
}
```

`Media` 没有自动转换为 DeepSeek 所需的 content 数组，所以还需要 HTTP 层补一次。

### 6.2 对象层和 HTTP 层如何传递图片

`DeepSeekThinkingChatModel` 能看见 `Prompt` 里的 `UserMessage.media`，但 HTTP 改写器拿到的只是已经序列化的 JSON 字节。项目用 `DeepSeekVisionMediaRegistry` 暂存这批图片：

```text
DeepSeekThinkingChatModel
  看到 UserMessage.media
  -> 登记图片

DeepSeekVisionMediaRegistry
  -> 保存“第几个 user 消息的第几张图片”

DeepSeekThinkingBodyCodec
  看到 JSON 的 role=user
  -> 取出对应图片并补入 content
```

这只是一次请求的临时桥梁：图片被取出后立即消费删除，请求结束后清理剩余记录。

### 6.3 为什么用 user 消息序号

不能直接使用 `Prompt.messages` 的绝对下标，因为 DeepSeek 序列化时，工具结果的多个响应可能展开成多条 JSON 消息：

```text
对象层消息下标 != JSON messages 数组下标
```

两侧使用同一套规则，只统计用户消息：

```text
第 N 条 UserMessage + 第 M 个 Media
        <=>
注册表 key = N:M

第 N 个 role=user JSON 消息
        <=>
读取 key = N:M
```

每条用户消息都占一个序号，即使它没有图片。这样工具消息如何展开，都不会影响 user 消息的相对顺序。

### 6.4 HTTP 改写后的最终格式

HTTP 层把字符串 `content` 转成数组，并追加图片块：

```json
{
  "role": "user",
  "content": [
    {"type": "text", "text": "图片说明和引用"},
    {
      "type": "image_url",
      "image_url": {
        "url": "data:image/png;base64,..."
      }
    }
  ]
}
```

默认使用内联 base64，也可以通过 `DEEPSEEK_VISION_TRANSPORT=files` 使用：

```json
{
  "type": "file",
  "file_id": "file-..."
}
```

Files API 是传输优化，不是视觉功能的前提；上传失败会退回内联图片。

## 7. 信息流总结

### 用户图片

```text
用户输入路径
  -> 用户原文 + 图片引用
  -> 原来的 UserMessage
       text  = 原文 + 引用
       media = 用户图片
  -> HTTP 补 image_url/file
  -> 本次请求发送给模型
```

### 工具图片

```text
AssistantMessage(tool_calls)
  -> 工具执行
  -> ToolResponseMessage(responseData = 图片引用)
  -> Spring AI 工具调用循环再次调用 ChatModel
  -> VisionMaterializer 追加 UserMessage
       text  = 工具图片说明
       media = 工具图片
  -> DeepSeek HTTP 层补 image_url/file
  -> 请求发送给模型
```

### 历史图片重新查看

```text
历史中保存的图片引用
  -> 模型调用 Read
  -> ToolResponseMessage(responseData = 图片引用)
  -> Spring AI 工具调用循环再次调用 ChatModel
  -> VisionMaterializer 追加 UserMessage(media = 图片)
  -> DeepSeek HTTP 层补图并发送请求
```

最终只需要记住一句话：

> 用户图片挂到原来的 `UserMessage`，随本次请求发送；工具图片先进入 `ToolResponseMessage` 的文本引用，Spring AI 工具调用循环再次调用 ChatModel 时，`VisionMaterializer` 再追加一条带 `Media` 的 `UserMessage`；两条路径最后统一由 DeepSeek HTTP 层把 `Media` 补成图片 JSON。

## 8. 关键类索引

理解主流程后，再按下面的顺序看代码：

| 类 | 作用 |
| --- | --- |
| `ui.CodeTuiView.injectAttachments` | 把用户输入的图片路径变成文本引用 |
| `agent.CodingAgent` | 提交用户文本、维护回合和会话 |
| `agent.media.MediaExternalizingCallback` | 把工具返回的图片保存并替换成引用 |
| `agent.media.VisionMaterializer` | 出站时从引用读取图片，给消息增加 `Media` |
| `agent.media.VisionMaterializingChatModel` | 在 provider 发出前触发图片处理 |
| `agent.DeepSeekThinkingChatModel` | 登记出站 `UserMessage.media` |
| `agent.media.DeepSeekVisionMediaRegistry` | 临时保存图片与 user 消息位置的映射 |
| `agent.DeepSeekThinkingBodyCodec` | 把图片补成 DeepSeek JSON |
| `agent.DeepSeekThinkingClientHttpConnector` | 改写流式请求体 |

## 9. 验证范围与已知边界

当前实现和测试覆盖：

- 用户直接附图，并在本次请求中发送；
- 工具返回图片后转成引用；
- 工具循环再次调用 ChatModel 时，由 `VisionMaterializer` 追加工具图片 `UserMessage`；
- DeepSeek 内联 base64 视觉通道；
- 流式和非流式请求体改写；
- user 消息序号和 media 序号对齐；
- Files API 的单测路径和失败降级路径。

需要注意：

- DeepSeek 内联通道已做真机视觉验证；
- Files API 通道目前主要由单测覆盖；
- 同一回合多次工具迭代可能重复发送当轮图片，这是无状态请求的固有成本；
- `CODETUI_VISION=off` 会关闭图片兑现，但不会删除消息中的引用文本。
