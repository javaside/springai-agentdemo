# Skill（技能）能力接入 — 设计文档

日期：2026-07-03
模块：`springai-code-tui`（编码 Agent 主程序）
状态：**v1 已实现并通过测试**（113 项全绿，含 4 项 `SkillCatalogTest`）。技能来源为**两层**：用户级 + 项目级，**不预置内置技能**。
参考：
- 社区文档 <https://spring-ai-community.github.io/spring-ai-agent-utils/v0.10.0/tools/SkillsTool/>
- 社区源码 <https://github.com/spring-ai-community/spring-ai-agent-utils>
- 项目内已有示例：`springai-agent-demo` 的 `SkillToolDemo`

---

## 1. 背景与目标

### 1.1 什么是 Skill

Skill（技能）= **一个含 `SKILL.md` 的文件夹**。`SKILL.md` 由两部分组成：

```markdown
---
name: git-commit-message                # YAML frontmatter：技能名（唯一 id）
description: 帮助按 Conventional Commits 规范撰写提交信息。当用户需要写…时使用。
---

# 正文：一份可复用的「操作说明书 / 领域指令」
（这里写详细的规则、步骤、示例……）
```

它是一种 **「渐进式披露（progressive disclosure）」的提示词工程**：

- 平时模型上下文里**只有**每个技能的 `name + description`（极省 token）；
- 当模型判断某个技能对当前任务有用时，**调用名为 `Skill` 的工具、只传技能名**；
- 工具随即返回该 `SKILL.md` 的**完整正文**（含 `basePath` 基目录 + 指令），注入本轮对话；
- 模型读完详细指令，再据此产出结果。

它与普通 `@Tool` 工具的本质区别：普通工具执行 Java 代码返回**数据**（查天气、读文件）；
Skill 工具「执行」的是**按需注入一大段指令/提示词**。

### 1.2 现状

- `springai-code-tui` 已把 `spring-ai-agent-utils` 0.10.0 作为依赖引入，并已注册 6 个社区工具
  （FileSystem / Shell / Grep / Glob / TodoWrite / SmartWebFetch），装配集中在 `AgentTools.build`。
- 项目里**已有** `SkillToolDemo`（在 `springai-agent-demo`）演示了 `SkillsTool` 的最小用法，但那是**独立 demo**，
  code-tui 主程序**尚未**接入 Skill 能力。
- code-tui 的斜杠命令体系（`/model` `/compact` `/context` `/help`）已成型，`SubmitHandler` 是 UI 与 Agent 之间的接缝。

### 1.3 目标

1. 让 code-tui 的编码 Agent **具备 Skill 能力**：模型能发现并按需调用用户 / 项目的技能。
2. 定义清晰的**技能存放约定**（两层：用户级 / 项目级），并说明用户**如何编写与使用技能**。
3. 新增 **`/skills` 命令**：让用户随时查看当前可用的技能清单。
4. 让技能的「被发现 → 被调用」在 TUI 里**可见**（复用现有 `ToolEventCallback` 事件通道）。
5. **无技能时优雅降级**：没有任何技能目录/文件时，不注册 `Skill` 工具，也不报错。

### 1.4 非目标（v1 明确不做）

- **不做命令式「安装」技能**（如 `/skills add <path|git-url>`）。v1 的安装是**约定式**的：
  往 `.codetui/skills/<名字>/` 放一个 `SKILL.md` 文件夹即完成安装（与 Claude Code 一致，技能就是文件）。
  评审结论：命令式安装应与**热加载**、**安装前正文确认（信任边界）**、**同名冲突/版本处理**打包成独立一版，
  不与核心链路耦合——理由见 §9。
- 不做技能的热加载 / 文件监听（改了 / 新放 `SKILL.md` 需重启进程生效）。
- 不做技能的远程仓库拉取、市场、版本管理。
- 不做用户手动强制注入某技能（如 `/skill <name>`）——v1 只做「模型自主调用 + 用户查看清单」。
- 不解决「技能捆绑的附属文件跨越 FileSystemTools 边界」问题（见 §7 已知限制）。

---

## 2. 已核实的库 API（javap 验证，非仅凭文档）

来自 `spring-ai-agent-utils-0.10.0.jar`：

```java
package org.springaicommunity.agent.tools;

public class SkillsTool {
    public static Builder builder();

    public static class Builder {
        // 加载方式：目录（可多次/批量）或 classpath 资源
        Builder addSkillsDirectory(String dir);
        Builder addSkillsDirectories(List<String> dirs);
        Builder addSkillsResource(Resource resource);
        Builder addSkillsResources(List<Resource> resources);
        Builder toolDescriptionTemplate(String template);   // 可选：自定义工具描述模板
        ToolCallback build();                                 // 产出一个 ToolCallback，工具名固定为 "Skill"
    }

    // 单个技能（公开嵌套 record），用于读取元数据
    public record Skill(String basePath, Map<String,Object> frontMatter, String content) {
        String name();     // = frontMatter["name"]
        String toXml();    // 注入给模型的 XML 片段
    }
}

package org.springaicommunity.agent.utils;

public class Skills {   // 独立的加载器，可脱离 Builder 单独取元数据
    static List<SkillsTool.Skill> loadDirectory(String dir);
    static List<SkillsTool.Skill> loadDirectories(List<String> dirs);
    static List<SkillsTool.Skill> loadResource(Resource... resources);
    static List<SkillsTool.Skill> loadResources(List<Resource> resources);
    // 内部还支持扫描 classpath 上 jar 里的技能
}
```

**关键结论（决定了下面的装配方式）**：

- `SkillsTool.Builder.build()` 返回的就是一个标准 `ToolCallback`（工具名 `Skill`），
  可以像现有 6 个工具一样，用 `ToolEventCallback` 装饰后交给 `ChatClient.defaultTools(...)`。
- `Skills.loadDirectory(...)` 能**单独**把技能加载成 `List<Skill>`，从而拿到每个技能的
  `name()` 和 `frontMatter().get("description")`——这正是 `/skills` 命令列清单所需的元数据。

**实现期新增的核实（字节码级，很重要）**：

- `Builder.toSkillsMap` 是 `HashMap.put` 按 add 顺序回放——**同名后勝ち、不会崩**；
  但 `build()` 生成的工具描述 `<available_skills>` 由喂进 Builder 的**全部**技能拼成，
  故同名技能会在描述里**列两次**（仅浪费少量 token，执行仍取后勝ち，见 §7）。
- **`Skills.loadResource(Resource)` 把每个 Resource 当「目录」处理**：内部 `resource.getFile()`
  → `loadDirectory(该路径)` 遍历，拿不到 File（jar 内）才回退 jar 扫描。**它期望的是技能根目录，
  不是单个 `SKILL.md` 文件**。这直接决定了装配必须「按层（目录）加载」而非「按文件加载」。

---

## 3. 关键决策

| 决策点 | 结论 | 理由 |
| --- | --- | --- |
| 技能存放位置 | **两层叠加**：用户级(`~/.codetui/skills`) + 项目级(`<root>/.codetui/skills`) | 对齐 Claude Code 的 `.claude/skills` 心智；用户级跨项目复用，项目级随仓库版本化 |
| 是否预置内置技能 | **不预置**（无 classpath 内置层） | 技能应由使用者按需提供，不把某种规范强加给所有项目 |
| 同名技能优先级 | **项目级 > 用户级**（后加载覆盖同名） | 越「近」越具体，项目可覆盖个人默认 |
| 工具注册时机 | **仅当至少加载到 1 个技能时**才注册 `Skill` 工具 | 空 `<available_skills>` 只会污染上下文、误导模型 |
| 工具事件可见性 | 用 `ToolEventCallback` 装饰，与其余 6 工具一致 | 让「技能被调用」在 TUI 出现一行工具活动，用户可感知 |
| 用户入口 | 新增只读命令 **`/skills`**（列 name + description + 来源层） | 技能是模型自主调用的；用户需要的是「知道有哪些技能可用」 |
| 是否支持手动注入 | v1 **不做** `/skill <name>` | 保持最小；模型自主调用已覆盖主要场景，手动注入留待 v2 |
| 装配位置 | 全部落在 `AgentTools.build`，产物经 `AgentRuntime` 上抛 | 与现有工具/记忆装配同址，接缝一致 |
| 技能目录不存在 | 静默跳过该层，不打日志噪音 | 两层常只存在一层甚至都不存在，缺失是常态 |

---

## 4. 项目侧改造方案（如何增加 Skill 功能）

改动集中在 `springai-code-tui` 的 `agent` 与 `ui` 两个包，**不新增第三方依赖**（`spring-ai-agent-utils` 已在。

### 4.1 技能解析（新增 `SkillCatalog`，实际落地）

新增 `com.example.springai.codetui.agent.SkillCatalog`，一次性完成「解析两层 → 去重 → 构建 `Skill` 工具」。
因 §2 已核实 `loadResource` 把 Resource 当**目录**处理，故**按层加载**：两层都用 `Skills.loadDirectory(层目录)`。
另提供 `load(projectRoot, userDir)` 包级重载，把用户级目录做成参数，便于测试注入临时目录、隔离真实 `~`。

```java
public final class SkillCatalog {
    public static final String DIR_NAME = ".codetui/skills";          // 用户级(相对~) / 项目级(相对root) 共用

    public record Loaded(List<SkillInfo> skills, ToolCallback tool) {} // tool 无技能时为 null

    public static Loaded load(Path projectRoot) {                     // userDir = ~/.codetui/skills
        return load(projectRoot, userSkillsDir());
    }

    static Loaded load(Path projectRoot, Path userDir) {              // 包级：供测试注入 userDir
        Path projectDir = projectRoot.resolve(DIR_NAME);

        // 顺序即优先级：用户 → 项目（LinkedHashMap.put 后勝ち去重）
        LinkedHashMap<String, SkillInfo> byName = new LinkedHashMap<>();
        boolean user    = collect(loadDirectory(userDir),    "用户", byName);
        boolean project = collect(loadDirectory(projectDir), "项目", byName);

        List<SkillInfo> infos = List.copyOf(byName.values());
        if (infos.isEmpty()) return new Loaded(List.of(), null);      // 空 → 不注册工具

        SkillsTool.Builder b = SkillsTool.builder();                  // 只喂「确有技能」的层
        if (user)    b.addSkillsDirectory(userDir.toString());
        if (project) b.addSkillsDirectory(projectDir.toString());
        return new Loaded(infos, b.build());
    }
}
```

顺序 = 加载顺序 = 覆盖顺序（**用户先、项目后**，同名后者胜）。`SkillInfo(name, description, source)` 是 UI 友好 record，
不让库的 `SkillsTool.Skill` 越过接缝。

### 4.2 `AgentTools.build`：注册 `Skill` 工具

在 6 工具装配处插入技能加载；`Skill` 工具本身已是 `ToolCallback`（非 `@Tool` 对象），单独追加进列表后统一装饰：

```java
SkillCatalog.Loaded skills = SkillCatalog.load(root);

List<ToolCallback> all = new ArrayList<>(Arrays.asList(
        ToolCallbacks.from(fs, sh, grep, glob, todo, webFetch)));   // 6 个 @Tool → ToolCallback
if (skills.tool() != null) all.add(skills.tool());                  // 有技能才追加

ToolCallback[] decorated = new ToolCallback[all.size()];
for (int i = 0; i < all.size(); i++)
    decorated[i] = new ToolEventCallback(all.get(i), listener);     // 技能调用也显示为一行工具活动
…
return new AgentRuntime(client, sessionService, manualStrategy, tokenCountEstimator, skills.skills());
```

`AgentRuntime` record 增加一个字段把技能清单上抛，供 UI 用：

```java
public record AgentRuntime(ChatClient client,
                           SessionService sessionService,
                           CompactionStrategy manualStrategy,
                           TokenCountEstimator tokenCountEstimator,
                           List<SkillInfo> skills) {}   // ← 新增
```

### 4.3 系统提示补一句技能引导（`SYSTEM_TEMPLATE`）

在 `AgentTools.SYSTEM_TEMPLATE` 的「工作方式」里补一条，告诉模型技能的存在与用法：

```
- 当任务匹配某个「可用技能」的描述时（如写提交信息、特定领域规范），先调用 Skill 工具并传入技能名，
  读取其完整指令后再据此产出结果；没有匹配技能时正常作答，不要臆造技能名。
```

> 注：`SkillsTool` 生成的工具描述里已内嵌 `<available_skills>`（各技能 name+description），
> 模型主要据此选择；系统提示这句只是额外「点醒」，避免模型忽略工具。

### 4.4 `SubmitHandler` 接缝：暴露技能清单

`SubmitHandler` 增加默认方法（与 `models()` 同风格，桩实现可省略）：

```java
/** 当前可用技能清单（供 /skills 展示）。默认空。 */
default List<SkillInfo> skills() { return List.of(); }
```

`CodingAgent` 持有 `List<SkillInfo>`（构造函数注入，来自 `AgentRuntime.skills()`），实现 `skills()` 返回它。
`CodeTuiApplication.main` 把 `runtime.skills()` 传进 `CodingAgent`。

### 4.5 `CodeTuiView`：新增 `/skills` 命令

1. `COMMANDS` 清单加一项：`new SlashCommand("/skills", "查看可用技能")`——自动获得斜杠补全菜单与 `/help` 列举。
2. `submitInput()` 的命令分发里加一个分支（只读、任何时刻可用，仿 `/context`）：

```java
if (cmd.equals("/skills")) {
    inputState.clear();
    printSkills();
    return;
}
```

3. `printSkills()` 把清单打进 scrollback（灰色信息行，仿 `printHelp`）：

```java
private void printSkills() {
    List<SkillInfo> list = onSubmit.skills();
    if (list.isEmpty()) {
        state.pushInfo("当前没有可用技能。可在 .codetui/skills/<名字>/SKILL.md 添加。");
        return;
    }
    state.pushInfo("可用技能（模型会按需自动调用）：");
    for (SkillInfo s : list) {
        state.pushInfo("  • " + s.name() + "  [" + s.source() + "]");
        state.pushInfo("      " + s.description());
    }
}
```

### 4.6 改动清单一览

| 文件 | 改动 | 状态 |
| --- | --- | --- |
| `agent/SkillCatalog.java` | **新增**：两层加载（用户 + 项目）+ 去重 + 构建 `Skill` 工具 | ✅ 已实现 |
| `agent/SkillInfo.java` | **新增**：UI 友好的技能元数据 record | ✅ 已实现 |
| `agent/AgentTools.java` | 加载技能、条件注册 `Skill` 工具、系统提示补一句、`AgentRuntime` 加 `skills` 字段 | ✅ 已实现 |
| `agent/SubmitHandler.java` | 加默认方法 `skills()` | ✅ 已实现 |
| `agent/CodingAgent.java` | 持有并返回 `skills()`（新增 8 参构造，7 参构造委托空清单，不破坏既有测试） | ✅ 已实现 |
| `CodeTuiApplication.java` | 把 `runtime.skills()` 传给 `CodingAgent` | ✅ 已实现 |
| `ui/CodeTuiView.java` | `COMMANDS` 加 `/skills`、分发分支、`printSkills()` | ✅ 已实现 |
| `src/test/.../SkillCatalogTest.java` | **新增**：无技能 / 用户新增 / 项目新增 / 同名覆盖去重 4 项 | ✅ 已实现 |
| `pom.xml` | 无需改动（依赖已在） | — |

---

## 5. 用户侧：如何使用 Skill

### 5.1 技能存放约定（两层）

| 层级 | 位置 | 用途 | 是否随仓库 |
| --- | --- | --- | --- |
| **项目级** | `<项目根>/.codetui/skills/<技能名>/SKILL.md` | 只属于本仓库的领域规范；随代码提交、团队共享 | ✅ 版本化 |
| **用户级** | `~/.codetui/skills/<技能名>/SKILL.md` | 个人跨项目通用技能 | ❌ 个人 home |

同名时**项目级覆盖用户级**（越具体越优先）。不预置任何内置技能——技能一律由使用者放文件提供。

### 5.2 编写一个技能

在项目根下建 `.codetui/skills/api-review/SKILL.md`：

```markdown
---
name: api-review
description: 审查 REST API 设计是否符合团队规范。当用户要评审接口、检查 API 命名/状态码/分页时使用。
---

# REST API 评审清单

对给定的接口定义，逐条检查并给出结论：

1. 路径用小写复数名词，层级不超过 2 层（`/orders/{id}/items`）。
2. 用 HTTP 语义：GET 只读、POST 建、PUT 全量改、PATCH 部分改、DELETE 删。
3. 状态码：201 建成、204 无体、400 入参错、404 不存在、409 冲突。
4. 列表接口必须支持分页参数 `page` / `size`，并返回 `total`。
5. 错误响应统一 `{ code, message, traceId }`。

输出格式：先给「✅ 通过 / ⚠️ 待改」总评，再逐条列出问题与修改建议。
```

要点：
- **文件名必须是 `SKILL.md`**，放在以技能名命名的独立文件夹里。
- frontmatter 的 **`name` 唯一**、**`description` 要写清「什么时候用」**——模型正是靠 description 决定是否调用。
- 正文就是详细指令，越具体越好；可含步骤、清单、示例、输出格式约束。

### 5.3 运行与查看

```bash
# 1. 放好技能文件（示例：项目级）
mkdir -p .codetui/skills/api-review && $EDITOR .codetui/skills/api-review/SKILL.md

# 2. 启动 code-tui（技能在启动时加载，改动需重启）
java -jar springai-code-tui/target/springai-code-tui.jar

# 3. 在 TUI 里查看当前可用技能
/skills
```

`/skills` 输出示例：

```
可用技能（模型会按需自动调用）：
  • git-commit-message  [用户]
      帮助按 Conventional Commits 规范撰写提交信息。当用户需要写…时使用。
  • api-review  [项目]
      审查 REST API 设计是否符合团队规范。当用户要评审接口…时使用。
```

### 5.4 使用（对用户是「透明」的）

用户**不需要**显式调用技能——只要正常描述任务，模型匹配到某个技能的 description 就会自动调用。
例如直接说：

> 帮我给这次改动写一条规范的提交信息：登录接口加了图形验证码。

模型会（在 TUI 里可见）先调用 `Skill("git-commit-message")` 读取规范，再据此产出提交信息。
TUI 会显示一行工具活动（形如 `📚 Skill · git-commit-message`），让这一步可感知。

---

## 6. 执行流程（端到端）

```
启动
  └─ AgentTools.build
       ├─ SkillCatalog.load(root)  → 按层加载(用户·项目 各走 loadDirectory)
       │                             → 按 name 去重(项目>用户) → List<SkillInfo>
       ├─ 有技能？→ SkillsTool.builder()…build() 得到名为 "Skill" 的 ToolCallback
       │             └─ ToolEventCallback 装饰 → 追加进 ChatClient.defaultTools
       └─ AgentRuntime.skills = List<SkillInfo> 上抛给 UI

一次对话回合（模型自主）
  第 1 轮：模型只看到各技能 name+description（<available_skills>），决定调用 Skill("api-review")
  工具执行：返回该 SKILL.md 完整正文（basePath + 指令），并触发一次 UI 工具事件
  第 2 轮：模型读完指令，按其规则产出最终结果

用户查看
  /skills → CodeTuiView.printSkills() → 读 SubmitHandler.skills() → 打进 scrollback
```

---

## 7. 已知限制与权衡

1. **技能捆绑的附属文件受 FileSystemTools 边界限制**。
   `SKILL.md` 可在正文引用同目录下的脚本/模板（模型会用 `read` 去读 `basePath` 下的文件）。
   但 `FileSystemTools` 的 `allowedDirectory` 只限定在**项目根**内——
   放在 `~/.codetui/skills` 里的用户级技能，其附属文件**在边界外，模型读不到**。
   v1 约定：**用户级技能应是「纯指令型」（只靠 SKILL.md 正文）**；需要读附属文件的技能请放**项目级**。
   （彻底方案需给 FileSystemTools 额外放行技能目录，留待 v2。）

2. **无热加载**。技能在启动时一次性加载，改 `SKILL.md` 需重启进程。v1 可接受（编码会话生命周期本就不长）。

3. **技能过多会撑大工具描述**。所有技能的 name+description 常驻在 `Skill` 工具描述里；
   几十个技能仍可控，上百个需考虑分组/懒披露——超出 v1 范围。

4. **`description` 质量决定命中率**。description 写得含糊，模型就不会调用。这是提示词工程的固有特性，靠文档引导用户写好。

5. **同名跨层覆盖时，工具描述会重复列一次**（已核实字节码）。`SkillsTool` 的 `<available_skills>` 由喂进
   Builder 的全部技能拼成，而我们把「用户层 + 项目层」都喂了进去——若项目用同名技能覆盖用户级，两条都会出现在描述里。
   **执行时仍取后勝ち的高优先级层（项目），行为正确**；`/skills` 清单也已自行去重、只显示一条。仅描述多耗少量 token，
   属可接受的次要瑕疵。（彻底修复需库支持「传入已去重的技能对象」，非本类可控。）

---

## 8. 测试计划

- `SkillCatalogTest`（**已实现，4 项全绿**，用 `load(root, userDir)` 注入临时目录隔离真实 `~`）：
  两层皆空 → 清单空且工具为 `null`；用户级技能被加入且标「用户」+ 工具名 `Skill`；项目级技能被加入且标「项目」；
  同名时项目级覆盖用户级（去重后只剩一条、来源变「项目」）。
- 未来可补 `AgentToolsSkillsTest`：
  - 有技能目录 → 注册出名为 `Skill` 的工具，且 `AgentRuntime.skills()` 非空、来源标签正确；
  - 无任何技能 → **不**注册 `Skill` 工具，`skills()` 为空（对照现有 6 工具数量不变）。
  - 装配期不发网络请求、不需有效 API key（沿用 `AgentToolsSecurityTest` 的既有约束）。
- `CodeTuiViewSkillsTest`（若 UI 可测）：`/skills` 空/非空两种输出；`/skills` 出现在补全菜单与 `/help`。
- 复用 `SkillToolDemo` 的手法：可选加一个装饰器断言「技能被调用时传入的技能名 ∈ 已加载集合」。

---

## 9. 未来工作（v2+）

### 9.1 技能管理 / 命令式安装（一个打包的独立版本）

评审已确认：**命令式安装不进 v1，单独作为「技能管理」一版做**。它不是一个孤立命令，而是三件事的组合，
必须一起做才成立：

1. **安装源** —— `/skills add <本地路径 | git-url>`：把技能拷进 `.codetui/skills/`。
   分阶段：先支持**本地路径**（低风险、无网络），再支持 **git-url / 远程**。
2. **热加载** —— v1 是启动时一次性加载，装完必须重启才生效，体验别扭。故命令式安装**必须**配套
   「装完即重新加载技能、刷新 `Skill` 工具描述与 `/skills` 清单」，否则 `add` 名不副实。
3. **信任边界（关键）** —— 技能 = 注入进 Agent 的指令，装别人的技能 = 引入**提示词注入风险面**。
   远程安装前需**展示技能正文让用户确认**再落盘；本地路径安装风险较低可从简。

配套：同名冲突/覆盖策略、`/skills remove <name>`、版本/更新。

> 为什么单独做（§1.4 已记）：核心价值（模型自主调用技能）与安装正交；把安装、热加载、信任三者
> 与核心链路解耦，能让 v1 先稳定上线，v2 再专注把「管理」这块做扎实。

### 9.2 其它

- `/skill <name>`：用户手动强制注入某技能正文（不依赖模型判断）。
- FileSystemTools 为技能目录额外放行，支持用户级技能读取附属文件。
- 技能来源标签在 TUI 里带颜色区分；`/skills <name>` 查看单个技能正文预览。
