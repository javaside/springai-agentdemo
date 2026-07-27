# 贡献指南

感谢关注本项目。这里记录了几条**与常见 Java 项目不同、不写下来就会踩**的约定。

## 环境

- JDK 17+（`maven.compiler.release=17`，不要用更高的语言特性）
- Maven 3.9+

## 构建与测试

**测试命令必须带模块作用域：**

```bash
mvn -pl springai-code-tui test                          # 全量
mvn -pl springai-code-tui test -Dtest='SomeTest'        # 单个类
```

**不要用整仓 `mvn test`** —— 仓库里有几个空的 demo 模块会让它失败。也**不要**用
`-DfailIfNoSpecifiedTests=false` 去盖这个问题，那只会把真实失败一起吞掉。

打发布包：

```bash
mvn -pl springai-code-tui package -Pdist      # 产出 target/*-dist.tar.gz 与 .zip
```

## 依赖真实网络 / API key 的测试

仓库里有若干**真机冒烟测试**，一律用 `@EnabledIfEnvironmentVariable` 门控——**没配对应变量就自动跳过，不算失败**：

| 测试 | 门控变量 |
| --- | --- |
| `CodingAgentSpikeTest` | `DEEPSEEK_API_KEY` |
| `BochaWebSearchSmokeTest` | `BOCHA_API_KEY` |
| `BraveWebSearchSmokeTest` | `BRAVE_API_KEY` |
| `McpStreamableHttpSmokeTest` | `CODETUI_MCP_SMOKE_URL` |
| `QwenRealStreamingToolCallSmokeTest` | `DASHSCOPE_API_KEY` |

新增这类测试请沿用同一门控模式。**测试里绝不能硬编码任何 key。**

### 一条已知的 flaky

`CodingAgentSpikeTest.todoTurnIdBinding` 走真实 DeepSeek 调用、单回合 60s 上限，实测常在
13–45s 之间浮动，偶尔会超时失败。撞上时**先单跑那一条确认**，不要以为是自己改坏了：

```bash
mvn -pl springai-code-tui test -Dtest='CodingAgentSpikeTest#todoTurnIdBinding'
```

## 提交约定

- 提交信息用 `type: 说明` 前缀（`feat:` / `fix:` / `refactor:` / `test:` / `docs:` / `release:`），正文用中文。
- 一次提交只做一件事。功能与其测试放在同一个提交里。
- **不要直接提交到 `main`**：开 `feature/xxx` 分支，完成后 `--no-ff` 合并。

## 改动流程

较大的改动（新功能、跨多文件的重构）请按仓库既有节奏走，产物都在 `docs/superpowers/` 下：

1. **设计**：`docs/superpowers/specs/YYYY-MM-DD-<主题>-design.md` —— 写清目标、非目标、已核准的事实依据、被淘汰的备选及理由。
2. **计划**：`docs/superpowers/plans/YYYY-MM-DD-<主题>.md` —— 拆成小任务，每个任务写清「先写失败的测试 → 跑到红 → 最小实现 → 跑到绿 → 提交」。
3. **实现**：按计划逐个任务做，每步都跑验证。

翻翻已有的 spec 就知道期望的详细程度。核心要求是：**凡是「实测得到的事实」都要写下证据**（`javap` 输出、`curl` 结果、字节码片段），别写没验证过的推测——仓库里已经有好几处「当初想当然、后来被实测推翻」的更正记录。

## 测试要求

- **先写测试，且必须真的看到它红**。看不到红就说明这条测试没测到目标行为。
- 断言要能被「反向改动」杀死。举例：只断言 `contains("503")` 是不够的，因为默认异常消息里本来就带状态码——得断言自定义前缀 `博查搜索失败：HTTP 503` 并断言**不含**「连不上」。
- 不确定一条测试是否有效时，**做一次变异测试**：把实现改坏，确认它变红，再改回来。改回来之后**务必在独立的命令里确认工作树干净**。

## 代码风格

- 跟着周围代码写：注释密度、命名、习惯用法都以同文件为准。
- 注释写**为什么**，不写「做了什么」。尤其是绕过某个库的坑时，把坑本身记下来（现象 + 实测证据），否则后人会把它当冗余删掉。
- 中文注释与中文错误消息是本仓库的常态，保持一致。

## 安全

发现安全问题请**不要开公开 issue**，见 [SECURITY.md](SECURITY.md)。
