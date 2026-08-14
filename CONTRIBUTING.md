# 贡献指南

感谢关注本项目。这里记录了几条**与常见 Java 项目不同、不写下来就会踩**的约定。

## 环境

- JDK 17+（`maven.compiler.release=17`，不要用更高的语言特性）
- Maven 3.9+

## 构建与测试

**测试命令必须带模块作用域与 `-am`：**

```bash
mvn -pl springai-code-tui -am test                                                       # 全量
mvn -pl springai-code-tui -am test -Dtest='SomeTest' -Dsurefire.failIfNoSpecifiedTests=false   # 单个类
```

`-am` 不可省：`springai-code-tui` 依赖同仓库的兄弟模块 `springai-tamboui-inline-patch`
（compile 依赖），而它**不发布到任何远程仓库**。不带 `-am` 时只有本地 `~/.m2` 恰好装过
同一版本才跑得通——版本一升级或换个干净机器就会报
`Could not resolve dependencies ... springai-tamboui-inline-patch`。

单类命令的 `-Dsurefire.failIfNoSpecifiedTests=false` **同样不可省**：`-Dtest` 是全局系统
属性，`-am` 拉进 reactor 的兄弟模块也会用 `SomeTest` 去匹配它自己的测试目录，匹配不到时
surefire 默认视为失败（`No tests matching pattern "SomeTest" were executed`），整个构建在
兄弟模块上就断了。该参数只放宽「`-Dtest` 模式没有匹配到任何测试」这一种错误，**不吞**断言
失败、编译失败、运行时异常——实测（2026-08-15）改坏断言后带此参数照样 `BUILD FAILURE`。

**不要用整仓 `mvn test`** —— 仓库里有几个空的 demo 模块会让它失败。这条也别指望
`surefire.failIfNoSpecifiedTests` 来救：它只作用于带 `-Dtest` 的调用，对整仓失败无效。

打发布包：

```bash
mvn -pl springai-code-tui -am package -Pdist      # 产出 target/*-dist.tar.gz 与 .zip
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
mvn -pl springai-code-tui -am test -Dtest='CodingAgentSpikeTest#todoTurnIdBinding' -Dsurefire.failIfNoSpecifiedTests=false
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

## 发布

发版说明写在 `docs/release-notes/vX.Y.Z.md`，`CHANGELOG.md` 只加一行索引。

**说明里的链接一律写相对路径**（`(v1.7.0.md)`、`(../../LICENSE)`）。这是唯一在四个环境里
都成立的写法：仓库文件视图、本地编辑器、以及仓库将来搬到任何别的托管站。

**但 GitHub Release 页面解析不了相对路径**——它不在 `docs/release-notes/` 这个目录下，
`(v1.7.0.md)` 在那里一律 404。所以别为了 Release 页面把源文件改成绝对 URL（那等于为一个
渲染环境赔掉另外三个，而且把 `github.com/<owner>/<repo>` 焊死在文档里）。转换交给发布脚本，
它在发布那一刻做替换，仓库里的文件一个字都不动，仓库地址从 `git remote get-url origin` 推导：

```bash
python3 scripts/publish-release-notes.py --print v1.8.0   # 先看一眼转换结果
python3 scripts/publish-release-notes.py v1.8.0           # 发布这一个
python3 scripts/publish-release-notes.py --all            # 全部重刷
```

`gh release create` 建新 Release 时同理——用 `--print` 的输出，别直接 `--notes-file` 源文件。

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
