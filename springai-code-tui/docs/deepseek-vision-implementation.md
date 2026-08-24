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

## 2. 总体思路：图片先变成引用，最后才变成请求里的图片

整个实现本质上只解决两个问题：

1. 用户发来的消息携带图片时，图片信息如何进入对话；
2. 工具调用产生图片时，图片信息如何继续留在对话中。

最后，无论图片来自用户还是工具，都在真正请求 DeepSeek 之前统一处理成 DeepSeek API 要求的格式。

可以先不看代码，把方案理解成下面这条链：

```text
图片文件
  -> 图片引用文本
  -> 对话消息中的文本
  -> 出站前读取引用，临时得到图片内容
  -> HTTP 请求中补成 DeepSeek 图片格式
```

这里有意把“图片引用”和“图片内容”分开：

- 对话历史里保存的是一段文本引用，而不是图片字节；
- 真正调用视觉模型时，才根据引用读取图片；
- 发送给 DeepSeek 的最后一刻，才把图片字节放进请求体。

这样既能让模型知道“有哪张图片”，又不会让图片字节随着会话历史不断累积。

## 3. 先看一个完整例子：图片如何走过一轮对话

假设用户输入：

```text
请分析这个报错界面 docs/bug.png
```

随后模型调用 `Read` 读取了一张工具生成的截图。整个过程可以分成几个阶段。

### 第一步：用户输入仍然是一段普通文本

输入框识别出 `docs/bug.png` 是图片路径，但不会把用户写的路径删除。项目把图片信息追加成一段结构化的文本引用，概念上类似：

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

这一步没有把图片字节放进消息。它只是把图片的“地址和说明”放进了用户文本。

所以，用户图片路径**不会丢**。路径仍然在用户原文里，另外还有一份结构化引用供程序后续解析。

### 第二步：这段文本进入对话历史

这段文本随后作为当前用户消息进入 Spring AI 的对话消息列表。可以把它理解成：

```text
用户消息
  文本：用户原文 + file_reference
  图片内容：还没有
```

Spring AI 里承载用户输入的对象通常叫 `UserMessage`。这里不需要先理解它的全部细节，只要知道：

- 用户原文和图片引用都在消息的文本部分；
- 图片路径没有被放进某个隐藏的 `context` 字段；
- 图片字节此时还没有进入消息；
- 会话保存的也是这段文本，而不是图片二进制。

### 第三步：模型可能先调用工具

模型看到图片引用后，也可能先调用工具，例如：

```text
Assistant：调用 Read，读取 screenshot.png
```

在 Spring AI 的消息链里，这通常表现为两条消息：

```text
assistant 消息：我要调用 Read，以及调用参数
tool 消息：Read 的返回结果
```

工具返回的内容可能是文本，也可能是图片。

### 第四步：工具返回图片时，先保存图片，再返回引用

工具结果不能直接把图片作为图片对象放进去。工具结果在 Spring AI 中主要是文本结果，项目使用的 `ToolResponseMessage` 没有可以直接承载 `Media` 的图片字段。

因此，工具返回图片时走下面的处理：

```text
工具返回的图片字节
  -> 保存到 .codetui/artifacts/
  -> 生成图片文件引用
  -> 把引用文本放进工具结果
```

工具结果最终类似这样：

```text
工具结果：
<file_reference>
kind: image
path: .codetui/artifacts/screenshot.png
mime_type: image/png
dimensions: 1200x800
delivery: not_in_view
</file_reference>
```

这里仍然没有把图片字节直接塞进工具消息。工具消息里保存的是引用文本，图片文件本身保存在 artifacts 中。

### 第五步：下一次请求前，再把当前回合的引用兑现成图片

模型需要继续回答时，系统要构造下一次请求。此时 `VisionMaterializer` 会扫描这次请求里的消息：

- 用户消息中的图片引用；
- 当前回合工具结果中的图片引用；
- 不扫描 assistant 普通文本里可能被模型复述的假引用。

找到引用后，系统才会：

1. 根据 `path` 找到图片文件；
2. 检查图片格式、尺寸和预算；
3. 必要时缩放或转码；
4. 读取最终要发送的图片字节；
5. 临时把图片挂到即将发送的用户消息上。

出站前的消息可以理解成：

```text
用户消息
  文本：用户原文 + 图片引用
  图片：Media(图片字节)

工具结果
  文本：图片引用
  图片：工具结果本身不直接携带 Media

临时用户消息（工具图片）
  文本：以下是工具结果中引用的图片：screenshot.png
  图片：Media(图片字节)
```

工具图片之所以需要临时生成一条用户消息，是因为工具结果消息本身不能直接承载图片；而 `UserMessage` 可以承载 `Media`。

这条临时用户消息只是为了本次请求表达“这里有一张工具产生的图片”，不是用户又输入了一句话，也不是把图片永久写回历史。

### 第六步：Spring AI 序列化时，图片仍可能被丢掉

到这里，项目自己的消息处理已经准备好了图片：

```text
用户消息 = 文本 + Media
临时工具图片消息 = 文本 + Media
```

但是 `spring-ai-deepseek` 的序列化实现主要读取消息文本。它可以把文本转换成 JSON，却不会自动把 `Media` 转换成 DeepSeek 需要的 `content` 图片数组。

所以它序列化出的请求可能仍然近似于：

```json
{
  "messages": [
    {
      "role": "user",
      "content": "请分析这个报错界面 <file_reference>..."
    },
    {
      "role": "tool",
      "content": "<file_reference>...screenshot.png...</file_reference>"
    },
    {
      "role": "user",
      "content": "以下是工具结果中引用的图片：screenshot.png"
    }
  ]
}
```

这里能看到图片引用文本，但看不到图片字节。这就是 DeepSeek 专属 HTTP 改写层存在的原因。

### 第七步：HTTP 发出前，把图片补成 DeepSeek 格式

HTTP 层在请求真正发出前，拿到：

- 已经序列化的 JSON 请求体；
- 本次请求中哪些用户消息有图片；
- 对应的图片字节或 `file_id`。

然后把用户消息的字符串 `content` 改成内容数组，并追加图片块：

```json
{
  "role": "user",
  "content": [
    {"type": "text", "text": "请分析这个报错界面 <file_reference>..."},
    {
      "type": "image_url",
      "image_url": {
        "url": "data:image/png;base64,..."
      }
    }
  ]
}
```

到这一刻，图片才真正以 DeepSeek API 认识的形式进入 HTTP 请求。

## 4. 用户发来的图片到底发生了什么

把用户输入这条路径单独展开，就是：

```text
输入框里的图片路径
  -> 识别为图片附件
  -> 追加 file_reference 文本
  -> 作为用户消息文本进入 Prompt
  -> 会话中保存这段文本
  -> 出站时解析 file_reference
  -> 读取图片并生成临时 Media
  -> HTTP 层把 Media 改成 DeepSeek 图片 JSON
```

### 4.1 图片路径会不会丢

不会。

路径有两个用途：

- 用户原文中的路径让模型知道用户提到了哪个文件；
- `file_reference` 中的路径让程序能够在出站时重新找到图片。

项目内图片直接引用原路径。项目外图片会先复制到 `.codetui/artifacts/`，引用副本路径。这样做是因为引用解析器只允许项目根目录内的路径；如果直接引用项目外的绝对路径，引用块会被安全规则丢弃，图片反而无法兑现。

### 4.2 引用放在 `UserMessage.context` 里吗

不是。

当前实现把引用作为文本拼进用户消息：

```text
UserMessage
  文本：用户原文 + file_reference
  Media：会话阶段为空，出站阶段临时增加
```

这里的 `file_reference` 是普通文本，只是使用了结构化格式，方便 `FileReferenceParser` 找出路径、MIME 类型、尺寸和交付状态。

### 4.3 会话里保存的是什么

会话里保存的是：

```text
用户原文 + 图片引用文本
```

会话里不保存：

```text
图片字节
临时生成的 Media
DeepSeek 的 image_url JSON
```

这就是为什么聊天很多轮、看过很多图片，历史消息不会因为图片二进制而无限膨胀。

## 5. 工具返回的图片到底发生了什么

工具调用的图片路径与用户图片不同，但后半段会汇合到同一条出站链路。

### 5.1 工具结果只能先保存文本引用

工具调用消息大致是：

```text
assistant：调用 Read，参数是 screenshot.png
tool：返回 Read 的结果
```

项目中工具结果通过 `ToolResponseMessage` 表达。它能保存工具的文本结果，但不能像用户消息一样直接挂一组 `Media`。

所以工具产生图片时不能这样做：

```text
ToolResponseMessage + Media(图片字节)   // 当前消息模型没有这个位置
```

实际处理是：

```text
图片字节
  -> MediaExternalizingCallback 识别图片
  -> MediaArtifactStore 保存 artifact
  -> ToolResponseMessage.responseData 写入 file_reference
```

因此工具结果不是“没有图片”，而是把图片从消息里的二进制改成了可追踪的文件引用。

### 5.2 为什么工具结果中的图片要合成用户消息

下一次模型请求前，`VisionMaterializer` 发现工具结果里有图片引用，就读取图片文件并生成 `Media`。

但工具消息仍然不能直接承载 `Media`，所以项目临时创建一条用户消息：

```text
ToolResponseMessage
  responseData = 图片引用文本

        |
        | 出站前兑现
        v

临时 UserMessage
  text = "以下是工具结果中引用的图片：screenshot.png"
  media = [Media(图片字节)]
```

这条临时用户消息的作用不是伪造用户输入，而是使用 Spring AI 能够承载 `Media` 的消息类型，把工具图片带进本次模型请求。

原始 `ToolResponseMessage` 保持不变，因为它必须继续和前面的 `assistant(tool_calls)` 配对。

### 5.3 临时消息会不会写进历史

图片字节不会写进历史。

会话需要保存的仍然是：

```text
assistant(tool_calls)
tool(responseData = 图片引用文本)
```

临时用户消息只用于构造本次出站 Prompt。下一轮重新构造消息时，系统会根据会话里留下的引用决定是否再次兑现；历史图片默认不会自动重复发送。

## 6. 图片如何从消息进入 DeepSeek 请求

前面解决的是“图片如何在项目消息链中保存和流转”。接下来是 DeepSeek 适配层的最后一步。

### 6.1 对象层已经有图片，但序列化层没有正确透传

在视觉兑现完成后，项目自己的消息对象已经可以表达：

```text
UserMessage
  text  = 图片说明和引用文本
  media = 图片字节
```

但 `spring-ai-deepseek` 序列化消息时主要使用 `getText()`。因此：

```text
Spring AI 对象层：有 Media
DeepSeek 普通 JSON：只有文本
```

图片在这里被静默忽略，且普通 `content: String` 也无法表达 DeepSeek 所需的文本块和图片块数组。

### 6.2 注册表连接两个阶段

`DeepSeekThinkingChatModel` 能看到 `Prompt` 中的 `UserMessage.media`；HTTP 改写器只能看到已经序列化的 JSON 字节。项目用 `DeepSeekVisionMediaRegistry` 在两者之间传递图片：

```text
DeepSeekThinkingChatModel
  看到 UserMessage.media
  -> 登记图片

DeepSeekVisionMediaRegistry
  -> user消息序号:media序号 -> 图片字节或 file_id

DeepSeekThinkingBodyCodec
  看到 JSON 中的 role=user
  -> 取回图片并追加图片块
```

注册表只是一次请求的临时通道：

- 纯文本请求不写入注册表；
- 图片被 HTTP 改写器取出后立即删除；
- 请求结束后清理未消费的记录。

### 6.3 为什么不用消息绝对下标

不能简单地用 `Prompt.messages` 的绝对下标作为图片位置，因为 DeepSeek 序列化时，工具结果的多个响应可能展开成多条 JSON 消息：

```text
对象层的第 N 条消息
不一定是 JSON messages 数组的第 N 条消息
```

项目两侧只统计用户消息：

```text
第 N 条 UserMessage + 第 M 个 Media
        <=>
注册表 key = N:M

第 N 个 role=user JSON 消息
        <=>
读取 key = N:M
```

每一条 user 消息都占一个序号，包括没有图片的 user 消息。这样其他角色消息如何展开，都不会改变用户消息相对顺序。

### 6.4 HTTP 层如何补成 DeepSeek 格式

`DeepSeekThinkingBodyCodec` 在请求体发送前：

1. 扫描 JSON 的 `messages`；
2. 只处理 `role=user`；
3. 找到对应的 user 序号；
4. 将字符串 `content` 转成文本块数组；
5. 从注册表取出对应图片；
6. 追加 `image_url` 或 `file` 图片块。

默认内联图片：

```json
{
  "type": "image_url",
  "image_url": {
    "url": "data:image/png;base64,..."
  }
}
```

可选 Files API：

```json
{
  "type": "file",
  "file_id": "file-..."
}
```

Files API 是传输优化，不是视觉功能的前提。上传失败时自动退回内联图片。

## 7. 一张图的完整生命周期

把上面的内容压缩成一条时序：

```text
用户输入图片路径
  |
  v
输入文本追加 file_reference
  |
  v
用户消息进入会话：只有文本引用，没有图片字节
  |
  v
工具调用产生图片
  |
  v
工具图片落 artifacts，ToolResponseMessage 只保存引用文本
  |
  v
下一次模型调用前扫描当前回合引用
  |
  v
读取图片、检查预算、缩放/转码
  |
  v
生成临时 UserMessage.media
  |
  v
DeepSeekThinkingChatModel 登记图片
  |
  v
spring-ai-deepseek 序列化：图片仍可能只剩文本
  |
  v
HTTP BodyCodec 追加 image_url/file
  |
  v
DeepSeek 收到真正的视觉请求
```

所以视觉支持不是让图片从一开始就跟着消息走到底，而是在每个边界转换表示：

```text
文本引用
  -> 临时 Media
  -> DeepSeek 图片 JSON
```

## 8. 关键设计取舍

### 8.1 引用持久化，图片内容临时化

引用可以进入会话历史，图片字节不进入。这样历史可恢复、可压缩，图片也能在需要时根据路径重新读取。

### 8.2 用户图片优先于工具图片

当前请求最多兑现用户图片 3 张、工具图片 1 张，并且还有视觉 token 和单回合累计限制。用户图片通常代表本轮任务意图，因此优先保留；工具循环产生的截图只保留最新的一张。

### 8.3 工具消息不改结构

`assistant(tool_calls)` 与 `tool` 结果必须保持合法配对。工具图片通过临时 `UserMessage` 旁路投递，而不是破坏标准工具消息结构。

### 8.4 普通文本请求保持原样

没有图片时：

- 不写入视觉注册表；
- 不改写普通 JSON；
- 不增加图片相关字段；
- DeepSeek 原有文本和思考逻辑保持不变。

## 9. 关键类索引

按消息处理链阅读代码时，只需要关注这些类：

| 类 | 作用 |
| --- | --- |
| `ui.CodeTuiView.injectAttachments` | 把用户输入的图片路径变成文本引用 |
| `agent.CodingAgent` | 提交用户文本、维护会话回合 |
| `agent.media.MediaExternalizingCallback` | 把工具返回的图片保存并替换成引用 |
| `agent.media.SessionFileExternalizer` | 把历史工具结果中的二进制文件换成引用 |
| `agent.media.VisionMaterializer` | 出站时读取引用，生成临时 `Media` |
| `agent.media.VisionMaterializingChatModel` | 在真正调用 provider 前触发图片兑现 |
| `agent.DeepSeekThinkingChatModel` | 登记出站 `Media` |
| `agent.media.DeepSeekVisionMediaRegistry` | 临时保存图片和消息位置的映射 |
| `agent.DeepSeekThinkingBodyCodec` | 把图片补成 DeepSeek JSON |
| `agent.DeepSeekThinkingClientHttpConnector` | 改写流式请求体 |

## 10. 验证范围与已知边界

当前实现和测试覆盖：

- 用户直接附图；
- 工具返回图片后转成引用；
- 工具图片在出站时合成为临时用户消息；
- DeepSeek 内联 base64 视觉通道；
- 流式和非流式请求体改写；
- user 消息序号和 media 序号对齐；
- Files API 的单测路径和失败降级路径。

需要注意：

- DeepSeek 内联通道已做真机视觉验证；
- Files API 通道目前主要由单测覆盖；
- 同一回合多次工具迭代可能重复发送当轮图片，这是无状态请求的固有成本；
- `CODETUI_VISION=off` 会关闭图片兑现，但不会删除消息中的引用文本。

最终可以把整个实现浓缩成一句话：

> 先把图片变成可以进入会话的文本引用，再在出站前把当前回合的引用兑现成临时图片，最后通过 DeepSeek 专属 HTTP 改写层，把图片补回 API 要求的 `content` 数组中。
