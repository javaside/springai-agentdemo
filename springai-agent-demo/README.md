# springai-agent-demo —— Spring AI 智能体能力（原始 API · 纯 Java）

**不依赖 Spring Boot**，用 Spring AI 原始库演示智能体能力。`ChatClient` 等对象同样在
`AgentDemoApplication.main` 里手动创建。

## 运行

```bash
export DEEPSEEK_API_KEY=你的key
mvn -pl springai-agent-demo -am package
java -jar springai-agent-demo/target/springai-agent-demo.jar
```

## 示例清单

| 菜单项 | 示例 | 关键类 | 你将学到 |
|------|------|--------|---------|
| 1 | 工具调用 | `ToolCallingDemo` | 用 `@Tool` 定义方法、`tools(...)` 注册，让模型按需调用 Java 代码 |
| 2 | 对话记忆 | `ChatMemoryDemo` | 用 `MessageWindowChatMemory` + `MessageChatMemoryAdvisor` 实现多轮记忆 |
| 3 | 多步 Agent | `MultiStepAgentDemo` | 模型自动规划并连续调用多个工具完成一个任务 |
| 4 | Advisor 顺序 | `ToolMemoryAdvisorDemo` | `ToolCallingAdvisor` 配合 `MessageChatMemoryAdvisor`，演示两者**顺序**决定工具调用的中间消息是否进入记忆 |
| 5 | 工具搜索 | `ToolSearchDemo` | `ToolSearchToolCallingAdvisor` 实现“按需发现工具”，工具很多时省 token |
| 6 | Skill 技能 | `SkillToolDemo` | 第三方 `spring-ai-agent-utils` 的 `SkillsTool`：把“可复用的领域指令”按需注入对话 |

## 示例 4 详解：ToolCallingAdvisor 与记忆的「顺序」意义

Spring AI **2.0 已移除 ChatModel 内部的工具执行**（1.x 时工具循环在 ChatModel 内部完成），
现在工具调用统一由 `ToolCallingAdvisor` 在 **advisor 链**里完成——只要用 `.tools(...)` 注册了工具，
`ChatClient` 就会自动注册一个 `ToolCallingAdvisor`（最多一个）。既然工具循环在 advisor 链里，
记忆 advisor 能否“看见”每一轮工具调用，就取决于两者的 `getOrder()`（**order 越小越靠外层**）：

| 顺序 | 记忆位置 | 工具调用中间消息是否入记忆 | 适用场景 |
|------|---------|--------------------------|---------|
| 记忆 order < 工具 order（**默认**） | 外层，包住整个工具循环 | **否**，只记“提问 + 最终回答” | 想要干净精简的对话历史 |
| 记忆 order > 工具 order | 内层，处于工具循环之内 | **是**，连“请求调用工具/工具返回”都记下 | 想持久化完整工具轨迹（审计/调试/复盘） |

运行该示例会用同一个问题分别跑两种顺序，并打印各自记忆里实际存了几条消息——
默认顺序存 2 条，反转顺序存 6 条，眼见为实。参考官方文档
[Recursive Advisors / ToolCallingAdvisor](https://docs.spring.io/spring-ai/reference/api/advisors-recursive.html#_toolcallingadvisor)。

## 示例 5 详解：工具搜索 / 动态工具发现（ToolSearchToolCallingAdvisor）

工具一多，传统做法把**所有**工具定义一次性发给模型，既费 token 又容易选错。
`ToolSearchToolCallingAdvisor` 改为「按需发现（progressive tool disclosure）」：

1. 启动时把你注册的全部工具建立**索引**，但不直接暴露给模型；
2. 只给模型一个内置元工具 `toolSearchTool`，让它用自然语言**搜索**所需工具；
3. 把搜到的工具**注入**后，模型再真正调用。

示例注册了 9 个工具（请假/会议室/快递/翻译/乘法/时间/天气…），只问一个“年假”问题。
为了把**完整交互过程**看清楚，示例自带一个小型 `RoundLoggingAdvisor`，按“轮次”整齐打印
（用 `System.out`，不走日志框架，所以没有 `DEBUG ... -` 前缀、不会与其它输出错乱）。真实运行片段：

```
📇 已为本次会话建立工具索引，共 9 个工具（但不会全部发给模型）
  ┌─ 第 1 轮 ──────────────
  │ ↗ 本轮模型可用工具: toolSearchTool                       ← 一开始只给“搜索工具”
  │ ↘ 模型决定: 请求调用工具 → toolSearchTool({"arg0":"查询员工年假余额 张三", ...})
  └────────────────────────
  │ 🔎 模型搜索工具: query="查询员工年假余额 张三" → 命中 []   ← regex 没匹配上
  ┌─ 第 2 轮 ──────────────
  │ ↗ 本轮模型可用工具: toolSearchTool
  │ ↘ 模型决定: 请求调用工具 → toolSearchTool({"arg0":"年假 查询 员工 假期余额", ...})  ← 模型自己换词重试
  └────────────────────────
  │ 🔎 模型搜索工具: query="年假 查询 员工 假期余额" → 命中 [queryAnnualLeave, ...]
  ┌─ 第 3 轮 ──────────────
  │ ↗ 本轮模型可用工具: queryAnnualLeave, checkMeetingRoom, ...  ← 命中的工具被注入进来
  │ ↘ 模型决定: 请求调用工具 → queryAnnualLeave({"arg0":"张三"})
  └────────────────────────
  ┌─ 第 4 轮 ──────────────
  │ ↘ 模型决定: 直接回答 → 张三当前剩余年假为 7 天。
  └────────────────────────
```

可见第 1 轮模型手里**只有** `toolSearchTool`（其余 8 个工具没发给它），搜索命中后工具才被注入——
这正是“按需发现”省 token 的原理。（顺带还能看到：regex 索引第一次没命中时，模型会**自己换关键词重试**，
这也是为什么生产环境常改用 **vector 语义索引**，对自然语言更鲁棒。）

**实现要点：**
- 依赖：`spring-ai-tool-search-advisor`（提供 advisor）+ `spring-ai-tool-search-tool`（提供 `ToolIndex`）。
- 索引实现三选一：**regex**（本示例用，关键词匹配，零额外依赖）、**lucene**、**vector**（语义检索，需向量模型）。
- `LoggingToolIndex` 装饰器包住 `RegexToolIndex`，打印每次工具搜索（🔎 行）。
- `RoundLoggingAdvisor` 是示例自带的一个简单 `CallAdvisor`，放在工具搜索 advisor 的**内层**（order 更大）
  才能拦截到循环里每一轮；用 `System.out` 打印，输出整齐、无日志前缀。
- 参考官方文档
  [Tool Calling / Tool Search Tool](https://docs.spring.io/spring-ai/reference/api/tools.html#tool-search-tool)。

## 示例 6 详解：Skill 技能（SkillsTool）

> 依赖第三方社区库 `org.springaicommunity:spring-ai-agent-utils`（针对 Spring AI 2.0），
> 官方文档：<https://spring-ai-community.github.io/spring-ai-agent-utils/v0.10.0/tools/SkillsTool>。

**什么是 Skill？** 一个含 `SKILL.md` 的文件夹。`SKILL.md` = 「YAML frontmatter（`name` + `description`）」+「正文指令」，
是一份**可复用的“操作说明书 / 领域知识模块”**。本示例的 `skills/git-commit-message/SKILL.md` 封装了
“如何按 Conventional Commits 规范写 git 提交信息”的完整规则。

**和普通 `@Tool` 工具的区别：** 普通工具执行 Java 代码返回数据；Skill 工具本质是**按需把一大段指令/提示词注入对话**。
模型平时只看到技能的简短 `name + description`（很省 token）；当它判断某技能有用时，调用名为 `Skill` 的工具、
**只传技能名**，工具便返回该 `SKILL.md` 的**完整正文**；模型读到详细指令后再产出结果——一种“渐进式披露
（progressive disclosure）”的提示词工程。

运行该示例（菜单 6），完整执行过程清晰可见：

```
🧰 已加载技能工具: Skill（模型一开始只能看到各技能的【名字+描述】，看不到正文，很省 token）

问：帮我给这次改动写一条规范的 git 提交信息：给登录接口加了图形验证码……

  ┌─ 第 1 轮 ──────────────
  │ ↗ 本轮模型可用工具: Skill
  │ ↘ 模型决定: 请求调用工具 → Skill({"command": "git-commit-message"})   ← 只凭描述就决定调用该技能
  └────────────────────────
  │ 🛠 技能工具被调用，入参: {"command": "git-commit-message"}
  │ 📜 技能返回内容（SKILL.md 正文，节选）:                                  ← 此刻才注入详细指令
  │     Base directory for this skill: .../skills/git-commit-message
  │     # 规范化 Git 提交信息  ...（Conventional Commits 规则全文）
  ┌─ 第 2 轮 ──────────────
  │ ↘ 模型决定: 直接回答 → feat(login): 登录接口增加图形验证码 ...          ← 读完指令后按规范作答
  └────────────────────────
```

**实现要点：**
- 依赖：`org.springaicommunity:spring-ai-agent-utils:0.10.0`。
- `SkillsTool.builder().addSkillsDirectory("…/skills").build()` 返回一个 `ToolCallback`（工具名固定为 `Skill`）。
  它用 `Files.walk` 在**文件系统**上递归查找 `SKILL.md`，所以技能目录要在运行时真实存在于磁盘
  （示例的 `resolveSkillsDir()` 会依次尝试仓库根目录 / 模块目录等候选路径）。
- `LoggingSkillCallback` 装饰器包住该 `ToolCallback`，打印技能的**入参**与**返回正文**。
- `RoundLoggingAdvisor`（同示例 5）按轮次打印交互，放在工具循环**内层**（order 更大）才能拦到每一轮。

## 工具是怎么定义的

见 `tools/` 目录：在普通类的方法上加 `@Tool(description=...)`、参数加 `@ToolParam(description=...)`，
description 就是给模型看的“说明书”，模型据此决定**何时调用、传什么参数**。注意：工具机制不依赖 Spring 容器，
普通对象 `new` 出来传给 `.tools(...)` 即可。

- `DateTimeTools`：获取当前时间、计算日期差
- `WeatherTools`：查询天气（演示用，返回假数据）
- `OfficeTools`：一组数量较多的办公类工具（请假/会议室/快递/翻译/乘法），用于示例 5 的工具搜索

## MCP 去哪了？

MCP 客户端配置较重（需外部 Server），用 Spring Boot starter 几行配置就能搞定，因此**迁到了
`springai-boot-demo`**，在那里演示更合适。
