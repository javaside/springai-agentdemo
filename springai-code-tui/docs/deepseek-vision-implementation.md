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

## 2. 总体思路：对象层保留，HTTP 层补齐

整条链路可以概括为：

```text
用户输入图片 / 工具产生图片
          |
          v
图片先保存为引用，或在出站阶段读取成 Media
          |
          v
VisionMaterializingChatModel
          |
          v
UserMessage + Media
          |
          v
DeepSeekThinkingChatModel 登记图片
          |
          v
spring-ai-deepseek 序列化成普通 JSON
          |
          v
DeepSeekThinkingBodyCodec 改写 JSON
          |
          v
符合 DeepSeek API 格式的 content 数组
```

这里有一个重要的分层：

### 对象层

对象层使用 Spring AI 的标准类型表达消息：

```text
Prompt
  -> UserMessage
       -> text
       -> Media
```

这一层负责回答“这一轮消息中有哪些图片”。它不关心 DeepSeek 最终要求的 JSON 长什么样。

### HTTP 层

HTTP 层拿到已经序列化的请求体后，负责回答“这些图片应该如何表示成 DeepSeek JSON”。

例如，原本的请求可能是：

```json
{
  "messages": [
    {
      "role": "user",
      "content": "请分析这张图片"
    }
  ]
}
```

改写后变成：

```json
{
  "messages": [
    {
      "role": "user",
      "content": [
        {"type": "text", "text": "请分析这张图片"},
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

这样做的好处是：

1. 不需要修改 Spring AI 的消息类型；
2. 不需要复制一套 DeepSeek ChatModel；
3. 普通文本请求仍然走原来的路径；
4. DeepSeek 专属逻辑被限制在 provider 和 HTTP 改写层内。

## 3. 第一阶段：用户消息中的图片如何进入请求

### 3.1 用户输入先变成图片引用

用户可以在输入框中直接输入图片路径，或者把图片拖入终端。项目不会把图片二进制立即写进会话消息，而是把它表示为一段文本引用。

引用中包含图片的基本信息，例如：

```text
<file_reference>
kind: image
path: .codetui/artifacts/...
mime_type: image/png
dimensions: 1440x900
delivery: pending
</file_reference>
```

这样做有两个原因：

- 会话历史主要保存文本，避免图片字节让历史文件和上下文不断膨胀；
- 图片是否真正发送，要等到确定当前 provider、模型和视觉预算之后再决定。

项目内图片可以引用原文件；项目外图片会先复制到 `.codetui/artifacts/`。引用解析时还会限制路径必须位于项目根目录内，避免模型或恶意文本通过引用读取任意文件。

### 3.2 出站前把当前回合的引用兑现成 Media

真正发请求前，`VisionMaterializingChatModel` 会处理当前回合的引用：

1. 找到最后一条非合成的 `UserMessage`，将它视为当前回合锚点；
2. 只处理这个锚点及其之后的图片引用；
3. 读取图片文件；
4. 根据格式和尺寸进行缩放、转码或拒绝；
5. 把可发送的图片转换成 Spring AI `Media`；
6. 把 `Media` 放回即将发送的 `UserMessage`。

历史图片默认不会自动重新兑现。模型如果需要重新查看历史图片，可以调用 `Read` 读取引用路径；工具返回的图片会重新成为当前回合的输入。

因此，图片在系统中形成了这样的生命周期：

```text
文件路径
  -> 文本引用
  -> 当前回合出站时读取
  -> UserMessage.Media
  -> HTTP 请求中的图片块
```

图片不会因为进入过一次请求，就永久进入会话历史。

### 3.3 工具产生的图片走同一条出站路径

工具返回图片时，工具装饰器会优先把图片保存成 artifact，并把返回内容外置成引用。工具结果本身不直接携带大量二进制内容。

下一次模型调用前，视觉装饰器会把当前回合工具结果中的图片引用合成为一条 `UserMessage`，再把图片兑现成 `Media`。

从 DeepSeek 的 HTTP 改写器角度看，用户直接贴的图片和工具产生的图片没有区别：

```text
都是某一条 UserMessage 上的 Media 列表
```

这正是两类图片能够复用同一条 DeepSeek 专属通道的原因。

## 4. 第二阶段：对象层如何把图片交给 HTTP 层

### 4.1 为什么需要注册表

对象层的 `Media` 和 HTTP 层的 JSON 改写之间没有直接参数传递关系：

- `DeepSeekThinkingChatModel` 能看到 `Prompt` 和 `Media`；
- HTTP connector 能看到已经序列化的 `byte[]` 请求体；
- 两者之间没有一个适合直接传递图片字节的 Spring AI 标准接口。

项目使用 `DeepSeekVisionMediaRegistry` 作为一个很小的旁路通道：

```text
DeepSeekThinkingChatModel                 DeepSeekThinkingBodyCodec
看到 UserMessage.Media                    看到 JSON messages 数组
          |                                      ^
          +--> VisionMediaRegistry -------------+
```

注册表保存的是“图片应该放到哪条 user 消息的哪个位置”以及图片内容：

```text
key:   user消息序号:media序号
value: 图片字节 + MIME 类型
```

概念上可以表示为：

```text
"2:0" -> image/png 的图片字节
"2:1" -> image/jpeg 的图片字节
```

### 4.2 为什么使用 user 消息序号

不能直接使用 `Prompt.getInstructions()` 中的绝对消息下标。

原因是 Spring AI DeepSeek 序列化时，某些 `ToolResponseMessage` 的多个响应可能会被展开成多条 JSON 消息。于是：

```text
对象层消息下标 != 序列化后 JSON 消息下标
```

如果之前的工具历史数量发生变化，绝对下标就会漂移，图片可能被放到错误的消息上，甚至静默丢失。

项目选择使用“第几个 user 消息”作为坐标：

- 对象层只统计 `UserMessage`；
- JSON 层只统计 `role=user`；
- 每条 user 消息都占一个序号，即使它没有图片；
- 两侧使用同样的计数方式，序列化展开其他角色消息也不会影响坐标。

因此两侧都遵守：

```text
第 N 条 user 消息 + 第 M 个 media
       <=>
注册表 key = N:M
```

### 4.3 注册表是一次性通道

注册表不是长期缓存，而是一次请求的临时通道：

- 只有确认请求中确实存在可注册图片时才清理并写入；
- 纯文本请求不接触注册表，避免无图请求干扰并发中的有图请求；
- HTTP 改写器每取出一张图片就立即删除对应 key；
- 请求结束后，未被消费的 key 由调用方清理。

这种“消费即删”的设计可以避免同一张图片在同一个 key 上被重复使用。

## 5. 第三阶段：HTTP 请求如何改写成 DeepSeek 格式

### 5.1 改写发生在请求发送前

`DeepSeekThinkingClientHttpConnector` 装饰底层 `ClientHttpConnector`。它不改变响应处理，也不改变流式响应的解析，只拦截有限长度的请求体：

```text
发送请求
  -> 收集请求 body
  -> DeepSeekThinkingBodyCodec 改写
  -> 更新 Content-Length
  -> 交给原 connector
```

非流式调用和流式调用都需要经过这一步：

- 非流式请求使用普通 HTTP connector 或 request interceptor；
- 流式请求使用 `DeepSeekThinkingClientHttpConnector`；
- 流式响应仍然由原来的响应处理逻辑消费。

### 5.2 只改写 user 消息

图片只会出现在 user 消息中，因此改写器只处理：

```json
{"role": "user", "content": ...}
```

`system`、`assistant` 和 `tool` 消息不参与图片注入。改写器按顺序扫描 JSON 中的 `role=user`，并与注册侧使用同样的 user 序号。

如果原始 `content` 是字符串，先转换为一个文本块：

```json
"content": "请分析图片"
```

变为：

```json
"content": [
  {"type": "text", "text": "请分析图片"}
]
```

然后把注册表中的图片块追加到数组末尾。

### 5.3 两种图片传输格式

#### 默认：内联 base64

```json
{
  "type": "image_url",
  "image_url": {
    "url": "data:image/png;base64,..."
  }
}
```

图片已经经过项目侧的尺寸和大小处理，直接把字节编码为 base64，放入 `data:` URL。

#### 可选：Files API

```json
{
  "type": "file",
  "file_id": "file-..."
}
```

启用 `DEEPSEEK_VISION_TRANSPORT=files` 后，项目会尝试用图片内容换取 DeepSeek `file_id`，之后请求只携带 file id。上传失败时自动降级为内联图片，因为 Files API 是传输优化，不应该成为视觉功能的单点依赖。

### 5.4 请求改写的失败策略

改写器遵循“尽量不影响请求”的原则：

- 找不到对应注册 key 时停止继续注入；
- 没有任何图片被改写时，原样返回请求体；
- 非 user 消息不改动；
- 普通文本请求不增加图片相关字段。

这样做的结果是：坐标不匹配时图片可能缺失，但不会把一个本来可用的文本请求改坏。图片功能本身通过单测和视觉冒烟测试发现问题，而不是让错误扩大成所有请求失败。

## 6. 一次请求的完整时序

以用户输入一张图片并询问“这是什么”为例：

```text
1. 输入框发现图片路径
       |
2. 图片路径被写成 file_reference 文本
       |
3. 回合提交，引用进入 Prompt / 会话消息
       |
4. VisionMaterializingChatModel 找到当前回合引用
       |
5. 读取图片，检查格式、尺寸、预算并生成 Media
       |
6. DeepSeekThinkingChatModel 看到 UserMessage.Media
       |
7. 以 user序号:media序号 登记到 VisionMediaRegistry
       |
8. spring-ai-deepseek 序列化普通消息
       |
9. HTTP connector 收集 JSON 请求体
       |
10. BodyCodec 扫描 role=user
       |
11. 把文本字符串转换为 content 数组
       |
12. 从注册表取出图片并追加 image_url/file 块
       |
13. 更新 Content-Length，发送到 DeepSeek
```

关键点是：图片在第 5 步才变成 `Media`，在第 12 步才变成 DeepSeek 的图片 JSON。中间的会话历史只保留引用文本。

## 7. 为什么不直接修改 Spring AI 的 DeepSeek 序列化器

直接修改第三方序列化器看起来更简单，但会带来几个问题：

1. 需要维护一个与 `spring-ai-deepseek` 内部实现高度耦合的分支；
2. 框架升级后容易因为内部类型变化而失效；
3. 视觉能力会渗入所有消息序列化路径，难以限制只对 DeepSeek 生效；
4. 无法自然处理项目自己的“当前回合图片兑现”和视觉预算策略。

当前设计把职责拆开：

| 模块 | 只负责什么 |
| --- | --- |
| `VisionMaterializingChatModel` | 引用如何变成当前请求中的 `Media` |
| `DeepSeekThinkingChatModel` | `Media` 如何暂存并与请求位置关联 |
| `DeepSeekVisionMediaRegistry` | 对象层和 HTTP 层之间传递一次性图片数据 |
| `DeepSeekThinkingBodyCodec` | 图片如何变成 DeepSeek JSON |
| `DeepSeekThinkingClientHttpConnector` | 如何在 HTTP 发送前调用改写器 |

每一层只处理自己最了解的表示形式。

## 8. 设计中的几个关键取舍

### 8.1 图片不进入会话记忆

会话存储引用而不是字节，解决了上下文无限增长问题。图片是否发送由出站阶段决定，因此可以同时实现：

- 当前回合图片才自动发送；
- 历史图片默认不重复发送；
- 模型需要历史图片时通过 `Read` 主动取回。

### 8.2 用户图片优先于工具图片

每次请求最多兑现用户图片 3 张、工具图片 1 张，并且有视觉 token 预算。用户直接贴图通常代表这一轮的核心意图，因此必须优先保留；工具循环产生的截图数量可能很多，只保留最新的工具图片，避免工具图片挤掉用户图片。

### 8.3 内联优先，Files API 作为增强

内联不需要额外上传请求，也不需要处理 file id 生命周期，失败面更小，所以作为默认通道。Files API 可以减少工具循环中重复传输的图片字节，但它优化的是带宽和延迟，不是计费；上传失败必须能够退回内联。

### 8.4 普通文本请求零行为变化

没有图片时：

- 不写入视觉注册表；
- 不清理其他请求可能正在使用的注册表内容；
- HTTP body 不增加视觉字段；
- 原来的 DeepSeek 文本和思考能力保持不变。

这是视觉旁路设计的重要边界。

## 9. 最小代码索引

理解这条链路只需要关注以下位置：

| 位置 | 作用 |
| --- | --- |
| `agent.media.VisionMaterializingChatModel` | 把当前回合的文件引用兑现成 `Media` |
| `agent.DeepSeekThinkingChatModel.registerMedia` | 扫描 user 消息并登记图片 |
| `agent.media.DeepSeekVisionMediaRegistry` | 保存 `user序号:media序号 -> 图片` 的临时映射 |
| `agent.DeepSeekThinkingBodyCodec.decorateVision` | 在 JSON 的 user content 中追加图片块 |
| `agent.DeepSeekThinkingClientHttpConnector` | 在流式 HTTP 请求发送前调用 body 改写器 |
| `agent.DeepSeekThinkingBodyCodec.entryToNode` | 生成 `image_url` 或 `file` JSON |
| `agent.media.DeepSeekFileStore` | Files API 模式下按内容复用 file id |

不需要先阅读整个 provider、会话、工具或 UI 实现。只要先抓住下面这条主线即可：

```text
引用 -> 当前回合 Media -> 注册表 -> HTTP JSON 改写 -> DeepSeek 图片块
```

## 10. 验证范围与已知边界

当前实现已经覆盖：

- 用户直接附图；
- 工具结果中的图片；
- DeepSeek 内联 base64 视觉通道；
- 非流式和流式请求的请求体改写；
- user 序号和 media 序号的错位保护；
- Files API 的单测路径与失败降级路径。

需要注意：

- DeepSeek 内联通道已做真机视觉验证；
- Files API 通道目前主要由单测覆盖，仍应视具体环境做真实请求验证；
- 图片仍然属于无状态请求的一部分，同一回合的多次工具迭代可能重复发送当轮图片；
- `CODETUI_VISION=off` 可以关闭图片兑现，但引用文本仍然保留，模型仍可通过路径理解“有一张图片可供 Read”。

最终可以把整个实现浓缩成一句话：

> 先用项目自己的媒体系统把图片安全地保留到当前回合，再用 DeepSeek 专属 HTTP 改写层把 Spring AI 丢掉的 `Media` 补回 API 要求的 `content` 数组中。
