# 千问 provider 接入（差量）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 完成千问（Qwen）provider 接入的剩余差量——根 README 补千问 + 真机冒烟验收，使 `feature/qwen-provider` 分支达到可合回 main 的状态。

**Architecture:** 主体代码已在 d420794 落地（`QwenProvider` 走百炼 OpenAI 兼容通路，与 `ZhipuProvider` 同构，spec 见 `docs/superpowers/specs/2026-07-16-qwen-provider-design.md`）。本计划只做两件事：文档同步、真机验收。无新增生产代码；若冒烟失败才回到 provider 层修。

**Tech Stack:** Markdown（README）；真机验收用发布 jar + `DASHSCOPE_API_KEY`。

---

**前置状态核对**（执行前确认，不符则停下来问用户）：
- 当前分支 `feature/qwen-provider`，含 d420794（QwenProvider 实现）与 2dec424（spec）。
- `mvn -pl springai-code-tui test` 全绿（415 用例）。验证命令必须模块作用域（整仓会被空模块打挂）。

---

### Task 1: 根 README 补千问

**Files:**
- Modify: `README.md:16`（对话模型列表）
- Modify: `README.md:49`（架构图注）

说明：spec 提的「L14 综合应用层描述」经核对原文只写了泛称「多 provider」、未点名任何一家，无需改动；实际需改的是下面两处点名清单。

- [ ] **Step 1: 改 L16 对话模型列表**

原文：

```markdown
- **对话模型**：[DeepSeek](https://platform.deepseek.com/)（国内可直连、价格低）；`springai-code-tui` 额外支持 智谱 GLM / Anthropic / OpenAI
```

改为：

```markdown
- **对话模型**：[DeepSeek](https://platform.deepseek.com/)（国内可直连、价格低）；`springai-code-tui` 额外支持 智谱 GLM / [通义千问](https://bailian.console.aliyun.com/)（百炼）/ Anthropic / OpenAI
```

- [ ] **Step 2: 改 L49 架构图注**

原文：

```markdown
    └── 多 provider（DeepSeek/智谱/Anthropic/OpenAI）+ 子 agent（Task + ParallelTasks 并行）+ 技能
```

改为：

```markdown
    └── 多 provider（DeepSeek/智谱/千问/Anthropic/OpenAI）+ 子 agent（Task + ParallelTasks 并行）+ 技能
```

- [ ] **Step 3: 核对全文无其他遗漏**

Run: `grep -n "Anthropic" README.md`
Expected: 命中的 provider 清单行都已含「千问」或「通义千问」；如有第三处点名清单，按同样格式补。

- [ ] **Step 4: Commit**

```bash
git add README.md
git commit -m "docs(readme): 根 README provider 清单补通义千问"
```

### Task 2: 真机冒烟验收（需用户 DASHSCOPE_API_KEY）

**Files:** 无代码改动；产物是验收结论（通过 → 可合并；失败 → 新任务修 provider）。

前置：向用户要 key 的配置方式——用户在自己 shell 里 `export DASHSCOPE_API_KEY=...` 后运行，**不要**让用户把 key 粘贴进对话。建议用户执行 `! export DASHSCOPE_API_KEY=...` 或自行在终端跑。

- [ ] **Step 1: 构建可运行 jar**

Run: `mvn -q -pl springai-code-tui -am package -DskipTests`
Expected: BUILD SUCCESS，产出 `springai-code-tui/target/springai-code-tui.jar`

- [ ] **Step 2: 启动并核对 /model 列表**

用户在**另一个可丢弃且 git 干净的目录**执行（TUI 是交互程序，须用户亲自跑；Claude 的沙箱 shell 跑不了交互 TUI）：

```bash
export DASHSCOPE_API_KEY=你的key
cd /path/to/disposable-project
java -jar /Users/zxh/IdeaProjects/springai-agentdemo/springai-code-tui/target/springai-code-tui.jar
```

TUI 内输入 `/model`。
Expected: 列表含 4 个 qwen 模型（qwen3.7-max / qwen3.7-plus / qwen3.6-flash / qwen3-coder-next）；选中 `qwen3.7-max`。

- [ ] **Step 3: 流式对话冒烟**

TUI 内发一条普通消息（如「用一句话介绍你自己」）。
Expected: 流式逐块渲染、正常收尾；无 Stream failed / 400 / 超时。

- [ ] **Step 4: 工具调用冒烟**

TUI 内发「读取当前目录下的 README（或任一文件）并总结第一段」。
Expected: 触发文件读取工具调用并回填结果继续生成（验证 `tool_calls` 流式分片兼容）；无解析错误。

- [ ] **Step 5: 模型切换冒烟**

TUI 内 `/model` 切到 `qwen3-coder-next`，再发一条编码问题（如「写个 Java 判断回文的函数」）。
Expected: 正常流式回答（验证每请求 options 模型覆盖生效）。

- [ ] **Step 6: 记录验收结论**

通过：在对话中确认四步全过，分支可合回 main。
失败：记录失败步骤与完整报错（TUI 日志在 `logs/`），开新任务回 provider 层修——优先怀疑 baseUrl 拼接（须打到 `/compatible-mode/v1/chat/completions`）与 tool_calls 流式分片格式；参考既往教训：真实抓包才是 ground truth。

### Task 3: 收尾

- [ ] **Step 1: 全量回归**

Run: `mvn -pl springai-code-tui test`
Expected: Tests run: 415+, Failures: 0, Errors: 0

- [ ] **Step 2: 询问用户是否合并/推送**

不主动 push。用户确认后再合 main / 推远端（远端走 gh HTTPS，非 SSH）。
