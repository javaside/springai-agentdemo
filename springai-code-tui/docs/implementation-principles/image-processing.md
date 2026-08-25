# 图片处理实现原理

## 1. 背景：为什么图片不能直接塞进会话

图片处理看起来只是“读取文件并发给模型”，实际同时涉及消息结构、会话持久化、工具调用、安全、内存和费用控制。

最直接的实现是把图片字节或 base64 放进消息，并随会话一起保存。但对话 API 是无状态的，每次请求都要重新发送历史消息。如果图片字节进入会话历史，就会产生几个问题：

- 看过的图片会在后续每轮请求中重复发送；
- 工具循环越长，同一张图被重复解码、编码和上传的次数越多；
- MCP 返回的 base64 或 `Read` 返回的二进制内容会迅速撑大上下文；
- 会话文件、压缩输入和 token 统计都会被图片字节污染；
- 历史中有几十张图时，即使本轮完全不需要，它们仍然占用请求和费用。

但如果只在消息里写“用户发过一张截图”，模型以后又无法定位原文件，也不能重新查看。

因此，当前设计把“长期保存”和“本次发送”分开：

```text
会话保存图片在哪里
当前请求才发送图片内容
```

一张图片在系统里依次使用三种表示：

```text
磁盘文件或 artifact
  -> 消息中的 file_reference 文本
  -> 当前出站 UserMessage 中的 Media
```

这三种表示分别解决字节存储、会话寻址和模型投递问题，不能混为一谈。

## 2. 一页看懂：图片处理消息链

### 2.1 用户直接附图

用户在输入框中输入图片路径，或把文件拖进终端。终端拖拽并没有上传文件，只是把路径粘贴到输入框。

详细过程：

```text
用户输入图片路径
  └── ImageAttachmentDetector 按文件内容确认它是图片

提交用户消息
  └── 原路径仍在用户原文中，正文末尾追加 file_reference

会话保存 UserMessage
  └── text = 用户原文 + file_reference

VisionMaterializer 处理当前出站 Prompt
  └── 从引用读取图片，给原 UserMessage 增加 Media

带图片的 UserMessage 交给 provider
```

压缩成消息链：

```text
输入框图片路径
  -> UserMessage(text = 原文 + file_reference)
  -> 原 UserMessage 增加 media
  -> provider
```

用户图片随本次请求发送，不需要先由模型调用 `Read`。

### 2.2 工具读取图片

工具结果消息不能直接承载 `Media`，因此工具图片需要追加一条临时 `UserMessage`。

详细过程：

```text
UserMessage
  └── 用户要求读取或分析文件

AssistantMessage
  └── tool_calls = Read(image.png)

项目执行 Read
  └── 图片保存或引用到磁盘，ToolResponseMessage 只返回 file_reference

Spring AI 工具循环再次调用 ChatModel
  └── VisionMaterializer 在工具结果后追加 UserMessage
      ├── text  = 工具图片名称说明
      └── media = 工具读取的图片

完整 messages 交给 provider
```

压缩成消息链：

```text
UserMessage
  -> AssistantMessage(tool_calls)
  -> ToolResponseMessage(responseData = file_reference)
  -> 新增 UserMessage(media = 工具图片)
  -> provider
```

原来的 `ToolResponseMessage` 不会被替换。新增消息只负责承载工具结果消息无法承载的图片。

### 2.3 会话消息和出站消息

会话长期保存：

```text
用户图片：UserMessage.text 中的 file_reference
工具图片：ToolResponseMessage.responseData 中的 file_reference
```

当前请求临时增加：

```text
用户图片：原 UserMessage.media
工具图片：新增 UserMessage.media
```

请求结束后，`Media` 不写回历史。历史图片需要重新查看时，模型调用 `Read`，重新走工具读取图片消息链。

## 3. 三种图片表示各自解决什么问题

| 表示 | 存放位置 | 生命周期 | 作用 |
| --- | --- | --- | --- |
| 图片文件或 artifact | 项目文件系统、`.codetui/artifacts/` | 跨请求 | 保存真实图片字节 |
| `file_reference` | `UserMessage.text` 或 `ToolResponseMessage.responseData` | 跨会话 | 保存可恢复的路径和元数据 |
| `Media` | 当前出站 `UserMessage` | 单次请求 | 把图片交给 provider |

### 3.1 图片文件和 artifact

图片真实字节始终在磁盘文件中。

项目内图片通常直接指向原文件。项目外图片、MCP 内联图片等没有受信任项目路径的内容，会进入：

```text
.codetui/artifacts/<sha256>.<ext>
```

`MediaArtifactStore` 使用内容 SHA-256 寻址，相同内容幂等去重，并通过临时文件和原子移动写入。对图片还会维护 `latest.<ext>` 软链，方便终端用户打开最近图片。

`MediaArtifact` 记录真实文件、相对路径、MIME、尺寸、大小、来源和文件名等元数据。它是 Java 对象，不会直接进入会话。

### 3.2 `file_reference`

`FileReference.render` 把图片元数据渲染为结构化文本，概念上是：

```text
<file_reference>
id: sha256:...
kind: image
mime_type: image/png
size_bytes: ...
dimensions: 1440x900
name: bug.png
path: docs/bug.png
delivery: not_in_view
reason: ...
</file_reference>
```

引用承担两个职责：

1. 让模型知道消息提到了哪张图片；
2. 让程序以后能安全地根据 `path` 重新读取图片。

它不是图片内容，也不放在 `UserMessage.context` 中，而是普通消息文本的一部分。

### 3.3 `Media`

`Media` 是当前请求的图片载荷。`VisionMaterializer` 从引用读取图片、完成格式处理和预算判定后，才创建：

```text
Media
  mimeType = 实际出站 MIME
  data     = byte[]
  name     = 清洗后的文件名
```

`Media.data` 必须使用 `byte[]`。实际 provider 请求组装对 `URI`、`String` 和 `byte[]` 之外的类型支持不一致，错误类型可能被静默跳过。

## 4. 用户图片为什么这样处理

### 4.1 为什么直接识别路径

终端无法像网页上传控件一样接收拖入文件。文件拖到终端时，终端只会粘贴路径。因此项目直接识别输入文本里的图片路径，不额外要求 `@图片` 语法。

识别支持：

- 相对项目路径；
- 绝对路径；
- `~/` 展开；
- 反斜杠转义空格；
- 单引号和双引号；
- 中文文件名；
- 一次输入多个图片路径。

裸路径识别必然会误附。例如：

```text
把 docs/bug.png 复制到 tmp/
```

这句话中的路径也满足图片判定。当前设计接受这个代价，用实时附件提示和 `Ctrl+X` 撤销解决，而不是猜测用户句意。

`Ctrl+X` 只取消附件语义，不删除输入框里的路径。路径仍作为普通文本发送，因为用户可能正是在讨论这个文件。

### 4.2 为什么图片路径不会被替换

提交时，`CodeTuiView.injectAttachments` 在原文末尾追加引用，而不是删除原路径：

```text
用户原文中的 docs/bug.png
  +
结构化 file_reference
```

原路径保留用户表达，引用块服务程序解析。实时 UI 显示用户原文，会话保存注入引用后的文本；恢复会话时，显示层再把引用块压成一行附件说明。

### 4.3 为什么按魔数而不是扩展名

扩展名和外部声明都不可信：

- 文本文件可以命名为 `.png`；
- 图片可能没有扩展名；
- MCP 声明 MIME 可能错误；
- BMP、TIFF 等格式容易被错误归类。

`MagicSniffer` 使用文件内容和 MIME 父类型判断真实类型。附件识别只读文件前 64 KiB，不为一次输入提示解码整张图片；负结果也缓存，避免每个渲染帧重复读取同一非图片路径。

### 4.4 为什么项目内图片不复制

项目内图片可能是正在修改的设计稿或截图：

```text
docs/design.png
```

如果提交附件时复制快照，用户后来更新原文件，模型再次 `Read` 引用路径时仍会看到旧版。直接引用原文件可以保持：

```text
路径身份稳定，内容随项目文件更新
```

因此普通项目内文件使用路径身份，而不是把每次内容变化都视为新附件。

### 4.5 为什么项目外图片必须复制

外部图片例如：

```text
~/Desktop/bug.png
```

不能直接写入可兑现引用。`FileReferenceParser` 只允许项目根目录内的真实文件，防止外部文本构造路径诱导系统上传敏感文件。

因此用户明确选择的项目外图片需要先复制到：

```text
.codetui/artifacts/<内容哈希>.png
```

引用只包含项目内副本路径。复制的主要目的不是缓存，而是让显式选择的外部图片重新落入受信任路径边界。

### 4.6 为什么不支持视觉时保留输入

用户附图但当前模型不支持视觉时，消息不会提交，输入框也不会清空。用户可以切换模型后直接重发。

未知模型默认判定为不支持。误判“不支持”只会产生可见拦截；误判“支持”则可能上传图片后得到 400，代价更高。

## 5. 工具图片为什么先外置成引用

### 5.1 ToolResponseMessage 没有 media

Spring AI 的工具结果主要包含：

```text
工具调用 id
工具名
responseData 文本
```

它不能直接表达：

```text
ToolResponseMessage(media = 图片)
```

所以工具图片先作为引用进入 `ToolResponseMessage.responseData`，随后由 `VisionMaterializer` 追加带 `Media` 的 synthetic `UserMessage`。

### 5.2 MCP 图片为什么必须即时外置

MCP 工具可能在 JSON 内容块中直接返回 base64 图片。若原样进入工具结果，base64 会进入模型和会话历史。

`McpMediaParser` 提取文本块和媒体块：

- 文本块原样保留；
- 图片块解码并写入 artifact store；
- 工具结果中用 `file_reference` 替换图片块；
- 单个媒体块失败时返回占位，不泄漏原 base64。

真实 MCP 服务曾返回没有 `type`、只有 `data + mimeType` 的媒体块。早期测试 fixture 总带 `type`，测试全绿但线上仍泄漏 base64。现在解析器兼容这种真实格式，测试也使用真实形状。

### 5.3 Read 图片为什么不能只看返回字符串

`Read` 读取 PNG 时，返回值可能是带行号的 hexdump。它看起来仍像文本，简单的“乱码比例”判断可能认为它不是二进制，导致几十或几百 KiB 内容进入会话。

正确判定方式是：

```text
从工具参数反查被读取的磁盘路径
  -> 检查磁盘原文件的真实类型
  -> 非文本文件转换为引用
```

只有无法反查来源的输出，才用 `BinarySniff` 做兜底判断。

### 5.4 为什么文本文件不能一起外置

早期设计曾尝试把所有文件内容都替换成引用，以减少上下文。真实编码流程证明这会破坏工作：

- `Edit.old_string` 依赖精确文本和空白；
- 模型下一轮需要继续理解刚读过的源码；
- 引用化后模型必须重新 `Read`，节省的 token 很快被抵消；
- 多一次工具调用还增加延迟和失败面。

最终规则收敛为：

```text
文本文件：原文保留
图片、视频、二进制：外置成引用
```

文本累积交给会话压缩解决，不由媒体层激进删除。

### 5.5 为什么已确认的媒体外置失败必须 fail-closed

如果已经确认返回内容是图片或二进制，外置失败时不能退回原始内容。否则“容错”会重新把 base64 或 hexdump 泄漏进会话。

正确降级是返回说明性占位：

```text
工具返回二进制文件，外置失败后内容已从会话移除
```

普通文本检测失败可以保守保留原文；已确认媒体的失败方向必须相反。

### 5.6 为什么还需要回合间兜底

即时 `MediaExternalizingCallback` 是主要路径。`SessionFileExternalizer` 在新回合提交开头再次检查历史工具结果，把绕过即时识别的旧式或异常非文本结果替换成引用。

它只处理可反查的项目内非文本文件：

- 已是引用则跳过；
- 文本文件不处理；
- Bash 等无法反查文件的普通输出不处理；
- 无改动时返回原列表引用，避免无意义写盘。

## 6. file_reference 为什么是安全边界

引用是文本，会流经用户输入、模型、工具和外部内容，不能因为格式像系统生成就天然信任。

`FileReferenceParser` 执行以下检查：

- 必填字段必须齐全；
- 只接受 `kind: image`；
- `path` 必须存在；
- 解符号链接后仍必须位于项目根目录内；
- 重复字段导致整块拒绝，不猜取第一个还是最后一个；
- 文件名中的控制字符必须清洗；
- 只扫描真实用户消息和工具结果，不扫描 assistant 消息。

### 6.1 为什么必须解符号链接

单纯：

```text
normalize(path).startsWith(root)
```

无法识别项目内符号链接实际指向项目外文件，也会在 macOS `/tmp -> /private/tmp` 等环境中误判真实路径。

路径包含判断和相对路径生成必须共用 `PathContainment` 的真实路径口径，避免一边认为在 root 内、另一边生成越界引用。

### 6.2 为什么不扫描 AssistantMessage

模型能看到引用格式，也可能在回答中复述甚至构造一段引用。如果系统扫描 assistant 文本，就等于允许模型自己的输出决定上传哪个本地文件。

因此图片兑现只信任：

- 当前真实 `UserMessage` 中的引用；
- 当前回合 `ToolResponseMessage` 中的引用。

### 6.3 为什么生成端和解析端都要防注入

Unix 文件名允许换行。恶意文件名可以伪造：

```text
name: evil
kind: image
path: other-file
```

只在生成端清洗不够，因为引用还可能来自外部文本；只在解析端拒绝也不够，因为系统不应主动产生歧义格式。

当前策略是：

```text
生成端清洗控制字符
+
解析端遇重复字段整块拒绝
```

## 7. 当前回合如何决定哪些图片发送

### 7.1 当前回合锚点

`VisionMaterializer` 使用纯位置规则：

```text
当前回合起点 = 最后一条非 synthetic UserMessage
当前回合范围 = 锚点消息及其后的消息
```

只兑现：

- 锚点用户消息中的用户附件；
- 锚点之后工具结果中的图片引用。

不兑现：

- 锚点之前的历史图片；
- assistant 文本中的引用；
- synthetic 图片消息本身作为新锚点。

这让历史图片的自动视觉成本为零，不需要额外维护“哪些图已经发过”的状态。模型需要重看历史图时调用 `Read`，新工具结果位于当前锚点之后，因此重新进入可兑现范围。

### 7.2 为什么工具图片使用 synthetic UserMessage

`ToolResponseMessage` 没有 `media`，所以工具图片消息链是：

```text
AssistantMessage(tool_calls)
  -> ToolResponseMessage(file_reference)
  -> UserMessage(text = 图片名称, media = 图片)
```

原工具结果一个字都不改，因为它承担工具调用配对和图片路径绑定。新增消息正文只列真正附带的图片名，并确保名称顺序与 `Media` 顺序一致。

新增消息写入：

```text
metadata["codetui.synthetic"] = true
```

即使未来 Spring AI 升级后这条临时消息意外回流，它也不会被误认为新的真实用户锚点。否则回合中途会突然找不到原用户消息及其后的工具图片，而且不会报错。

### 7.3 去重和优先级

单次请求先收集用户图片，再收集工具图片，并按引用 SHA 去重。因此同一张图既由用户附带又被工具读取时：

- 只发送一份；
- 保留在原用户消息上；
- 不再为工具结果追加重复图片。

顺序使用稳定的插入顺序，确保 synthetic 消息中的第 N 个文件名对应 `media` 中第 N 张图。

## 8. 图片格式、缩放和 OOM 防护

`ImagePreparer` 位于引用解析和 `Media` 构造之间。

| 实际格式 | 处理方式 | 原因 |
| --- | --- | --- |
| PNG / JPEG / GIF | 小图原样；长边超过 1568 时等比缩放 | Provider 支持，缩放可降低字节和视觉 token |
| BMP / TIFF | 解码后转 PNG | JDK 能解码，但多数模型 API 不直接接受 |
| WebP | 原样透传 | Provider 接受，但 JDK 默认无法稳定解码缩放 |
| HEIC / AVIF | 当前不兑现 | JDK 默认解码能力不足，Provider 支持也不一致 |

### 8.1 MIME 仍然以魔数为准

引用中的 MIME 和 MCP 声明只作为提示。`ImagePreparer` 再次读取文件头确认真实类型，避免被错误扩展名或声明带入错误处理路径。

### 8.2 为什么最大长边是 1568

大截图通常远高于模型理解所需分辨率。长边缩到 1568 能保留界面文字和结构，同时显著减少上传字节和视觉 token。

缩放保持宽高比。处理后的 PNG 如果仍超过 4 MiB，再转 JPEG；仍超限则不兑现。

### 8.3 为什么必须在完整解码前检查像素数

压缩文件大小不能代表解码内存。高压缩比 PNG 可能只有几十 MiB，解成 `BufferedImage` 后占用数 GiB。

正确顺序是：

```text
ImageReader 只读宽高
  -> width × height <= 5000 万像素
  -> 通过后才完整解码
```

检查放在 `ImageIO.read` 之后就失去 OOM 保护意义。测试使用解码计数器确认超像素图片没有进入完整解码，而不是只断言最终返回空。

### 8.4 为什么图片准备需要缓存

一个工具回合可能多次调用模型，每次都重新组装完整请求。同一张用户图片会随多个工具迭代重复进入候选集。

如果每次都重新：

```text
解码 -> 缩放 -> PNG/JPEG 编码
```

CPU 和内存分配会成倍增长。

缓存键包含绝对路径、mtime 和最大边长，文件更新后通常会重新准备。缓存只保存成功结果。

当前实现没有显式缓存容量和过期清理，这是后续需要关注的内存边界。

## 9. 为什么需要视觉预算

只限制每次请求无法控制完整工具回合。假设模型连续调用截图工具 20 次，每次请求都满足单请求上限，总视觉成本仍会失控。

当前预算：

```text
每请求：
  用户图片最多 3 张
  工具图片最多 1 张
  视觉估算最多 6000 token

每回合：
  累计兑现最多 12 张·次
```

### 9.1 为什么用户图片优先

用户一次附带的图片通常直接表达任务意图，例如：

```text
照这张设计稿修改页面
```

工具循环可能随后产生很多截图。如果所有图片放在同一个“最新优先”池，用户设计稿会被工具截图挤掉。

因此：

- 用户图片先过预算；
- 工具图片只取最新一张；
- 用户图片和工具图片分别限制数量。

### 9.2 为什么用户侧收集全部引用

用户第 4 张及之后即使不能发送，也要识别出来并把 `delivery` 改为 `budget_exceeded`。否则它仍写着 `not_in_view`，会诱导模型再次 `Read`，但下一次仍然撞上相同预算。

工具结果不能改写，因此工具侧只收集最新一个候选。继续解析旧工具图无法反馈状态，只会浪费准备成本。

### 9.3 为什么还要单回合上限

每请求 6000 token 并不能阻止一次工具循环调用模型很多次。单回合 12 张·次限制的是累计重传成本。

预算按当前回合 key 分桶，避免共享装饰器时不同回合互相消耗额度。计数表有界，只保留有限数量的回合 key。

## 10. delivery 状态解决什么问题

引用中的 `delivery` 不只是显示状态，它直接影响模型下一步行为。

| 状态 | 含义 | 模型应该如何处理 |
| --- | --- | --- |
| `delivered` | 图片已随当前请求发送 | 直接分析图片 |
| `reference_only` | 当前模型不支持图片 | 不要重复 Read，需换视觉模型 |
| `not_in_view` | 当前未发送，但可通过 Read 带回 | 需要时调用 Read |
| `budget_exceeded` | 本请求张数或 token 预算不足 | 减少图片或单独读取 |
| `turn_budget_exhausted` | 本回合累计额度耗尽 | 结束本回合，下一回合再看 |

为什么必须改写状态：

- 已发送仍写 `not_in_view`，模型同时收到图片和“看不到”的矛盾信号；
- 超预算仍写 `not_in_view`，模型会反复调用 `Read`；
- 单请求预算不足和单回合耗尽不区分，模型无法判断重试是否有意义。

用户引用位于可改写的锚点消息中，因此出站副本会更新它的状态。工具结果要求保持原文，旧工具引用不会被改写；synthetic 消息只列真正发送的工具图片。

HEIC、读取失败、超像素等“无法准备”的情况目前没有精确状态。实现有意不硬套 `budget_exceeded`，因为减少图片数量也不能解决格式不支持。这是当前状态模型的已知表达缺口。

## 11. 压缩、恢复和文件生命周期

### 11.1 为什么压缩必须保留精确引用

普通 LLM 摘要可能把：

```text
<file_reference path="docs/bug.png">
```

概括成：

```text
用户提供了一张报错截图
```

图片仍在磁盘，但精确路径丢失，模型再也无法 `Read`。

`MediaReferencePreservingCompactionStrategy` 从真正被归档的事件中收集引用，并生成附件清单：

- 不从每次压缩请求的全部历史重复收集；
- 只信任用户消息和工具结果；
- 按路径去重；
- 最多保留最近 20 条；
- 如实说明更早附件丢失数量；
- 清单消息标记 synthetic，避免抢走视觉回合锚点。

### 11.2 会话恢复如何显示引用

会话文件仍保存完整引用块。`-c` 恢复时，`HistoryReplay` 只在显示层把它压成：

```text
📎 bug.png (1440×900)
```

显示简化不改变会话中的路径和元数据。

### 11.3 Artifact GC 的边界

artifacts 默认上限 500 MiB，只在启动时扫描，超限后按 mtime 删除最旧文件。

GC 不扫描所有会话引用，因此旧会话引用的 artifact 可能被删除。后果是以后 `Read` 得到文件不存在，而不是读取到错误文件。项目内原文件不归 artifact store 所有，不会被 GC 删除。

这是用可恢复失败换取简单、低成本生命周期管理的设计。

## 12. 关键历史教训

图片处理反复修改的原因，是很多问题只有真实协议、真实终端和长工具循环才能暴露。

### 12.1 假 MCP fixture 会掩盖真实泄漏

早期测试中的媒体块都有 `type`，真实 MCP 返回却可能只有 `data + mimeType`。解析器漏识别后，base64 原样进入模型。测试必须使用真实 server 的返回形状，而不是只使用理想协议样例。

### 12.2 工具返回字符串不能代表原文件类型

PNG 经 `Read` 后可能成为带行号 hexdump，看起来不像典型二进制。按字符串乱码比例判断会漏掉它。文件类工具必须反查磁盘原件并按魔数判断。

### 12.3 外置所有文件会破坏编码工作流

“所有文件内容都不进会话”看似能节省上下文，实际让 `Edit.old_string` 丢失精确文本，并迫使模型重复读取源码。最终规则改为只外置图片和二进制，文本原样保留。

### 12.4 外置失败不能退回原媒体

对已确认媒体返回 `raw`，会重新泄漏 base64 或 hexdump。已确认媒体必须 fail-closed，返回占位；普通文本才可以保守放行。

### 12.5 路径边界必须解符号链接

词法 `startsWith` 既可能被符号链接绕过，也会在 macOS 路径映射上误判。路径判断、相对路径生成和引用解析必须使用同一套真实路径口径。

### 12.6 文件名本身可以注入引用字段

Unix 文件名允许换行。生成端必须清洗控制字符，解析端必须拒绝重复字段，避免不同消费者对同一歧义引用作不同解释。

### 12.7 消息绝对下标不能跨序列化层使用

Spring AI 可能把一个 `ToolResponseMessage` 的多个结果展开成多个 HTTP tool 消息。对象层下标因此和 JSON 下标不同。DeepSeek 图片关联最终改成 user 消息序号，避免有工具历史时图片静默错位。

### 12.8 单请求预算挡不住工具循环

每次请求都满足限制，不代表整个回合成本可控。必须增加单回合累计上限，并让统计也使用同一回合口径。

### 12.9 “上一请求”的视觉统计几乎总是零

回合额度耗尽后，后续工具迭代通常兑现 0 张。若 `/context` 只显示最后一次请求，用户看到的几乎总是零。统计改为本回合累计，0 张请求不清空已有数据。

### 12.10 PTY 测试不能覆盖 OS 全局热键

附件撤销最初使用 `Ctrl+G`，PTY 测试可以注入该字节，但真实 macOS 上按键被浏览器扩展注册为全局热键，终端根本收不到。最终改用 `Ctrl+X`。关键终端 UX 需要真人实机验证，PTY 只能证明字节到达应用后的行为。

## 13. 关键类与测试索引

### 13.1 关键类

| 类 | 作用 |
| --- | --- |
| `ui.ImageAttachmentDetector` | 从输入文本识别图片路径 |
| `ui.CodeTuiView.injectAttachments` | 把用户图片追加成消息引用 |
| `media.MagicSniffer` | 按文件内容判断真实类型 |
| `media.PathContainment` | 解符号链接后执行项目路径边界检查 |
| `media.MediaArtifactStore` | 内容寻址、原子写和 artifact 去重 |
| `media.FileReference` | 生成图片引用和 delivery 状态 |
| `media.FileReferenceParser` | 安全解析可兑现引用 |
| `media.McpMediaParser` | 从 MCP 工具结果提取内联媒体 |
| `media.MediaExternalizingCallback` | 工具结果即时外置 |
| `media.SessionFileExternalizer` | 新回合开始时兜底清理历史非文本结果 |
| `media.ImagePreparer` | 嗅探、缩放、转码和 OOM 前置检查 |
| `media.VisionBudget` | 单请求和单回合视觉预算 |
| `media.VisionMaterializer` | 当前回合引用转 `Media`，并维护消息链 |
| `media.VisionMaterializingChatModel` | 在会话组装后、provider 调用前触发兑现 |
| `media.MediaReferencePreservingCompactionStrategy` | 压缩时保留附件寻址信息 |
| `media.ArtifactGc` | 启动时限制 artifact 磁盘占用 |

### 13.2 关键测试

| 测试 | 钉住的契约 |
| --- | --- |
| `ImageAttachmentDetectorTest` | 路径、转义、中文、魔数、数量和缓存 |
| `AttachmentInjectionTest` | 项目内外附件、引用注入、能力闸门和撤销 |
| `FileReferenceParserTest` | 越界、符号链接、重复字段和文件名注入 |
| `MediaExternalizingCallbackTest` | MCP、Read、项目外文件和 fail-closed |
| `VisionMaterializerTest` | 消息链、当前回合、去重、预算和 delivery |
| `ImagePreparerTest` | 格式、缩放、转码、缓存和解码前 OOM 防护 |
| `VisionBudgetTest` | token、回合上限和并发分桶 |
| `VisionMaterializingChatModelTest` | 出站接线、call/stream 和 options 转发 |

图片处理文档到 `UserMessage.media` 为止。之后分成两条 provider 适配路径：OpenAI、Anthropic、Qwen 和智谱如何使用 Spring AI 原生转换，见[其他 Provider 视觉能力实现原理](native-vision-providers.md)；DeepSeek 的特殊补图过程见 [DeepSeek 视觉能力实现原理](deepseek-vision.md)。
