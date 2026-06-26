---
name: git-commit-message
description: 帮助按 Conventional Commits 规范撰写规范的 git 提交信息。当用户需要写 commit message、提交信息、git 提交说明，或描述一次代码改动时使用。
---

# 规范化 Git 提交信息

## 用途
根据用户描述的代码改动，生成符合 Conventional Commits 规范的提交信息。

## 指令
请严格按以下格式输出一条提交信息：

1. 标题行格式：`<type>(<scope>): <subject>`
   - `type` 取值：feat（新功能）、fix（修复）、docs（文档）、refactor（重构）、test（测试）、chore（杂务）
   - `scope` 可选，表示影响范围（模块名）
   - `subject` 用中文祈使句，简洁描述做了什么，不超过 50 字，结尾不加句号
2. 空一行后写正文，用要点列出主要改动（每行以 `- ` 开头）。
3. 如果是破坏性变更，正文后加一行 `BREAKING CHANGE: <说明>`。

## 示例
输入：给登录接口加了图形验证码，修复了验证码不区分大小写的问题
输出：
```
feat(login): 登录接口增加图形验证码

- 新增图形验证码生成与校验
- 修复验证码大小写敏感导致的校验失败
```
