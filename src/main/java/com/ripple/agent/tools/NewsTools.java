package com.ripple.agent.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

/**
 * NewsEventAgent 的 tool 门面：只暴露 HN 窗口检索一个工具。
 * 去重与 relevance 打分是该 agent 的 LLM 判断职责，不设为工具。
 */
public final class NewsTools {

    private final RippleTools toolkit;

    public NewsTools(RippleTools toolkit) {
        this.toolkit = toolkit;
    }

    @Tool("按时间窗口检索 Hacker News 资讯（Algolia search_by_date）。"
            + "返回 JSON 数组，每条含 title/date/summary/url/source/externalId（objectID 为溯源标识）。")
    public String searchNewsByWindow(
            @P("关键词，如 Nvidia") String keyword,
            @P("窗口起点，yyyy-MM-dd，闭区间") String startDate,
            @P("窗口终点，yyyy-MM-dd，闭区间") String endDate) {
        return toolkit.searchNewsByWindow(keyword, startDate, endDate);
    }
}
