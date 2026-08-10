# 技能配置（Skills）

> 本页内容对应 README 的「技能配置」章节。


技能（Skill）是一份 Markdown 指令文件（`SKILL.md`），描述「遇到某类任务时该怎么做」。模型会按需自动调用（`/skills` 查看清单、`/skill` 手动指定），把该文件正文注入到当前这条消息，从而在特定场景下给模型专门的方法论/操作规程。

### 从哪里读取（两层目录）

启动时扫描**两个文件系统目录**，每个技能是**一个子目录、里面放一个 `SKILL.md`**：

| 层 | 路径 | 说明 |
|---|---|---|
| 用户级 | `~/.codetui/skills/<技能名>/SKILL.md` | 跨项目复用（`user.home` 下） |
| 项目级 | `<项目根>/.codetui/skills/<技能名>/SKILL.md` | 随仓库版本化，**同名覆盖用户级** |

- **技能名 = 子目录名**；合并顺序为**用户 → 项目**，同名以**项目级**为准（项目可覆盖你的个人版）。
- 目录不存在的层**静默跳过**；某层解析报错只跳过该层，不影响另一层、也不崩启动。
- **无 classpath 内置层**——只有上面这两个磁盘目录（没有随 jar 打包的内置技能）。
- 记忆/会话/MCP/模型偏好用的也是同一个 `.codetui/` 目录约定（`~/.codetui/` 与 `<项目根>/.codetui/`；其中会话、记忆与模型偏好只有项目级）。

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
~/.codetui/skills/
├── systematic-debugging/
│   └── SKILL.md
└── chrome-devtools/
    └── SKILL.md            # 例：装官方 chrome-devtools-mcp 的 skill

<项目根>/.codetui/skills/
└── writing-plans/
    └── SKILL.md            # 项目专属，随仓库提交
```

### 生效与热加载

- 运行中新增/删除/修改 `SKILL.md` 后，在 code-tui 里执行 **`/reload`** 重扫两层目录即生效——**无需重启**；即便启动时零技能，也能 `/reload` 出第一个新增技能。
- `/skills` 查看当前可用清单（含来源层标注），`/skill` 为本条消息手动指定一个技能。

### 装第三方技能（如 chrome-devtools-mcp 官方 skill）

第三方技能仓库若采用 `skills/<名>/SKILL.md` 布局（如 [chrome-devtools-mcp](https://github.com/ChromeDevTools/chrome-devtools-mcp/tree/main/skills)），直接把对应子目录拷到上面两层之一即可：

```bash
# 全局对所有项目生效
mkdir -p ~/.codetui/skills
cp -r /path/to/chrome-devtools-mcp/skills/chrome-devtools ~/.codetui/skills/
# 或只对当前项目生效
cp -r /path/to/chrome-devtools-mcp/skills/chrome-devtools <项目根>/.codetui/skills/
```

放好后 `/reload`，`/skills` 即可见。

> 注意：技能是**软引导**（提示模型「该怎么做」），并非硬约束——例如它可引导模型优先用 `take_snapshot`（文本）而非 `take_screenshot`，但**挡不住**模型把大文件/图片读进上下文。真正防止上下文被撑爆需在代码层加防线（工具输出限幅、拒读二进制），技能只降低触发概率。

