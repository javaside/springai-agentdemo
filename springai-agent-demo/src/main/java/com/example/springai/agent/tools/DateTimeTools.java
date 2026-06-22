package com.example.springai.agent.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * 一组“工具方法”，供模型在需要时调用（Function/Tool Calling）。
 *
 * <p>用 {@code @Tool} 标注方法、{@code @ToolParam} 描述参数，Spring AI 会把这些说明发给模型；
 * 模型判断需要时会“请求调用”某个方法，Spring AI 自动执行并把结果回传给模型。
 * 关键点：description 要写清楚，模型据此决定何时调用、怎么传参。
 */
public class DateTimeTools {

    /** 模型本身不知道“现在几点”，必须靠工具获取实时信息。 */
    @Tool(description = "获取当前的日期和时间")
    public String getCurrentDateTime() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    /** 让模型把“算天数”这种确定性计算交给代码，而不是自己心算（更可靠）。 */
    @Tool(description = "计算两个日期之间相差的天数")
    public long daysBetween(
            @ToolParam(description = "起始日期，格式 yyyy-MM-dd") String start,
            @ToolParam(description = "结束日期，格式 yyyy-MM-dd") String end) {
        LocalDate s = LocalDate.parse(start);
        LocalDate e = LocalDate.parse(end);
        return ChronoUnit.DAYS.between(s, e);
    }
}
