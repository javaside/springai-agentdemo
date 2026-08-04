# 记住上次使用的大模型 · 设计

**日期**：2026-08-05
**模块**：`springai-code-tui`
**状态**：已确认，待实施

## 问题

用户每次启动 code-tui 都要重新走一遍 `/model` 选自己想要的模型。

现状：`ProviderRegistry` 构造时激活「偏好序里第一个 `available()` 的 provider」，激活模型 = 该家的 `defaultModel()`。偏好序由 `CodeTuiApplication` 的构造入参写死（deepseek 打头）。`/model` 选中经 `ProviderRegistry.select(modelId)` 只改内存，进程一退就没了。

## 需求边界

**做**：进程退出后重新启动（**不带** `-c`），自动回到上次 `/model` 选中的模型。

**不做**：
- 显式配置项（`defaultModel` 之类手写配置）——本轮只做「自动记住」。
- 「锁定」语义（配置写死后不被自动记忆覆盖）——YAGNI。
- 跨项目共享——本轮记忆按项目隔离，见下。

## 已确认的四个决定

| 决定 | 选择 | 理由 |
|---|---|---|
| 存储层级 | **项目级** `<root>/.codetui/` | 用户选择。允许「A 项目用便宜模型跑杂活、B 项目用强模型」。代价：换个新目录仍要重选一次。 |
| 记忆口径 | **自动记住上次选的** | `/model` 按 Enter 即写盘。零额外操作。代价：临时切一下别的模型试试，也会被记下。 |
| 失效兜底 | **回退默认 + 打一行提示** | 沉默回退会让人以为记忆功能坏了。 |
| 文件形态 | **专用文件** `<root>/.codetui/model.json` | 用户选择（对比：通用 `settings.json`）。职责更窄、名字更直白。 |

`.codetui/` 已被仓库 `.gitignore` 挡住，不会误提交，clone 下来也不会带上别人的模型选择。

## 架构

### 新增组件：`ModelPreference`

```java
public final class ModelPreference {
    public static Path fileFor(Path root);             // <root>/.codetui/model.json
    public static Optional<String> read(Path root);    // 缺失/坏文件/空值 → empty，绝不抛
    public static boolean write(Path root, String id); // 失败 → false，绝不抛
}
```

盘上格式，只有一个键：

```json
{ "lastModel": "deepseek-v4-flash" }
```

**键名叫 `lastModel` 而不是 `model`**：在名字上说清「这是**上次用的**，不是你配置的默认」。将来真要加显式配置项，两者可以共存而不打架。

**只记 `modelId`，不记 provider**：`ProviderRegistry.select(String)` 的既有语义就是「在可用 provider 里找拥有该 id 的那家」。`*_MODELS` 环境变量配置可能造成跨家重名，此时命中列表序靠前的可用家——但 `/model` 面板本身也只能按 id 选，**UI 层面同样区分不了重名**。只记 id 与现有交互完全一致，不引入新的不一致。这是已知限制，不是疏忽。

### 写盘点：`CodeTuiView`，不是 `CodingAgent`

语义上「agent 状态变了就持久化」更像 `CodingAgent.selectModel` 的活，但两件事让它落在了视图层：

1. `CodingAgent` **没有 `root` 字段**，且它的构造函数是一条 **11 个重载的伸缩链**（`CodingAgent.java:89` 到 `:233`）。加一个参数要么改一整条链，要么破坏它全 `final` 字段的写法塞个 setter。
2. `CodeTuiView` **已经持有 `root`**（`CodeTuiView.java:143`，`.codetui/artifacts/` 就是它解析的），而且选中后那行 `⚙ 已切换模型` 确认信息也在它手里（`:1306`）——「写失败要不要多打一行」的判断天然长在同一处。

**代价（已知约束，必须写进代码注释）**：持久化只挂在选择器的 Enter 路径上。核对过 `selectModel` 在生产代码里**只有 `CodeTuiView:1302` 一个调用方**，所以当前不漏。**将来若新增 `/model <id>` 这类直接命令或任何其它切换入口，必须一并接上持久化**，否则会出现「切了但没记住」。

### 恢复点：构造完 `ProviderRegistry` 之后 `select`

不走「把初始 modelId 做成 `ProviderRegistry` 构造入参」这条路：`ProviderRegistry` 不该认识磁盘，且改构造签名要牵动全部调用点与测试。

构造后 `select` 还白送一个失效检测——`select()` 对未知模型是**静默忽略**的，所以 `select` 之后比一下 `activeModelId()` 是否等于要恢复的 id，不等就是「用不了了」。不需要新增任何 API。

代价：构造完到 `select` 之间有一瞬间 active 是 deepseek 默认。这一瞬间不发任何请求，无副作用。

### 子 agent 自动受益

`SubagentRunner` 用的是 `registry.active().chatModel()`（`:197`）和 `registry.activeChatOptions()`（`:530`）——**子 agent 跟着主 agent 的激活模型走**。启动时把模型恢复好，子 agent 自动受益，不用额外接线。

## 数据流

### 写（选中时）

```
用户在 /model 面板按 Enter
  → onSubmit.selectModel(id)                    （已有，切内存）
  → 若 onSubmit.currentModel() 真的变成了 id
       → ModelPreference.write(root, id)
       → 失败则额外 pushInfo「⚠ 没能记住这个选择（仅本次运行生效）」
  → pushInfo「⚙ 已切换模型 · <label>」          （已有）
```

**只在真正生效后才写**是关键一步：`ProviderRegistry.select()` 对未知模型静默忽略，不加这道判断就会把一个选不中的 id 落到盘上，下次启动再触发一次「用不了，已回退」——自己给自己制造失效记录。

### 读（启动时）

```
new ProviderRegistry(...)          （已有，激活第一个可用家的默认模型）
  → new ConversationState()        （已有，CodeTuiApplication.java:57）
  → ModelPreference.read(root)
       empty → 什么都不做（首次运行，走现在的行为）
       有 id → registry.select(id)
                若 registry.activeModelId() != id
                   → pushInfo「• 上次用的模型 <id> 现在不可用，已回退到 <当前>。」
```

位置必须在 `ConversationState` 创建**之后**：提示要走 `state.pushInfo` 落进开场 scrollback，和现有的权限模式提示同一条路。

## 错误处理与降级

### 读侧

降级契约照 `PermissionConfigLoader`：**任何情况都返回 `Optional`，绝不抛**。启动路径上抛异常等于整个工具起不来，为一个偏好把 code-tui 搞挂完全不值。

| 情况 | 结果 |
|---|---|
| 文件不存在 | `empty`，**不打日志**——首次运行是常态，不是错误 |
| JSON 非法 | WARN + `empty` |
| 缺 `lastModel` 键 | `empty` |
| `lastModel` 值不是字符串（数字/null/对象） | `empty` |
| `lastModel` 是空串或全空白 | `empty` |
| 读 IO 异常 | WARN + `empty` |

### 写侧

原子写，照 `PermissionConfigWriter` / `FileSessionRepository` 的既有范式：

1. `.codetui/` 不存在 → `Files.createDirectories`
2. 先写同目录临时文件（随机后缀，防跨进程互相写坏）
3. `ATOMIC_MOVE` 移到目标；`AtomicMoveNotSupportedException` 时降级 `REPLACE_EXISTING` 普通替换

任何异常 → WARN + 返回 `false` + **不改动原文件**。

### 刻意偏离 permissions.json 的范式

`permissions.json` 写侧走 Jackson **树模型读-改-写**，因为文件里有用户手写的规则和未知字段必须原样保留。`model.json` 是**纯机器写的单键文件**，没有用户内容要保护，**整份覆盖**更简单也更不容易写坏。

**必须在类注释里埋一条绊线**：这个文件哪天长出第二个键，写侧就必须改回读-改-写，否则整份覆盖会悄悄吃掉另一个键。

### 并发

- **进程内**：`selectModel` 只在 UI 线程发生，不用加锁。（`PermissionConfigWriter` 那把静态锁是因为工具并行审批会同时回写，这里没有对应场景。）
- **跨进程**（同一项目开两个窗口）：last-writer-wins。单键文件，最坏结果是「记住了另一个窗口选的模型」；临时文件带随机后缀，不会互相写坏。照既有取舍，可接受。

### 与权限模式的不对称（文档必须交代）

README 明确写了 `-c` **不**恢复权限模式，而模型现在要跨重启恢复。理由是两者性质不同：

- 权限模式记错了，会让**不该执行的东西执行**——安全后果。
- 模型记错了，最坏是多花点钱或慢一点，且状态栏一直显示着当前模型名，用户一眼看得见、随时改得回来。

不写清楚就显得前后矛盾。

### 与 `-c` 正交

模型记忆独立于会话恢复。不带 `-c` 的默认启动照样生效——**这正是本需求的核心路径**。`-c` 恢复会话时也不从会话文件里读模型（会话文件里根本没存）。

## 测试策略

### 1. `ModelPreferenceTest`（纯单测，临时目录当 root）

- 写后读回相等
- 文件不存在 → `empty`
- JSON 非法（如 `{`）→ `empty` 且不抛
- 缺 `lastModel` 键 → `empty`
- `lastModel` 值是数字 / `null` / 空串 / 全空白 → `empty`
- `.codetui/` 不存在时 `write` 会建目录
- 写到不可写目录 → `false` 且不抛。**这条要 `assumeTrue` 非 root**，否则 root 下 POSIX 权限位不拦人、测试假绿
- 写完目录里不留 `.tmp` 残骸

### 2. 补钉 `ProviderRegistryTest`

`select(未知 id)` 后 `activeModelId()` 不变。整个失效检测都架在这个行为上，它得有测试钉着。（现有 `ProviderRegistryTest:40` 有 `select("no-such-model")`，实施时先确认断言到底钉了什么，缺则补。）

### 3. 启动恢复逻辑抽成包私有静态方法

```java
static void restoreLastModel(ProviderRegistry registry, Path root, ConversationState state)
```

`main()` 测不了，不抽出来这个装配点就是**零覆盖**。这一条写得很坚决，因为上一轮刚吃过一模一样的亏——`TaskOutput` 的 `ToolRegistry` 注册完全没有测试，是审查 agent 翻出来的。

用例三条：

- 有记忆且可用 → 激活变了、**无**提示
- 有记忆但不可用 → 激活保持默认、有提示且提示里**同时含**旧 id 与当前 id
- 无记忆 → 激活不变、无提示

### 4. 写侧接线（`CodeTuiView`）

- 选中后 `model.json` 真的出现且内容是选中的 id
- `write` 失败时多打一行
- **不生效就不写**：喂一个 registry 里没有的 id → 文件不产生

### 5. pty 冒烟（端到端）

唯一能证明「重启之后真的记住了」的测试——单测证明不了装配顺序。

```
进程 A：启动 → /model 选第 2 项 → 断言状态栏变成第 2 项 → 退出
进程 B：同一 root、不带 -c 启动 → 断言状态栏一上来就是第 2 项
```

照既有 7 个冒烟脚本的范式：`ioctl TIOCSWINSZ` 设窗口大小 + `TERM=xterm-256color`。**断言不写死按键步数**——`wait_for` 会命中陈旧 scrollback，模型档位数一变，写死的步数就静默错位。

### 6. 变异验证

每条都必须为**正确的理由**红：

| 变异 | 必须红的测试 | 红的理由必须是 |
|---|---|---|
| 删掉启动恢复那几行 | pty 冒烟 | 进程 B 状态栏还是默认模型（**不是编译错**） |
| 删掉 `select` 后的 `activeModelId` 比对 | 「不可用时提示」 | 该出现的提示没出现 |
| 「只在生效时写」改成无条件写 | 「不生效就不写」 | 文件被创建了 |
| 读侧坏文件降级改成抛异常 | 坏文件那条 | 抛了异常而不是返回 `empty` |

## 文档改动

- `springai-code-tui/README.md`：`.codetui/` 文件清单加 `model.json` 一行
- 同上，权限模式那处「`-c` 不恢复权限模式」旁边补上本节「与权限模式的不对称」的说明

## 验证命令

```bash
mvn test -pl springai-code-tui
```

**必须带 `-pl springai-code-tui`**：整仓 `mvn test -Dtest=…` 会被 3 个空模块打挂。

