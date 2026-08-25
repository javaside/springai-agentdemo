# 实现原理文档

本目录收录项目关键功能的实现原理，重点解释：

- 消息和数据在系统中如何变化；
- 当前实现为什么这样设计；
- 它解决了哪些真实问题；
- 相邻模块之间的职责边界在哪里。

这些文档不以罗列源码为目标。阅读时应先理解完整信息流，再按关键类索引进入代码。

## 推荐阅读顺序

当前三篇文档共同解释图片从输入到 provider HTTP 请求的完整过程：

```text
用户图片或工具图片
  -> 图片文件和 file_reference
  -> UserMessage.media
      ├── OpenAI / Anthropic / Qwen / 智谱原生适配
      └── DeepSeek 特殊补图
```

建议按以下顺序阅读：

1. [图片处理实现原理](image-processing.md)
2. [其他 Provider 视觉能力实现原理](native-vision-providers.md)
3. [DeepSeek 视觉能力实现原理](deepseek-vision.md)

## 当前文档

### 图片处理实现原理

入口是用户输入的图片路径或工具返回的图片，出口是当前出站 Prompt 中的 `UserMessage.media`。

文档解释图片引用、artifact、安全边界、格式处理、视觉预算和消息链，不讨论各 provider 如何生成自己的 HTTP 图片格式。

阅读：[image-processing.md](image-processing.md)

### 其他 Provider 视觉能力实现原理

入口是已经带有 `Media` 的 `UserMessage`，出口是 OpenAI、Anthropic 以及 OpenAI 兼容端点的图片请求内容块。

文档解释 Spring AI 为什么能原生处理这些 provider，以及协议兼容、端点支持和模型视觉能力之间的区别；不展开图片如何产生，也不展开 DeepSeek 的 Registry 补图实现。

阅读：[native-vision-providers.md](native-vision-providers.md)

### DeepSeek 视觉能力实现原理

入口同样是已经带有 `Media` 的 `UserMessage`，出口是补齐图片后的 DeepSeek HTTP JSON。

文档只解释 `spring-ai-deepseek` 序列化遗漏 `Media` 后，项目如何通过 Registry 和 HTTP 请求体改写补图；通用图片处理和其他 provider 的原生适配分别由前两篇负责。

阅读：[deepseek-vision.md](deepseek-vision.md)

## 文档边界

三篇视觉文档的职责边界是：

```text
图片处理实现原理
  输入：图片路径、工具图片、file_reference
  输出：UserMessage.media

其他 Provider 视觉能力实现原理
  输入：UserMessage.media
  输出：OpenAI / Anthropic / 兼容端点请求内容块

DeepSeek 视觉能力实现原理
  输入：UserMessage.media
  输出：DeepSeek HTTP 图片内容块
```

一篇文档可以链接到相邻阶段，但不应复制相邻文档的主体内容。

## 后续新增约定

以后新增实现原理文档时：

1. 将文档放在本目录；
2. 在“当前文档”中登记标题、入口、出口和职责边界；
3. 如有前置或后续文档，在“推荐阅读顺序”中补充关系；
4. 先展示完整信息流，再解释类、字段、序列化或协议细节；
5. 避免复制其他文档已经负责的原理，只通过链接连接相邻阶段。
