package com.example.springai.agent.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 一组“数量较多”的演示工具，专门用来配合工具搜索（动态工具发现）示例。
 *
 * <p>当工具很多时，把全部定义一次性塞给模型会浪费 token。ToolSearchToolCallingAdvisor 会让模型
 * 先按自然语言“搜索”需要的工具，再只把命中的工具交给它调用。description 写清楚，搜索才准。
 * 这里的方法都返回演示用的假数据，重点在“工具被正确发现并调用”。
 */
public class OfficeTools {

    @Tool(description = "查询某位员工的剩余年假天数")
    public String queryAnnualLeave(@ToolParam(description = "员工姓名") String name) {
        return name + " 当前剩余年假 7 天。";
    }

    @Tool(description = "提交一张请假申请单")
    public String submitLeaveRequest(
            @ToolParam(description = "员工姓名") String name,
            @ToolParam(description = "请假天数") int days) {
        return "已为 " + name + " 提交 " + days + " 天请假申请，单号 LR-20260623。";
    }

    @Tool(description = "查询指定会议室在某天是否空闲")
    public String checkMeetingRoom(
            @ToolParam(description = "会议室名称，如 301") String room,
            @ToolParam(description = "日期，格式 yyyy-MM-dd") String date) {
        return "会议室 " + room + " 在 " + date + " 上午空闲、下午已被预订。";
    }

    @Tool(description = "查询某城市的快递网点电话")
    public String queryCourierPhone(@ToolParam(description = "城市名称") String city) {
        return city + " 快递网点电话：400-123-4567。";
    }

    @Tool(description = "把一段文本翻译成英文")
    public String translateToEnglish(@ToolParam(description = "要翻译的中文文本") String text) {
        return "[EN] " + text;
    }

    @Tool(description = "计算两个整数的乘积")
    public long multiply(
            @ToolParam(description = "第一个整数") long a,
            @ToolParam(description = "第二个整数") long b) {
        return a * b;
    }
}
