# 权限管理 期 3：补判定漏洞 + 规则可删 —— 设计

**日期**：2026-08-02
**上游**：`docs/superpowers/specs/2026-07-31-permission-mode-design.md`（期 0–2 的总设计）
**前置**：期 1（引擎 + 审批面板）、期 2（计划模式）均已落地并经用户实测

---

## 为什么做这一期

期 1/2 交付时，有四条**已知但未修**的判定问题被如实写进了 README 与计划文档。
它们的共同形状是：**用户以为拦住了，实际没拦**——而权限层的全部价值就建立在这个信任上。

| # | 问题 | 现状 |
|---|---|---|
| 1 | 路径匹配区分大小写 | `deny Write(/etc/**)` 命中不了 `/ETC/passwd`，而 macOS APFS 上那是同一个文件 |
| 2 | 不解析符号链接 | 两步绕过：先让路径经符号链接指向敏感位置，规则按原写法匹配不到 |
| 3 | `ACCEPT_EDITS` 的命令段不判工作区 | `mkdir /etc/evil`、`mv ~/notes.txt /tmp/x` 直接放行，**而面板打出的理由写着「工作区内的文件操作」** |
| 4 | 过宽 root 静默架空一条检查 | `root=/` 时「写入项目与家目录之外的系统位置」对所有路径失效 |

第 3 条尤其糟：它不只是行为有洞，**界面还在说一句不实的话**。

配套做一件 UX：`/permissions` 目前**只能加不能删**（误点一次「永久允许」就得手改 JSON）。
它是本期的必要配套——修了匹配规则之后，旧规则的含义可能变了，得让人能就地清掉。

### 本期明确不做

- **危险清单扩充**。黑名单没有尽头，逐条加只会制造「越来越安全」的错觉；真实边界已在 README 写明。
- **工具自审接缝**（判定顺序第 3 步的预留插槽）。至今无实现方，YAGNI。
- **面板内新增 / 编辑规则**。新增仍走审批面板的「永久允许」；面板内手输 DSL 需要校验与错误提示，工作量接近再加半期。
- **重新审视「家目录豁免」**。见 §4 的诚实声明。

---

## 1. 贯穿原则：只在 deny/ask 方向放宽匹配

问题 1 与 2 的共同点是**同一个文件有多种写法**，而规则只按其中一种匹配。
要补就得让匹配认得出更多写法——但放宽在两个方向上的后果完全相反：

- **deny 放宽** → 多拦一些。可能误拦（Linux 上 `/etc` 与 `/ETC` 确实是两个目录），
  但**误拦你看得见、能调整**。
- **allow 放宽** → 多放行一些。**不可逆**——放过去就执行了。

故：**deny / ask 规则与内置底线按「任一写法命中即命中」；allow 规则只认原写法。**

这与引擎里既有的一处不对称同源（命令分段判定：deny/ask 任一段命中即命中，
allow 每段都命中才放行），两者同处一地，一条注释能讲清两件事。

### 1.1 放在哪一层：引擎，不是规则

| 方案 | 取舍 |
|---|---|
| **A. 引擎生成候选、逐个喂给 `rule.matches`**（**采纳**） | 不对称留在引擎，与既有的命令分段不对称同处一地；`PermissionRule` 保持纯粹 |
| B. 在 `PermissionRule.matches` 内按 behavior 分支 | **与期 1 的既定决定冲突**：「behavior 语义不属于匹配原语，同一条规则换个 behavior 就换含义会让后续任务全得记住这个例外」 |
| C. 在 `ToolTargets.extract` 产出别名集 | `extract` 不知道 behavior，做不了不对称；且它是被广泛使用的接缝，改返回类型波及面大 |

### 1.2 符号链接 = 真别名

产生一个**确实不同**的路径，故按别名处理：

```
aliases(target) = [原路径, 符号链接解析后]      // 去重、保序
```

`toRealPath()` 对**尚不存在的文件**会抛——这正是期 1 当初不用它的原因
（「写一个还不存在的文件」是常态）。解法是**解析最长的已存在祖先，再拼回剩余段**：

```
/tmp/link/sub/new.txt      （link → /etc，new.txt 尚不存在）
  最长已存在祖先 /tmp/link/sub → 解析为 /etc/sub
  拼回剩余段                   → /etc/sub/new.txt
```

这样「目标文件尚不存在、但父目录经符号链接指向敏感位置」也能被 deny 命中——
那正是两步绕过的形态。解析失败（权限不足、循环链接）时**退回原路径**，不抛。

### 1.3 大小写 = 匹配模式，不是别名

`/ETC/passwd` **不是** `/etc/passwd` 的别名（Linux 上它俩是两个文件）。
要命中 `deny Write(/etc/**)`，得让**规则与目标同时折叠**。

做法：给每条 **deny/ask 规则预生成一个折叠孪生**（pattern 小写），由引擎持有：

```java
foldedTwin(rule) = new PermissionRule(toolName, pattern.toLowerCase(Locale.ROOT),
                                      behavior, scope)
```

**`pattern == null` 的规则不生成孪生**（`工具名` / `工具名(*)` 形态匹配该工具的全部调用，
与大小写无关）——直接 `toLowerCase` 会 NPE。

在引擎构造时、以及 `addSessionRule` / `addPersistentRule` 时维护。判定时：

```
deny / ask：
    对每个 alias a：
        rule.matches(a)                        命中 → 返回
        foldedTwin.matches(a.toLowerCase())    命中 → 返回
allow：
    rule.matches(原路径)                        一次匹配，不用别名、不折叠
```

**收益：`PermissionRule` 一行不用改。** 工具名仍按原样比较（工具注册名是精确的）。

**两个机制的适用面不同，别混**：**别名**（§1.2）只对**路径目标**有意义——命令、URL、
`bash_id` 没有「符号链接解析后」这一说，它们的 alias 集就是自身；**折叠**（本节）对
路径与命令目标都适用。

**命令目标也折叠**：macOS 上 `RM -rf /` 真能执行（`/bin/RM` 在大小写不敏感的文件系统上
解析到 `/bin/rm`），故 `deny Bash(rm:*)` 应当拦得住它。代价是可能误拦一个真名叫 `RM`
的工具——极罕见，且方向安全。

### 1.4 成本

别名与折叠**只在需要时才算**：有 deny/ask 规则、或要走内置底线时。
纯只读工具与 allow 路径不付这个代价。每次判定至多 1–2 次文件系统调用。

### 1.5 内置底线：只需审计，不需改造

实测确认：`DangerousPaths` **已经大面积折叠大小写**——命令基名（`/bin/RM → rm`）、
路径分段、系统密钥全路径都折了。而 `PermissionRule` 里一处 `toLowerCase` 都没有。

**故本条的洞只在用户写的规则上。** 内置底线本期只做一次审计：确认文件名与扩展名
（`SECRET_FILES` / `SECRET_EXTENSIONS`）的比较也折叠；若有遗漏一并补上。
底线的符号链接解析同样接入 §1.2 的别名。

---

## 2. `ACCEPT_EDITS` 的命令段必须判工作区

现状：`commandByMode` 只看首词是不是 `mkdir`/`touch`/`mv`/`cp`，**完全不看目标在哪**。
而 `Write`/`Edit` 是**确实**判 `insideRoot` 的——同一个承诺，两种兑现。

新规则：这四个命令的段，**所有路径参数都在工作区内**才放行，否则 ASK。取参数的规则：

| token | 处理 |
|---|---|
| 首词（命令名） | 跳过 |
| `-` 开头 | 当选项跳过 |
| **`~` 开头** | **判为工作区外 → ASK** |
| 含通配符（`*` `?` `[`） | 取**首个通配符之前的字面前缀**判定 |
| 其余 | 绝对路径按原样；相对路径对 root 解析；要求 `insideRoot` |

**`~` 那条必须单列**：token 字面是 `~/notes.txt`，当相对路径解析会得到
`<root>/~/notes.txt`——**落在工作区内、被错误放行**，而 shell 实际会展开到家目录。
这正是探针里 `mv ~/notes.txt /tmp/x` 被放行的形态之一。

**通配符**：`*.txt` → 前缀为空 → 解析到 root → 区内；`../*.txt` → 前缀 `../` → 区外 → ASK。

误判方向安全：`mkdir -m 755 dir` 的 `755` 会被当相对路径解到 root 内（无害）；
`cp -t /etc src` 的 `/etc` 判为区外 → 问。带重定向的段本就已被排除在白名单外。

**同时改掉那句不实的文案**——`自动接受编辑：命令各段都是只读或工作区内的文件操作`
从此才是真的。

---

## 3. 过宽 root 不再静默架空检查

`isOutsideWritableRoots` 里这一句：

```java
if (root != null && abs.startsWith(root.normalize())) {
    return false;      // 不算「工作区之外的系统位置」
}
```

`root = /` 时任何绝对路径都 `startsWith("/")`，该检查对**所有路径**失效
（`cp x /usr/local/bin/git` 不再被拦）。按名字命中的检查（`.ssh`、shell 启动文件、密钥）
不受影响，仍然生效——**被架空的是这一条，不是整层底线**。

判据不用深度启发式，用一条精确的：

```
root 过宽  ⟺  root 是 "/"，或 root 是家目录的严格祖先
```

`/work` 不含家目录 → 正常；`/Users` 含 `/Users/<你>` → 过宽；`/` → 过宽。
过宽时**不拿 root 做豁免**，该检查照常生效，并在启动时打一行说明为何会多问。

---

## 4. 一处诚实声明：`root == 家目录本身` 救不了

直接在 `$HOME` 下运行时，§3 的修法**不生效**——因为紧接着还有一条独立的
「家目录豁免」（`abs.startsWith(home)` → 不算系统位置）。改了 root 那条，家目录那条照样放行。

要动它就得重新审视「你自己家里的文件不算系统位置」这条设计，那是另一个决定，
**不在本期**。README 里「不要在 `$HOME` 下直接运行」的告诫仍然只能靠自律。

写下这条是为了避免一种更坏的结果：**以为修好了**。

---

## 5. `/permissions` 升级为可删规则的面板

从「只读打进 scrollback」改成交互面板，照 `/mcp` 的既有形状（↑↓ 选择、即时生效 + 回写文件）。

```
  🔑 权限规则（↑↓ 选择 · d 删除 · Esc 关闭）
    [DENY]  Read(**/.env)                    用户级
  ❯ [ALLOW] Bash(mvn test:*)                 项目级
    [ALLOW] BochaWebSearch(*)                本会话
    ─────────────────────────────────────
    内置底线不在此列，无法删除
```

三条设计判断：

1. **删除键是 `d`，不是 Enter。** `/mcp` 用 Enter 切换启用状态，那是可逆的；
   删规则不可逆（尤其删 deny），不该和「移动光标后顺手回车」共用一个键。
2. **删除一律要确认，且删 deny 时措辞不同**：删 allow 说「以后会重新询问」，
   删 deny 说「**这会放宽权限**」。同一操作在两个方向上后果不对称，提示也该不对称。
3. **删完必须同步从引擎的内存规则表里摘掉**（`fileRules` / `sessionRules`），
   否则重启前它还在生效——**面板说删了、实际还拦着，是最坏的一种谎**。

本会话规则只从内存摘；落盘规则回写对应层文件（用户级 `~/.codetui/permissions.json`，
项目级 `<root>/.codetui/permissions.json`）。

**同一条 DSL 可能同时存在于两层**（两层是取并集，不是覆盖）。面板**按层逐条列出**，
删的是**选中那一条所属的层**——不做「一键删光同名规则」，那会在用户只想清掉项目层时
悄悄动了他自己机器上的用户层配置。

需要给 `PermissionConfigWriter` 加一个 `remove`，纪律照抄现有的 `append`：
原子写、保留未知字段与条目顺序、JSON 非法就整个不动、重复键检测、保留原 POSIX 权限位。

---

## 6. 测试策略

本项目出过五个「不会失败的测试」，故这一节写具体判据而非原则。

**① 匹配逻辑的断言不得依赖宿主文件系统。**
折叠孪生是纯字符串匹配，任何 OS 上都可测。**危险写法**是「创建 `/ETC/x` 再断言被拦」——
在 APFS 上它与 `/etc/x` 是同一个文件，测试会因**错误的原因**变绿，换到 Linux CI 就红。

**② 必须有「allow 不得被放宽」的反向断言**——这是整个方案的安全支点，也最容易漏：

```
allow Write(/etc/**)  对  /ETC/passwd  → 不命中
deny  Write(/etc/**)  对  /ETC/passwd  → 命中
```

**③ 符号链接用 `@TempDir` + `Files.createSymbolicLink` 造真实链接**，
覆盖「目标文件尚不存在、但父目录经链接指向敏感位置」这一形态。
Windows 上创建符号链接可能需要特权 → 用 `Assumptions` 跳过而非失败。

**④ 每条修复都要变异实测**：停用该修复后对应用例必须变红；仍绿说明用例没打到那条路径，
**先修用例**。

**⑤ pty 实机验面板**：高亮纯前景不串色、每项占一个物理行、确认行出现——三条在本项目均有前科。

**⑥ 验证命令必须带模块作用域**：`mvn test -pl springai-code-tui`；不许用
`-DfailIfNoSpecifiedTests=false` 盖问题。既有 flaky `CodingAgentSpikeTest.todoTurnIdBinding`
（打真实模型、60s 超时）的红单独记录、不计入判据。

---

## 7. 影响面

| 文件 | 改动 |
|---|---|
| `agent/permission/PermissionEngine.java` | 别名 + 折叠孪生（deny/ask/底线）；`ACCEPT_EDITS` 命令段判工作区；规则删除入口 |
| `agent/permission/PathAliases.java`（新） | 符号链接解析（最长已存在祖先 + 拼回剩余） |
| `agent/permission/DangerousPaths.java` | 接入别名；大小写折叠审计；过宽 root 判据 |
| `agent/permission/BashCommandSplitter.java` | 命令段的路径参数抽取 |
| `agent/permission/PermissionConfigWriter.java` | 新增 `remove` |
| `ui/CodeTuiView.java` | `/permissions` 改交互面板 + 删除确认 |
| `CodeTuiApplication.java` | 过宽 root 的启动提示 |
| `README.md` / `SECURITY.md` | 已知限制随修复更新 |

`PermissionRule.java` **不改**——这是 §1.1 选 A 的直接收益。

---

## 8. 验收

- [ ] `mvn test -pl springai-code-tui` 全绿（既有 flaky 单独记）
- [ ] `permission_smoke.py` → `SMOKE PASS`，含 `/permissions` 面板的新断言
- [ ] 四条修复各自的变异实测都能让对应用例变红
- [ ] 人工：误点一次「永久允许」后，能在 `/permissions` 面板里删掉它，且**重启后确实不在**
- [ ] 人工：删一条 deny 规则时，确认提示明确说「这会放宽权限」
