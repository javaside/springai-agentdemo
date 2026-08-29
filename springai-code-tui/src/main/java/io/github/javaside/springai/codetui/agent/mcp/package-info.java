/**
 * MCP（Model Context Protocol）客户端域：{@code McpRegistry}（注册/启停/工具快照）、
 * {@code McpClientManager}（连接生命周期）、配置读写（{@code mcp.json}，支持 {@code ${VAR}}
 * 插值——token 留在环境变量，配置文件只写引用）、传输工厂（stdio / Streamable HTTP）。
 *
 * <p><b>启动期并行连接</b>：{@code init} 不等连完就返回，TUI 立刻渲染；每回合快照
 * {@code activeTools()}，连上即生效。{@code decorate} 给 MCP 工具套上与内置工具相同的
 * 装饰链（事件/权限/媒体外置）。
 *
 * <p><b>依赖方向</b>：依赖 permission（审批规则）/seam（AgentListener）/media/tools
 * （装饰链）以及 agent 装配层的 {@code AgentTools.testEngine} 测试工厂——最后这条是
 * {@code agent ↔ agent.mcp} 已知包循环的成因，列入重构遗留清理项。另有
 * {@code McpClientManager} 取模块根的 {@code AppInfo} 版本号作 MCP 客户端标识，构成
 * {@code codetui ↔ agent.mcp} 第二条环（同属遗留清理项，改法是由装配层把版本号当参数传入）。
 */
package io.github.javaside.springai.codetui.agent.mcp;
