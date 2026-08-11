# code-tui README 快速开始重构 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让下载 code-tui 发布包的用户能从 README 第一屏附近完成配置和启动，并消除 Java 版本及运行路径的矛盾。

**Architecture:** 根 README 只承担项目入口和短快速开始；随发布包分发的模块 README 承担完整安装、使用与深入文档。发布包主路径统一使用 `bin/config.env` 和平台启动脚本，源码构建、直接 JAR 运行及制包说明放到后部。

**Tech Stack:** Markdown、POSIX shell、Windows Batch、Maven Assembly

## Global Constraints

- 支持基线统一表述为 JDK 17+。
- 不新增 Java 版本检测，只纠正启动脚本注释和错误文案。
- 不新增 `QUICKSTART.md`。
- 不修改发布包目录结构、启动脚本名称或配置加载逻辑。
- `bin/config.env.example` 复制为 `bin/config.env` 是发布包推荐配置路径。
- 示例必须区分安装目录和智能体工作目录。
- 快速开始附近必须保留“不是安全沙箱”的醒目警告。
- 不修改历史发布说明。

---

## File Map

- `README.md`：仓库首页；提供简短、完整的下载运行路径，再介绍产品能力与教学模块。
- `springai-code-tui/README.md`：发布包主说明书；提供详细快速开始、安全边界、核心能力、配置和开发者说明。
- `springai-code-tui/src/package/bin/code-tui`：macOS/Linux 启动入口；只修正 JDK 文案。
- `springai-code-tui/src/package/bin/code-tui.cmd`：Windows 启动入口；只修正 JDK 文案。
- `springai-code-tui/src/package/assembly.xml`：不修改；用于验证上述 README、脚本、配置和指南确实进入发布包。

### Task 1: 重写发布包 README 的用户路径

**Files:**
- Modify: `springai-code-tui/README.md`
- Reference: `springai-code-tui/src/package/bin/config.env.example`
- Reference: `springai-code-tui/docs/guide/*.md`

**Interfaces:**
- Consumes: 发布包现有布局 `bin/`、`springai-code-tui.jar`、`lib/`、`docs/guide/`。
- Produces: 发布包内面向最终用户的唯一主说明书，供 Task 2 从根 README 链接。

- [ ] **Step 1: 记录现有 README 的关键事实，防止重排时丢失**

核对并保留以下信息：五家 provider、`/model`、权限安全边界、`-c`、日志位置、源码构建命令、制包命令、八个专题指南链接。

Run:

```bash
Grep "DeepSeek|ZHIPU|DASHSCOPE|ANTHROPIC|OPENAI|--continue|日志位置|mvn -pl|docs/guide" springai-code-tui/README.md
```

Expected: 每类事实至少有一个匹配；实施后重复运行，仍应全部有匹配。

- [ ] **Step 2: 将 README 顶部改为发布包快速开始**

将标题和一句话简介后的首个主体章节写成以下语义与顺序：

```markdown
## 快速开始（下载发布包）

前置条件：JDK 17+，以及至少一家支持的模型服务 API Key。

### macOS / Linux

1. 解压并进入安装目录。
2. 复制 `bin/config.env.example` 为 `bin/config.env`。
3. 编辑配置，取消任意一家 `*_API_KEY` 的注释并填写值。
4. 切换到一个可随意丢弃、且已由 Git 干净纳管的项目目录。
5. 使用安装目录中的 `bin/code-tui` 启动。

### Windows

使用 `.zip`，复制 `bin\config.env.example`，并通过 `bin\code-tui.cmd` 启动。
```

命令示例必须体现：

```bash
cd /path/to/disposable-git-project
/path/to/springai-code-tui-<version>/bin/code-tui
```

以及：

```bat
cd C:\path\to\disposable-git-project
C:\path\to\springai-code-tui-<version>\bin\code-tui.cmd
```

说明安装目录只存程序，工作目录才是智能体读写的目标；补充 `-c` / `--continue`；将环境变量方式放到“临时试用”提示中，不作为主流程。

- [ ] **Step 3: 在快速开始后保留短安全警告并重组后续章节**

按以下章节层次重排已有内容：

```markdown
## 安全声明
## 核心能力
## 常用操作
## 配置与数据位置
## 从源码构建与运行
## 制作发布包
## 使用指南
```

“核心能力”压缩为六组：模型与 provider、编码工具、权限与安全、子 agent 与计划、会话与记忆、MCP/Skills/视觉。保留已有专题链接，不在 README 重复专题机制。

“从源码构建与运行”保留：

```bash
mvn -pl springai-code-tui -am package
java -jar springai-code-tui/target/springai-code-tui.jar
```

“制作发布包”保留：

```bash
mvn -pl springai-code-tui clean package -Pdist
```

- [ ] **Step 4: 检查模块 README 的内容完整性和 Markdown 格式**

Run:

```bash
git diff --check -- springai-code-tui/README.md
```

Expected: exit 0，无空白错误。

Run:

```bash
Grep "快速开始（下载发布包）|bin/config.env|bin/code-tui|bin\\code-tui.cmd|JDK 17|--continue|不是安全沙箱|从源码构建与运行|制作发布包|docs/guide" springai-code-tui/README.md
```

Expected: 所有关键路径均有匹配。

- [ ] **Step 5: 提交模块 README 重构**

```bash
git add springai-code-tui/README.md
git commit -m "docs(code-tui): 重写发布包快速开始"
```

### Task 2: 精简根 README 的下载入口

**Files:**
- Modify: `README.md`
- Reference: `springai-code-tui/README.md`

**Interfaces:**
- Consumes: Task 1 确立的发布包配置和启动路径。
- Produces: 仓库首页的短快速开始，并将深入配置导向模块 README。

- [ ] **Step 1: 重写根 README 顶部的“下载即用”**

保留徽章和导航链接，将下载段落改成可独立完成的短流程：

```markdown
### 下载并运行（无需构建）

1. 下载对应平台的发布包并解压。
2. 复制 `bin/config.env.example` 为 `bin/config.env`，填写至少一家 API Key。
3. 进入待处理的、可丢弃且由 Git 管理的项目目录。
4. 通过安装目录中的平台启动脚本运行。
```

macOS/Linux 和 Windows 分开给命令。根 README 不列全部可选环境变量，将详细配置链接到 `springai-code-tui/README.md`。

- [ ] **Step 2: 消除根 README 内部的运行路径冲突**

检查后部“快速开始”和“运行”章节。源码模块运行说明应明确属于“从源码构建”，不能让发布包用户误以为需要执行 Maven 或直接 JAR。保留教学模块的源码运行命令，但将 code-tui 的发布包路径与源码路径清楚标注。

- [ ] **Step 3: 检查根 README 的关键信息**

Run:

```bash
git diff --check -- README.md
```

Expected: exit 0。

Run:

```bash
Grep "下载并运行|config.env.example|disposable|code-tui.cmd|JDK 17|不是安全沙箱|springai-code-tui/README.md" README.md
```

Expected: 下载、配置、工作目录、Windows、Java、安全和深入文档均有匹配。

- [ ] **Step 4: 提交根 README 重构**

```bash
git add README.md
git commit -m "docs: 理顺 code-tui 下载运行入口"
```

### Task 3: 统一启动脚本的 Java 基线文案

**Files:**
- Modify: `springai-code-tui/src/package/bin/code-tui:3,45`
- Modify: `springai-code-tui/src/package/bin/code-tui.cmd:3,29`

**Interfaces:**
- Consumes: 项目已声明的 JDK 17+ 基线。
- Produces: 与 README 一致的启动脚本注释和缺失 Java 错误提示；不改变脚本行为。

- [ ] **Step 1: 将四处 `JDK 21+` 精确改为 `JDK 17+`**

POSIX 脚本应包含：

```sh
# 需求：JDK 17+。
```

```sh
echo "错误：未找到 java。请安装 JDK 17+（或设置 JAVA_HOME）后重试。" >&2
```

Windows 脚本应包含：

```bat
rem 需求：JDK 17+。
```

```bat
echo 错误: 未找到 java。请安装 JDK 17+ 或设置 JAVA_HOME 后重试。 1>&2
```

- [ ] **Step 2: 验证只改变文案，不改变脚本逻辑**

Run:

```bash
git diff --check -- springai-code-tui/src/package/bin/code-tui springai-code-tui/src/package/bin/code-tui.cmd
git diff --word-diff -- springai-code-tui/src/package/bin/code-tui springai-code-tui/src/package/bin/code-tui.cmd
```

Expected: 只有四处 `21` → `17`。

Run:

```bash
Grep "JDK 21\+" springai-code-tui/src/package/bin
```

Expected: 无匹配。

- [ ] **Step 3: 提交 Java 文案修正**

```bash
git add springai-code-tui/src/package/bin/code-tui springai-code-tui/src/package/bin/code-tui.cmd
git commit -m "fix(dist): 统一启动脚本 Java 版本说明"
```

### Task 4: 构建并审计实际发布包

**Files:**
- Verify: `springai-code-tui/target/springai-code-tui-1.9.0-dist.tar.gz`
- Verify: `springai-code-tui/target/springai-code-tui-1.9.0-dist.zip`
- Verify extracted files under a temporary directory

**Interfaces:**
- Consumes: Tasks 1–3 的文档和脚本文案。
- Produces: 发布包布局、文档链接和示例命令与实际产物一致的验证证据。

- [ ] **Step 1: 构建发布包**

Run:

```bash
mvn -pl springai-code-tui clean package -Pdist
```

Expected: `BUILD SUCCESS`，并生成 `.tar.gz` 与 `.zip`。

- [ ] **Step 2: 检查两个归档的必要文件**

Run:

```bash
tar tzf springai-code-tui/target/springai-code-tui-1.9.0-dist.tar.gz
unzip -l springai-code-tui/target/springai-code-tui-1.9.0-dist.zip
```

Expected: 两者均包含顶层 `springai-code-tui-1.9.0/`，以及：

```text
README.md
bin/code-tui
bin/code-tui.cmd
bin/config.env.example
springai-code-tui.jar
lib/*.jar
docs/guide/security.md
docs/guide/permissions.md
docs/guide/background-agent.md
docs/guide/vision.md
docs/guide/mcp.md
docs/guide/skills.md
docs/guide/interjection.md
docs/guide/reference.md
```

- [ ] **Step 3: 解压到临时目录并检查包内 README 和链接目标**

Run:

```bash
TMP_DIR=$(mktemp -d)
tar xzf springai-code-tui/target/springai-code-tui-1.9.0-dist.tar.gz -C "$TMP_DIR"
test -f "$TMP_DIR/springai-code-tui-1.9.0/README.md"
test -f "$TMP_DIR/springai-code-tui-1.9.0/bin/config.env.example"
test -x "$TMP_DIR/springai-code-tui-1.9.0/bin/code-tui"
test -f "$TMP_DIR/springai-code-tui-1.9.0/docs/guide/reference.md"
```

Expected: 所有 `test` 返回 0。

- [ ] **Step 4: 做全局一致性检查**

Run:

```bash
Grep "JDK 21\+" README.md springai-code-tui
```

Expected: 当前 README、启动脚本和模块文件中无错误的 JDK 21+ 要求；若测试资源或历史说明有匹配，逐项判断是否属于本任务范围，不能盲改历史记录。

Run:

```bash
git diff --check
git status --short
```

Expected: `git diff --check` 返回 0；工作区不包含 `target/` 或临时解压文件等未跟踪产物。

- [ ] **Step 5: 审阅提交范围**

Run:

```bash
git log --oneline -4
git diff b945069..HEAD -- README.md springai-code-tui/README.md springai-code-tui/src/package/bin/code-tui springai-code-tui/src/package/bin/code-tui.cmd
```

Expected: 只包含两份 README 重构和四处 Java 文案修正，没有配置逻辑、打包布局或历史发布说明改动。
