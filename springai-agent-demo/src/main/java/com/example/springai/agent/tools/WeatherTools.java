package com.example.springai.agent.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 模拟的“天气查询”工具。真实项目里这里会调用天气 API；为聚焦演示，这里返回假数据。
 */
public class WeatherTools {

    @Tool(description = "查询某个城市当前的天气情况")
    public String getWeather(@ToolParam(description = "城市名称，例如 北京") String city) {
        // 演示用：返回固定的假数据
        return switch (city) {
            case "北京" -> "北京：晴，26℃，微风";
            case "上海" -> "上海：多云，28℃，东南风";
            case "广州" -> "广州：雷阵雨，31℃，闷热";
            default -> city + "：晴，25℃（示例数据）";
        };
    }
}
