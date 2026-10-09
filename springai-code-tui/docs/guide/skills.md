# 技能配置（Skills）

> 本页内容对应 README 的「技能配置」章节。


技能（Skill）是一份 Markdown 指令文件（`SKILL.md`），描述「遇到某类任务时该怎么做」。模型会按需自动调用（`/skills` 查看清单、`/skill` 手动指定），把该文件正文注入到当前这条消息，从而在特定场景下给模型专门的方法论/操作规程。

### 从哪里读取（六层目录）

启动时扫描**六个文件系统目录**，每个技能是**一个子目录、里面放一个 `SKILL.md`**。

表中 `.claude/skills` 与 `.agents/skills` 的四行是**兼容层**——开源技能项目的通用安装命令
（如 `npx skills add <owner>/<repo>`，skills.sh 生态）按检测到的 agent 定向落目录，不会写进
`.codetui`：Claude Code 生态落 `.claude/skills`，agents.md 通用标准（Cursor / Codex / Copilot /
Gemini CLI / OpenCode 等的项目级）落 `.agents/skills`。code-tui 直接读这两个约定，
**别家的安装命令装完即可用**，不必手动拷贝：

| 层 | 路径 | 来源标签 |
|---|---|---|
| 用户·兼容 | `~/.claude/skills/<技能名>/SKILL.md` | `用户·claude` |
| 用户·兼容 | `~/.agents/skills/<技能名>/SKILL.md` | `用户·agents` |
| 用户·自有 | `~/.codetui/skills/<技能名>/SKILL.md` | `用户` |
| 项目·兼容 | `<项目根>/.claude/skills/<技能名>/SKILL.md` | `项目·claude` |
| 项目·兼容 | `<项目根>/.agents/skills/<技能名>/SKILL.md` | `项目·agents` |
| 项目·自有 | `<项目根>/.codetui/skills/<技能名>/SKILL.md` | `项目` |

- **优先级 = 表格从上到下，同名后者覆盖前者**。两条原则：**项目 > 用户**（仓库可覆盖个人版），
  **自有 > 兼容**（同 scope 内 `.codetui` 覆盖 `.claude`/`.agents`——code-tui 自己的目录有最终话语权）。
- **同名不会重复**：安装器把同一技能写进多个目录（镜像仓库常把 `.claude/skills` 与 `.agents/skills`
  维护成字节级镜像）时，按 frontmatter `name` 去重只保留一条。**防重复靠去重，不靠少读目录**——
  而只认一个目录会在「只装了 Claude 生态」或「只装 Codex/Cursor 生态」的机器上漏技能。
- **技能名 = frontmatter `name`**；目录不存在的层**静默跳过**；某层解析报错只跳过该层，不影响其他层、也不崩启动。
- **无 classpath 内置层**——只有上面六个磁盘目录（没有随 jar 打包的内置技能）。
- 记忆/会话/MCP/模型偏好用的仍是 `.codetui/` 一套目录约定（`~/.codetui/` 与 `<项目根>/.codetui/`；
  其中会话、记忆与模型偏好只有项目级）——技能是唯一读外部目录约定的例外，理由见上。

### SKILL.md 格式

YAML frontmatter（至少 `name` 与 `description`）+ 正文：

```markdown
---
name: systematic-debugging
description: Use when encountering any bug, test failure, or unexpected behavior, before proposing fixes
---

# Systematic Debugging

...方法论/操作步骤正文（会被注入给模型）...
```

- `description` 决定模型「何时该调用」——写清触发场景，别只写标题。
- 正文即注入内容；过长会占用上下文，按需精简。

### 目录布局示例

```
~/.codetui/skills/                     # 自有用户层
├── systematic-debugging/
│   └── SKILL.md
~/.claude/skills/                      # 兼容用户层（npx skills add 常见落点）
└── design-taste-frontend/
    └── SKILL.md

<项目根>/.codetui/skills/              # 自有项目层（最高优先级）
└── writing-plans/
    └── SKILL.md                       # 项目专属，随仓库提交
<项目根>/.agents/skills/               # 兼容项目层（镜像仓库常与 .claude/skills 双写）
└── seam-craft/
    └── SKILL.md
```

### 生效与热加载

- 运行中新增/删除/修改 `SKILL.md` 后，在 code-tui 里执行 **`/reload`** 重扫六层目录即生效——**无需重启**；即便启动时零技能，也能 `/reload` 出第一个新增技能。
- `/skills` 查看当前可用清单（含来源层标注），`/skill` 为本条消息手动指定一个技能。

### 装第三方技能

第三方技能仓库通常自带安装命令（`npx skills add <owner>/<repo>`、各家 agent 的 plugin/skill 安装等），
多数最终落到 `.claude/skills` 或 `.agents/skills`——code-tui **直接就能读到**，装完 `/reload` 即可见。

仓库若只是源码里带了 `skills/<名>/SKILL.md` 布局（如 [chrome-devtools-mcp](https://github.com/ChromeDevTools/chrome-devtools-mcp/tree/main/skills)），
手动拷到任一层即可：

```bash
# 全局对所有项目生效（拷进兼容层，Claude Code 等别的 agent 也能用）
mkdir -p ~/.claude/skills
cp -r /path/to/chrome-devtools-mcp/skills/chrome-devtools ~/.claude/skills/
# 或只对当前项目生效
cp -r /path/to/chrome-devtools-mcp/skills/chrome-devtools <项目根>/.agents/skills/
```

放好后 `/reload`，`/skills` 即可见。

> 注意：技能是**软引导**（提示模型「该怎么做」），并非硬约束——例如它可引导模型优先用 `take_snapshot`（文本）而非 `take_screenshot`，但**挡不住**模型把大文件/图片读进上下文。真正防止上下文被撑爆需在代码层加防线（工具输出限幅、拒读二进制），技能只降低触发概率。

