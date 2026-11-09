# springai-tamboui-demo 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新建教学模块 `springai-tamboui-demo`（学习路线新第⑤步），用 4 个可运行示例覆盖 code-tui 实际用到的 TamboUI 0.4.0 API 子集，并完成学习路线改号（code-tui 顺延为第⑥步）。

**Architecture:** 多示例类 + 菜单 main（core-demo 式）。四个示例按层次递进：①文本/样式/宽度（stdout 直出）→ ②Buffer/Frame/Block 立即模式（InlineTuiRunner）→ ③事件循环/按键绑定/粘贴（InlineTuiRunner）→ ④Toolkit DSL 迷你版 CodeTuiView（InlineApp，live 区 + scrollback 双通道）。每个示例的可提纯逻辑抽成独立类先行单测；TUI 交互以真实终端人工跑验收。上游坑按 B 案三段式（带病演示→病因→药方）展示。

**Tech Stack:** Java 17、Maven、TamboUI 0.4.0（tamboui-bom 管理，仅上游原版五个 jar：core/tui/widgets/toolkit/jline3-backend）、JUnit 5。

**Spec:** `docs/superpowers/specs/2026-11-09-tamboui-demo-design.md`（已批准；计划据此论证，执行者两份都读）

## Global Constraints

- Java 17 语言级别（不更高）；注释/文档/提交信息全中文；提交前缀 `feat:`/`test:`/`docs:` 等。
- 模块依赖白名单：`dev.tamboui` 五个上游 jar（版本随根 pom `tamboui-bom` 0.4.0，pom 里不写版本号）+ `junit-jupiter`(test)。**禁止**依赖 `springai-tamboui-inline-patch`、spring-ai、spring-boot、跨模块依赖其他 demo。
- 每个示例类 javadoc 首行含「基于 TamboUI 0.4.0 实测 API」。
- 测试命令带模块作用域：`mvn -pl springai-tamboui-demo -am test`；单类另加 `-Dsurefire.failIfNoSpecifiedTests=false`。
- 分支 `feature/tamboui-demo`，不直接提交 main；一次提交只做一件事，功能与测试同提交。
- 文档内链接一律相对路径；不做 release 相关动作（版本 bump / release notes / CHANGELOG 不在本分支）。
- TamboUI API 签名已全部 javap 核实（见各任务 Interfaces），实施时不得臆造方法名；有疑虑先 `javap -cp ~/.m2/repository/dev/tamboui/<artifact>/0.4.0/*.jar <类名>` 或看同目录 sources jar。

## Review Focus

（spec 隐含但无任务测试覆盖、最可能咬人的输入类；每条钉到 owning task 的测试）

1. **菜单输入非法/越界/非数字**——应被拒绝并提示，不得抛异常退出菜单 → `MenuInputTest`（Task 1）。
2. **上游宽度值随版本漂移**（0.4.0 带病值是教学叙事的事实基础）→ `WidthSamplesTest` 钉死 0.4.0 实测值（Task 2）。
3. **粘贴文本含 `\r\n` / 孤立 `\r`**——纯统计层必须正确计数（CR 泄漏是上游坑，不是统计层的锅）→ `PasteStatsTest`（Task 4）。
4. **TextAreaState 多行编辑**（Enter 换行、backspace 跨行回删）→ `TextAreaStateTest`（Task 5）。
5. **示例③④异常退出路径**（q / Ctrl+C 必须可靠退出并恢复终端，否则终端留在 raw mode）→ 代码层钉 try-with-resources（InlineTuiRunner/InlineApp 均实现 AutoCloseable），人工验收步骤逐条检查（Task 4/5 的手跑步骤）。

## File Structure

```
springai-tamboui-demo/
├── pom.xml                                        # Task 1
├── README.md                                      # Task 6
└── src/
    ├── main/java/io/github/javaside/springai/tamboui/
    │   ├── TambouiDemoApplication.java            # Task 1 菜单入口
    │   ├── TambouiDemo.java                       # Task 1 示例接口
    │   ├── MenuInput.java                         # Task 1 纯逻辑：菜单输入解析
    │   └── demo/
    │       ├── TextStylesWidthDemo.java           # Task 2 示例①
    │       ├── WidthSamples.java                  # Task 2 纯数据：宽度对照表（含病值标注）
    │       ├── BufferBlockDemo.java               # Task 3 示例②
    │       ├── BufferBlockArtist.java             # Task 3 纯逻辑：整帧 Buffer 绘制
    │       ├── InlineEventsDemo.java              # Task 4 示例③
    │       ├── PasteStats.java                    # Task 4 纯逻辑：粘贴统计
    │       ├── ToolkitChatDemo.java               # Task 5 示例④
    │       └── FakeStreamer.java                  # Task 5 纯逻辑：假流式分帧
    └── test/java/io/github/javaside/springai/tamboui/
        ├── MenuInputTest.java                     # Task 1
        ├── WidthSamplesTest.java                  # Task 2
        ├── BufferBlockArtistTest.java             # Task 3
        ├── PasteStatsTest.java                    # Task 4
        ├── FakeStreamerTest.java                  # Task 5
        └── TextAreaStateTest.java                 # Task 5
```

仓库级改动：根 `pom.xml`（modules，Task 1）、根 `README.md` + `AGENTS.md` + `springai-jline-demo/README.md` + `.github/workflows/ci.yml`（Task 7）。

**纯逻辑类与示例类分离的原因**：示例类进 TUI 模式后不可单测（jline-demo 同口径）；可测逻辑全部下沉到无 tamboui 运行期依赖的纯类（WidthSamples 只依赖 CharWidth 静态方法，BufferBlockArtist 只操作 Buffer 数据结构——都不起终端）。

---

### Task 1: 分支与模块骨架（pom / 菜单 / 打包接线）

**Files:**
- Create: `springai-tamboui-demo/pom.xml`、`src/main/java/io/github/javaside/springai/tamboui/{TambouiDemoApplication,TambouiDemo,MenuInput}.java`
- Modify: 根 `pom.xml`（`<modules>` 里 `springai-jline-demo` 之后、`springai-tamboui-inline-patch` 之前插入一行）
- Test: `src/test/java/io/github/javaside/springai/tamboui/MenuInputTest.java`

**Interfaces:**
- Produces（后续任务依赖）:
  - `interface TambouiDemo { int order(); String title(); void run() throws Exception; }`（菜单据此排序与调度，对照 core-demo 的 `Demo`）
  - `final class MenuInput { /** @return 1..count 有效选择；0 退出；-1 非法 */ static int parse(String line, int count) }`
  - 可运行模块：`mvn -pl springai-tamboui-demo -am test` 可执行。
  - **注册点**：`TambouiDemoApplication` 里的示例 `List.of(...)` 是唯一注册处——Task 2–5 每个任务的 Step 3 收尾都必须把新示例类按 order 填进去，否则菜单里看不到。

- [ ] **Step 1: 建分支与模块空壳**

```bash
git checkout -b feature/tamboui-demo
```

同时建好模块空壳（pom + 根 pom 注册 + 包目录），使后续 `mvn -pl springai-tamboui-demo ...` 可解析。pom 要点（对照 jline-demo pom 抄结构）：parent 继承根 pom；依赖 `dev.tamboui:tamboui-core/tamboui-tui/tamboui-widgets/tamboui-toolkit/tamboui-jline3-backend`（不写版本，bom 管理）+ `junit-jupiter`(test)；jar-plugin 主类 `io.github.javaside.springai.tamboui.TambouiDemoApplication` + addClasspath + `classpathPrefix=lib/`；dependency-plugin 拷 `target/lib`；surefire 3.5.2。根 `pom.xml` 的 `<modules>` 在 `springai-jline-demo` 之后、`springai-tamboui-inline-patch` 之前插入。

- [ ] **Step 2: 写失败测试 `MenuInputTest`**

用例：`"1"`→1、`" 2 "`→2（容忍空白）、`"0"`→0、`"q"`→-1、`""`→-1、`"9"`(count=4)→-1、`"-1"`→-1、`null`→-1、`"3"`(count=4)→3。

- [ ] **Step 3: 跑测试确认红**

Run: `mvn -pl springai-tamboui-demo -am test`
Expected: 编译失败（MenuInput 不存在）。

- [ ] **Step 4: 实现最小骨架**

pom/注册已在 Step 1 建好，本步只写 Java：`MenuInput.parse`：trim 后非数字/越界/空 → -1；`Integer.parseInt` 包 NumberFormatException。
`TambouiDemoApplication`：main 里 `List<TambouiDemo> demos = List.of()`（后续任务逐个填），自写简版菜单循环（对照 core-demo `ConsoleMenu` 的交互形态：打印编号列表 → 读行 → `MenuInput.parse` 分派），**不跨模块 import core-demo**。

- [ ] **Step 5: 跑测试确认绿**

Run: `mvn -pl springai-tamboui-demo -am test`
Expected: PASS（MenuInputTest 全绿）

- [ ] **Step 6: 验证打包**

Run: `mvn -pl springai-tamboui-demo -am package -DskipTests && ls springai-tamboui-demo/target/lib | head -8`
Expected: 产出可执行 jar，`target/lib` 含五个 tamboui jar。运行 `java -jar springai-tamboui-demo/target/springai-tamboui-demo-1.30.0.jar` 应打印菜单（0 退出）。

- [ ] **Step 7: 提交**

```bash
git add springai-tamboui-demo/pom.xml springai-tamboui-demo/src pom.xml
git commit -m "feat: springai-tamboui-demo 模块骨架——pom 接线与示例菜单"
```

---

### Task 2: 示例① TextStylesWidthDemo —— 文本样式与宽度口径（带病演示）

**Files:**
- Create: `demo/TextStylesWidthDemo.java`、`WidthSamples.java`
- Test: `WidthSamplesTest.java`

**Interfaces:**
- Consumes: `TambouiDemo`（Task 1）。
- Produces（demo 类与 README 依赖）:
  - `record WidthSample(String label, String sample, int upstream, int actualTerminal, boolean sick)`——`upstream` 为上游 `CharWidth.of(String)` 返回值，`actualTerminal` 为 patch 口径的终端实际渲染宽度，`sick=true` 表示两者不符（带病）。
  - `final class WidthSamples { static List<WidthSample> table(); }`
- 已核实的 TamboUI 0.4.0 签名（javap 实测）：`CharWidth.of(String)→int`；`Span.styled(String,Style)` / `.fg(Color)` / `.bold()`；`Line.from(Span...)` / `.centered()`；`Text.from(Line...)`；`Style.create().fg(Color)` 及 `.red()/.bold()` 等便捷链；`Color` 常量 `Color.RED/GREEN/YELLOW/CYAN/GRAY/...`。

- [ ] **Step 1: 写失败测试 `WidthSamplesTest`**

断言 `WidthSamples.table()` 逐行值（0.4.0 实测，2026-11-09 本机 javap 探针证据，串以码点标注防编辑器隐形改写）：

| label | sample（码点） | upstream（断言值） | actualTerminal | sick |
|---|---|---|---|---|
| ASCII | `A` | 1 | 1 | false |
| 中文 | `中` | 2 | 2 | false |
| 雪花+VS16 | U+2744 U+FE0F | 1 | 2 | true |
| keycap | U+0039 U+FE0F U+20E3 | 1 | 2 | true |
| ZWJ 家族 | U+1F468 U+200D U+1F469 U+200D U+1F467 | 2 | 5 | true |
| 含 ZWNJ | `a` U+200C `b` | 2 | 3 | true |

同时断言：`CharWidth.of(sample) == upstream` 对每行成立（表与库现场互证——这是活断言：上游升级修好病值时测试变红，提示更新教学文案）。测试源码里这些串用 `new String(new int[]{0x2744,0xFE0F},0,2)` 之类的码点构造，禁止字面量（防格式化工具/复制粘贴引入不可见变化）。

- [ ] **Step 2: 跑测试确认红**

Run: `mvn -pl springai-tamboui-demo -am test -Dtest='WidthSamplesTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败（WidthSamples 不存在）。

- [ ] **Step 3: 实现 `WidthSamples` 与 `TextStylesWidthDemo`**

`WidthSamples.table()` 返回上表六行（sample 同样码点构造，附一行 javadoc 说明实测来源与日期）。
`TextStylesWidthDemo`（implements TambouiDemo，order=1）三段式，stdout 直出：
1. **富文本三粒度**：`Span.styled(...)` 着色加粗 → `Line.from(...)` 拼行 → `Text.from(...)` 组段，逐级打印并注释「Span=片段/Line=一行/Text=一段」；
2. **宽度对照表**：打印 `WidthSamples.table()` 为四列表（样本、上游算的宽度、终端实际宽度、病否），病行用红色标注 + 现场提示「盯着右两列看跳格」；文案注明 actualTerminal 取自 patch 模块 spec 的实测记录、不同终端可能有出入（ZWJ 行尤其如此——这正是没有唯一真相时的工程口径问题）；
3. **手动两栏对齐演示**：左列放样本、按 `CharWidth` 计算的宽度补空格再放右栏——ASCII/中文两行右栏对齐，四个病行右齐崩坏；结尾三行文字给出病因（逐码点/跳宽口径的局限）与药方（code-tui shadow `CharWidth` 并自建 `TerminalWidth`，相对链接指向 `../springai-tamboui-inline-patch`，注意 README 里链接相对路径规则——示例运行时打印纯文本路径即可）。

- [ ] **Step 4: 跑测试确认绿**

Run: `mvn -pl springai-tamboui-demo -am test -Dtest='WidthSamplesTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS。

- [ ] **Step 5: 真实终端人工验收**

Run: 打包后在真实终端跑菜单选 1。检查点：彩色文本可见；对照表四病行宽度列 1/1/2/2 与实际列 2/2/5/3 并排；对齐演示中病行右栏 visibly 错位。IDE 控制台跑只验逻辑不验对齐（宽口径依赖终端字体），README 的运行说明后续任务会写明。

- [ ] **Step 6: 提交**

```bash
git add springai-tamboui-demo/src
git commit -m "feat: 示例① 文本样式与宽度口径——上游 CharWidth 带病演示"
```

---

### Task 3: 示例② BufferBlockDemo —— 立即模式与差分渲染

**Files:**
- Create: `demo/BufferBlockDemo.java`、`BufferBlockArtist.java`
- Test: `BufferBlockArtistTest.java`

**Interfaces:**
- Consumes: `TambouiDemo`（Task 1）。
- Produces:
  - `final class BufferBlockArtist { /** 画一帧：ROUNDED 全边框 + 标题 + 内部帧计数与自定义 cell */ static Buffer draw(int width, int height, long frame) }`
- 已核实签名：`Block.builder().borders(Borders.ALL).borderType(BorderType.ROUNDED).build()`（code-tui CodeTuiView:1748 同款用法）；`block.render(Rect, Buffer)`、`block.inner(Rect)→Rect`；`Buffer.empty(Rect)` / `.set(int,int,Cell)` / `.setString(int,int,String,Style)` / `.get(int,int)→Cell`；`Cell.symbol()→String`；`Rect(int x,int y,int w,int h)`；`InlineTuiRunner.create(InlineTuiConfig)` + `.run(InlineEventHandler, Renderer)`，`InlineEventHandler.handle(Event, InlineTuiRunner)→boolean`，`Renderer.render(Frame)`，`Frame` 含 buffer（render 回调里 `frame.buffer()`——若 javap 无此方法则以 sources jar 为准调整，教学点不变）。

- [ ] **Step 1: 写失败测试 `BufferBlockArtistTest`**

`draw(20, 6, 0)` 断言：`(0,0)` 处 cell symbol 为 ROUNDED 左上角 `╭`（以实际渲染为准——先在测试里断言 `!= Cell.EMPTY.symbol()` 且为单字符框线，运行后把实际值写死为精确断言）；`(10,0)` 为上边框线；`(1,1)` 起的内部行可读出标题文本；`(19,5)` 为右下角框线；内部不含框线字符。`draw(20,6,1)` 与 frame=0 相比仅帧计数 cell 不同（Artist 层面验证「整帧重画」——差分是 runner 的职责，见 Step 3 说明）。

- [ ] **Step 2: 跑测试确认红**

Run: `mvn -pl springai-tamboui-demo -am test -Dtest='BufferBlockArtistTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败。

- [ ] **Step 3: 实现 `BufferBlockArtist` 与 `BufferBlockDemo`**

`draw`：`Buffer.empty(new Rect(0,0,width,height))` → `Block` 画框 → `inner()` 内 `setString` 写标题与 `frame` 计数 → 在内部空区用 `set(x,y,new Cell("◆", Style...))` 放一个随 frame 移动的光标点（教学点：你写的是「整屏应该长什么样」）。
`BufferBlockDemo`（order=2）：`InlineTuiRunner.create(InlineTuiConfig.defaults(8))`，`run(handler, renderer)`：renderer 每帧调 `BufferBlockArtist.draw(runner 宽高, frame++)` 渲染进 Frame；handler 里空格键推动 frame（请求重绘）、q/Esc `runner.quit()`；注释讲清「你只描述整屏，库对前后两帧 Buffer 做差分、只发变化的 cell——这就是立即模式」。try-with-resources 包 runner。

- [ ] **Step 4: 跑测试确认绿**

Run: `mvn -pl springai-tamboui-demo -am test -Dtest='BufferBlockArtistTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS。

- [ ] **Step 5: 真实终端人工验收**

跑菜单选 2：出现圆角边框面板与移动的 ◆；按空格 ◆ 移动；q 退出后终端恢复正常（无残留 raw mode）。改小窗口再按空格，帧随新尺寸重画。

- [ ] **Step 6: 提交**

```bash
git add springai-tamboui-demo/src
git commit -m "feat: 示例② Buffer 立即模式与差分渲染——Block 画框整帧重画"
```

---

### Task 4: 示例③ InlineEventsDemo —— 事件循环、按键绑定与粘贴三段式

**Files:**
- Create: `demo/InlineEventsDemo.java`、`PasteStats.java`
- Test: `PasteStatsTest.java`

**Interfaces:**
- Consumes: `TambouiDemo`（Task 1）、Task 3 的 runner 用法。
- Produces:
  - `record PasteStats(int chars, int lines, int crlfCount) { static PasteStats of(String text); }`——`lines` 按换行分段计数（`\n`/`\r\n`/孤立 `\r` 都算换行，与事件层括号粘贴协议送来的原文口径一致），`crlfCount` 为 `\r\n` 出现次数（教学用：CR 是否被吃掉一测便知）。
- 已核实签名：`InlineTuiConfig.builder(int height).bracketedPaste(boolean).build()`（`defaults(int)` 为无括号粘贴的便捷版，本示例必须显式 `builder(...).bracketedPaste(true)` 才能稳定收到 `PasteEvent`）；事件类型 `KeyEvent(KeyCode, KeyModifiers, int)`（访问器 `code()`/`ch()` 以 javap 实测为准）、`PasteEvent.text()`、`ResizeEvent.width()/height()`；`KeyTrigger.key(KeyCode.UP)` / `.ch('q')` / `.matches(Event)`；`Bindings`（`InlineTuiConfig.Builder.bindings(...)` 接受）与 `Actions` 的具体构造以 sources jar 为准——本示例最小化依赖：方向键/字母键直接在 handler 里 `KeyTrigger.matches` 判定即可，不强求 `Bindings/Actions` 全套（code-tui 用了，示例注释里点名这两个类名与用途，代码层用 KeyTrigger 达成同一教学点）。

- [ ] **Step 1: 写失败测试 `PasteStatsTest`**

用例：`"abc"`→(3,1,0)；`"a\nb"`→(3,2,0)；`"a\r\nb"`→(4,2,1)；`"a\rb"`（孤立 CR）→(3,2,0)；空串→(0,1,0)；`"行1\r\n行2\r\n行3"`→按行数 3、crlf 2。断言用精确值（能被反向改动杀死）。

- [ ] **Step 2: 跑测试确认红**

Run: `mvn -pl springai-tamboui-demo -am test -Dtest='PasteStatsTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败。

- [ ] **Step 3: 实现 `PasteStats` 与 `InlineEventsDemo`**

`InlineEventsDemo`（order=3）「按键仪表盘」，`InlineTuiRunner.create(InlineTuiConfig.builder(10).bracketedPaste(true).build())`，handler 维护：最近按键名、光标块 (x,y)（方向键经 `KeyTrigger.key(UP/DOWN/LEFT/RIGHT).matches` 移动）、最近窗口尺寸、最近一次 `PasteEvent` 的 `PasteStats`。renderer 把这些画进 Frame（复用 Task 3 的 setString 手法）。三段式粘贴：
1. 提示「贴一小段多行文本」→ 收到 PasteEvent → 显示统计（正常）；注释点出括号粘贴协议（终端把粘贴包在 ESC[200~/201~ 之间，库据此区分「粘贴」与「逐键」）；
2. 提示「再贴一大段（几百行，慢慢贴）」→ 上游 50ms 静默预算掐断、剩余字节回按键解析 → 仪表盘上看到统计错乱/按键名乱跳（病）；
3. 界面固定一行小字讲病因与药方（patch shadow `EventParser` 提高静默预算 + 修 CR 泄漏，链接 spec `2026-10-09-cr-paste-leak-and-vs16-width-design.md` 的仓库相对路径）。
q 或 Esc `runner.quit()`；try-with-resources。

- [ ] **Step 4: 跑测试确认绿**

Run: `mvn -pl springai-tamboui-demo -am test -Dtest='PasteStatsTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS。

- [ ] **Step 5: 真实终端人工验收**

跑菜单选 3：按方向键光标块移动；改窗口尺寸行更新；贴小段多行文本统计正确；贴大段文本（从本仓库任一源文件粘贴 100+ 行）看到统计错乱或按键乱跳——这正是要演示的病；q 退出终端恢复正常。

- [ ] **Step 6: 提交**

```bash
git add springai-tamboui-demo/src
git commit -m "feat: 示例③ 事件循环与按键绑定——粘贴三段式演示上游坑"
```

---

### Task 5: 示例④ ToolkitChatDemo —— 迷你版 CodeTuiView（Toolkit DSL）

**Files:**
- Create: `demo/ToolkitChatDemo.java`、`FakeStreamer.java`
- Test: `FakeStreamerTest.java`、`TextAreaStateTest.java`

**Interfaces:**
- Consumes: `TambouiDemo`（Task 1）。
- Produces:
  - `final class FakeStreamer { FakeStreamer(String fullText); /** 每 tick 推进一个字符；返回当前视图 */ StreamView view(); void advance(); boolean done(); record StreamView(String liveTail, List<String> settledLines) {} }`——`liveTail` 是尚未定稿的残行（live 区原地刷新用），`settledLines` 是已遇到 `\n` 定稿的完整行（下沉 scrollback 用）。
- 已核实签名：`InlineApp` 钩子 `height()/render()/configure(int)/onStart()/onStop()`，工具 `runner()→InlineToolkitRunner`、`println(String)`、`quit()`、`run()` 自举（无需自己 create runner）；DSL：`Toolkit.text(Object)`、`richText(Text)`、`column(Element...)`、`textArea(TextAreaState)`、`InlineToolkit.scope(boolean, Element...)`；`TextAreaState()` / `.insert(String)` / `.insert(char)` / `.text()` / `.lineCount()` / `.getLine(int)` / `.cursorRow()/.cursorCol()`（其余编辑方法 deleteBackward/deleteForward/moveCursor* 等以 javap 实测为准）；code-tui 参照物：`CodeTuiView`（render() 的 column+scope 组合、inputElement/statusLine 结构、Enter 拦截模式见其 1661 行 `handleKeyEvent`——示例照此模式但规模缩小到 ~150 行）。

- [ ] **Step 1: 写失败测试 `FakeStreamerTest` 与 `TextAreaStateTest`**

FakeStreamer：全文 `"你好\n世界吗"`——advance×2 后 `view().liveTail()=="你好"` 的前两字残段、settled 空；advance 到第 3 字（越过 `\n`）后 settled=["你好"]、liveTail 为空；done 后 liveTail 空、settled=["你好","世界吗"]。再测单行无换号与空文本两个边界。
TextAreaStateTest（直接测上游库行为，钉 API 契约）：`insert('a')` 后 `insert('b')` → text=="ab"、cursor 在行尾；`insert("行1")` 后插入换行（以 javap 实测的换行方法为准——`TextAreaState.insert(char)` 传 `'\n'` 或专用方法）→ `lineCount()==2`、`getLine(0)=="行1"`；backspace 删到行首再删一次应回到上一行行尾（跨行回删）；`clear()` 或 `setText` 后 text 为新值。

- [ ] **Step 2: 跑测试确认红**

Run: `mvn -pl springai-tamboui-demo -am test -Dtest='FakeStreamerTest,TextAreaStateTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败。

- [ ] **Step 3: 实现 `FakeStreamer` 与 `ToolkitChatDemo`**

`ToolkitChatDemo extends InlineApp`（order=4），覆盖 `height()`（空态 4，对照 CodeTuiView）、`render()`、`configure(int)`（Ctrl+C 绑定退出——绑法对照 CodeTuiView 同名方法的写法）：
- `render()` 返回 `column(scope(流式中, richText(残行)), scope(流式中, text("⟐ 生成中…")), inputElement, statusLine)`——两个 scope 展示「面板用完即收起」（preferredSize=0 → 行回收）；所有 children 首行判空（scope 第二参 eager 求值，CodeTuiView 同坑）。
- 输入框：`textArea(TextAreaState)` 包 `Block` 圆角边框（对照 code-tui 输入框形态）；Enter 拦截发送（`handleKeyEvent` 模式返回 `EventResult.HANDLED`），Ctrl+C 退出。
- 发送后：用户行经 `runner().println(...)` 下沉 scrollback；`InlineToolkitRunner.scheduleRepeating`（builder-check 实测存在；若签名有出入以 javap 为准）或 `runOnRenderThread` 定时推进 `FakeStreamer`，每 tick 后请求重绘——残行在 live 区原地变化，定稿行 println 下沉。
- javadoc 头部给出与 CodeTuiView 的对应表（钩子↔钩子、双通道↔双通道、scope↔面板收起），并注明「原版约 2000 行，本示例 ~150 行」。

- [ ] **Step 4: 跑测试确认绿**

Run: `mvn -pl springai-tamboui-demo -am test -Dtest='FakeStreamerTest,TextAreaStateTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS。

- [ ] **Step 5: 真实终端人工验收**

跑菜单选 4：底部圆角输入框可打中文/多行；Enter 发送后用户行沉入 scrollback；假流式逐字出现（残行原地刷新）、「⟐ 生成中…」面板出现；流完后面板收起、输入框回到底部原位；Ctrl+C 退出终端恢复正常。

- [ ] **Step 6: 提交**

```bash
git add springai-tamboui-demo/src
git commit -m "feat: 示例④ Toolkit DSL 迷你聊天视图——live 区与 scrollback 双通道"
```

---

### Task 6: 模块 README

**Files:**
- Create: `springai-tamboui-demo/README.md`

**Interfaces:** Consumes: Task 2–5 的示例清单与教学点（本任务纯文档）。

- [ ] **Step 1: 写 README**

结构（对照 jline-demo/core-demo README 的密度）：
1. 一句话定位 + 示例表（# / 示例 / 你将学到，四行）；
2. 「运行」节：真实终端要求 + `mvn -q -pl springai-tamboui-demo -am package -DskipTests` + `java -jar springai-tamboui-demo/target/springai-tamboui-demo-<version>.jar`（命令可复制运行，带 -am）；
3. 「与 code-tui 的对应表」：本模块 API ↔ code-tui 使用处（Span/Line/Text↔富文本渲染、Buffer/Frame↔差分重绘、InlineTuiRunner↔事件循环、Toolkit DSL↔CodeTuiView、Block/TextAreaState↔输入框面板）；**逐项标注 4 个符号（`TerminalWidth`/`CharWidth`/`Buffer`/`InlineTuiRunner`）在 code-tui 的 classpath 上实际解析到 patch 版**；
4. 「为什么存在 inline-patch」一节：B 案三段式的收束——宽度病值、粘贴静默预算、CR 泄漏各自一句话 + 相对链接（`../springai-tamboui-inline-patch`、`../docs/superpowers/specs/2026-10-09-cr-paste-leak-and-vs16-width-design.md`）；
5. 「不教什么」：TamboUI 其余能力（全屏 TuiRunner、chart/table/css/export）指向官方文档 https://tamboui.dev 与 GitHub 仓库。

- [ ] **Step 2: 验证**

Run: `grep -c "](\.\." springai-tamboui-demo/README.md` 且肉眼检查全部链接为相对路径；`mvn -pl springai-tamboui-demo -am test` 仍 PASS（README 不影响，但提交前例行）。

- [ ] **Step 3: 提交**

```bash
git add springai-tamboui-demo/README.md
git commit -m "docs: springai-tamboui-demo 模块 README——示例表与 patch 说明"
```

---

### Task 7: 学习路线改号 + CI 接入 + 整体验证

**Files:**
- Modify: 根 `README.md`、`AGENTS.md`、`springai-jline-demo/README.md`、`.github/workflows/ci.yml`

**Interfaces:** Consumes: Task 1–6 全部完成（改号前提是模块已存在且有测试）。

- [ ] **Step 1: 根 README 改号**

模块表加第⑤行 `springai-tamboui-demo`（公式列「（界面·框架）」，一句话「TamboUI 终端 UI 框架：文本/立即模式/事件循环/Toolkit DSL」），code-tui 行改第⑥；「五个模块」→「六个模块」相关措辞（两处以上，逐个 grep）；新增「第 5 步 · springai-tamboui-demo」章节（示例表 + 本步核心认知一句 + ✅ 自测一句），原「第 5 步 · springai-code-tui」章节改第 6 步；路线总述（第 22–24 行附近「终端界面」处）补「TUI 框架」半句。jline-demo README 第 18–19 行衔接语补「下一步见 springai-tamboui-demo」。

- [ ] **Step 2: AGENTS.md 同步**

仓库结构表加一行（模块/角色「第⑤步：TamboUI 终端 UI 框架」/测试「少量」）；概览段「前四个模块按学习路线递进（对话 → 工具/记忆/循环 → Boot 自动装配 → 终端界面）」改为含五个教学模块的表述。

- [ ] **Step 3: CI 接入**

`.github/workflows/ci.yml` 「测试 springai-code-tui」步骤后追加：

```yaml
      - name: 测试 springai-tamboui-demo
        run: mvn -B -pl springai-tamboui-demo -am test
```

- [ ] **Step 4: 残留检查**

Run: `grep -rn "五个模块\|第 5 步 · springai-code-tui" README.md AGENTS.md` 
Expected: 无输出（改号干净）。

- [ ] **Step 5: 整体验证**

Run: `mvn -pl springai-code-tui -am test && mvn -DskipTests package`
Expected: 全绿（code-tui 回归不受影响 + 整仓打包含新模块）。真机四示例已在前四个任务逐个验收。

- [ ] **Step 6: 提交与合并**

```bash
git add README.md AGENTS.md springai-jline-demo/README.md .github/workflows/ci.yml
git commit -m "docs: 学习路线新增第⑤步 springai-tamboui-demo，code-tui 顺延第⑥步；CI 接入新模块测试"
git checkout main && git merge --no-ff feature/tamboui-demo -m "merge: feature/tamboui-demo —— TamboUI 教学模块（学习路线新第⑤步）"
```

合并后 `mvn -pl springai-code-tui -am test` 复跑一次确认 main 状态干净（memory：跨模块测试先 `mvn -N install` 父 pom 非必需——本仓单仓单版本，直接跑）。

