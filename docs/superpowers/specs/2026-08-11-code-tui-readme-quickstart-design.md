# code-tui README 快速开始重构 · 设计

**日期**：2026-08-11
**模块**：仓库根文档、`springai-code-tui`
**状态**：已确认，待实施

## 问题

发布包虽然包含 `README.md`、启动脚本和 `bin/config.env.example`，但用户解压后仍不容易判断如何运行。核心问题不是缺少单条命令，而是文档把发布包用户、源码开发者和项目维护者的路径混在一起：

- 包内 `springai-code-tui/README.md` 先展示密集的功能列表和安全说明，运行步骤埋在“构建”之后。
- “运行”章节以环境变量和 `java -jar` 为主，没有把发布包自带的启动脚本与 `config.env` 作为主路径。
- 根 README 的下载示例先进入安装目录再执行程序，没有明确区分“安装目录”和“智能体工作目录”，容易让用户误以为应在解压目录中使用 code-tui。
- 根 README 和模块 README 职责重叠，既重复又缺少清晰的阅读层级。
- 正式文档以 JDK 17+ 为基线，但 macOS/Linux 和 Windows 启动脚本的注释、错误提示写成 JDK 21+；脚本实际只检查 Java 是否存在，不检查版本。

## 目标

让首次下载发布包的用户只看 README 第一屏附近，就能完成：

1. 确认前置条件；
2. 解压发布包；
3. 复制并填写 `bin/config.env`；
4. 理解安装目录和工作目录的区别；
5. 从待处理的 Git 项目目录启动 code-tui；
6. 知道 Windows 与 macOS/Linux 分别使用哪个启动脚本。

同时保留源码构建、功能介绍、安全声明和专题文档入口，但调整信息顺序，不扩展新的安装机制或启动功能。

## 范围

### 做

- 重组根 `README.md` 顶部的 code-tui 下载与运行说明。
- 重组 `springai-code-tui/README.md`，将发布包快速开始提到功能介绍和源码构建之前。
- 以复制 `bin/config.env.example` 为推荐配置路径，将临时环境变量作为补充方式。
- 分别给出 macOS/Linux 和 Windows 的运行示例。
- 明确安装目录与工作目录的区别，并要求从可丢弃、由 Git 干净纳管的目标项目目录启动。
- 压缩模块 README 的功能列表，详细内容继续链接至 `docs/guide/`。
- 将两个启动脚本中的 JDK 21+ 文案统一改为 JDK 17+。
- 构建并检查发布包中的 README、配置模板、启动脚本和专题文档。

### 不做

- 不新增 `QUICKSTART.md`，避免出现第三份重复入口文档。
- 不修改发布包目录结构、脚本名称或配置加载逻辑。
- 不新增 Java 版本检测；启动脚本仍只检查 Java 命令是否存在。
- 不重写 `docs/guide/` 专题内容。
- 不修改历史发布说明中的下载校验值或功能描述。

## 文档职责

### 根 `README.md`

面向第一次进入仓库或 Release 页的读者，负责：

1. 用一句话说明 code-tui 是什么；
2. 提供“下载并运行”的短路径；
3. 给出简明安全警告；
4. 展示核心能力概览；
5. 介绍仓库内其他教学模块；
6. 将详细配置与功能说明链接到模块 README。

根 README 不展开全部 provider 环境变量、日志策略和专题功能细节。

### `springai-code-tui/README.md`

既是模块文档，也是发布包根目录中的主说明书，负责：

1. 发布包快速开始；
2. 安全边界；
3. 核心能力与常用操作；
4. 配置、会话和日志位置；
5. 从源码构建；
6. 制作发布包；
7. 专题指南索引。

## 模块 README 信息架构

按以下顺序重组：

1. 标题与一句话介绍
2. `快速开始（下载发布包）`
   - 前置条件：JDK 17+、至少一家模型服务的 API Key
   - macOS/Linux：解压、复制配置、编辑配置、从目标项目启动
   - Windows：解压、复制配置、编辑配置、从目标项目启动
   - `-c` / `--continue` 恢复最近会话
   - 临时环境变量配置作为补充说明
3. `安全声明`
4. `核心能力`
5. `常用操作`
6. `配置与数据位置`
   - `bin/config.env`
   - `<项目根>/.codetui/`
   - 日志目录
7. `从源码构建与运行`
8. `制作发布包`
9. `使用指南索引`

功能介绍从十几条平铺列表压缩为六类：

- 模型与 provider
- 文件、Shell 与联网工具
- 权限与安全
- 子 agent、后台任务与计划
- 会话、上下文与长期记忆
- MCP、Skills 与视觉输入

每类只保留用户价值与关键入口，机制细节交给 `docs/guide/`。

## 快速开始的命令语义

示例必须显式区分两个路径：

- `<安装目录>`：解压出来的 `springai-code-tui-<version>`，包含 `bin/`、主 JAR 和 `lib/`。
- `<工作目录>`：code-tui 将要读取和修改的 Git 项目。

macOS/Linux 的最终启动形态：

```bash
cd /path/to/disposable-git-project
/path/to/springai-code-tui-<version>/bin/code-tui
```

Windows 的最终启动形态：

```bat
cd C:\path\to\disposable-git-project
C:\path\to\springai-code-tui-<version>\bin\code-tui.cmd
```

不把 `java -jar` 作为发布包主路径。它只保留在源码运行或高级用法说明中，因为启动脚本还负责加载配置文件和确定日志目录。

## 配置路径

推荐路径：

```bash
cp bin/config.env.example bin/config.env
```

Windows：

```bat
copy bin\config.env.example bin\config.env
```

随后提示用户编辑 `bin/config.env`，取消任意一家 provider 的 API Key 注释并填写值。README 不复制完整模板，避免配置项在 README 与 `config.env.example` 两处漂移。

临时环境变量仍可使用，但放在推荐流程之后，并明确它适合临时试用。

## Java 版本文案

项目 POM 和正式文档的运行基线为 Java 17，因此将以下启动脚本文案从 JDK 21+ 改为 JDK 17+：

- `springai-code-tui/src/package/bin/code-tui` 的顶部需求注释和“未找到 Java”错误；
- `springai-code-tui/src/package/bin/code-tui.cmd` 的顶部需求注释和“未找到 Java”错误。

本次不增加版本解析与拒绝旧 Java 的逻辑。文案表达的是受支持的前置条件，不声称脚本会验证版本。

## 安全说明

安全警告不能因快速开始重构而弱化，但分成两层：

- 快速开始旁保留一段醒目的短警告：不是安全沙箱，只在可丢弃、由 Git 干净纳管的目录运行；
- 后续“安全声明”保留权限层边界、用户权限和目录无边界等完整说明，并链接 `docs/guide/security.md`。

示例工作目录使用 `disposable-git-project` 一类名字，避免用模糊的 `some-project` 淡化风险。

## 验证

1. 构建发布包：

```bash
mvn -pl springai-code-tui clean package -Pdist
```

2. 检查 tar.gz 和 zip 的文件清单，确认包含：

- `README.md`
- `bin/code-tui`
- `bin/code-tui.cmd`
- `bin/config.env.example`
- `springai-code-tui.jar`
- `lib/`
- `docs/guide/*.md`

3. 解压归档，核对 README 中的相对链接在包内有对应文件。
4. 搜索当前文档和启动脚本中的 `JDK 21+`，确认本任务涉及的错误文案已移除。
5. 搜索 JDK 17+ 表述，确认根 README、模块 README 与启动脚本一致。
6. 核对 README 示例中的文件名、参数和路径与实际发布包一致。
7. 查看 Git diff，确认没有无关功能或历史发布说明改动。
