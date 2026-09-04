package com.ripple.agent.tools;

import com.ripple.agent.api.CorrelationAnalyst;
import com.ripple.agent.api.MarketDataAgent;
import com.ripple.agent.api.NewsEventAgent;
import com.ripple.agent.api.RenderAgent;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

/**
 * agent-as-tool：把 4 个子 agent 封装为 @Tool，供顶层 OrchestratorAgent（主 agent）调用。
 * 主 agent 只见 4 个语义化工具（marketData/newsEvents/align/render），不见底层实现——
 * 分支决策（哪步失败要不要重试、窗口没新闻要不要扩大）交给 LLM 按运行时内容判断。
 */
public class SubAgentTools {

    private final MarketDataAgent marketDataAgent;
    private final NewsEventAgent newsEventAgent;
    private final CorrelationAnalyst correlationAnalyst;
    private final RenderAgent renderAgent;

    public SubAgentTools(MarketDataAgent marketDataAgent, NewsEventAgent newsEventAgent,
                         CorrelationAnalyst correlationAnalyst, RenderAgent renderAgent) {
        this.marketDataAgent = marketDataAgent;
        this.newsEventAgent = newsEventAgent;
        this.correlationAnalyst = correlationAnalyst;
        this.renderAgent = renderAgent;
    }

    @Tool("行情数据子agent：拉取标的行情并计算技术指标（MA/RSI/拐点/趋势段），中间结果落盘 work/。"
            + "返回 JSON：{symbol, quotes:{...}, analysis:{...}}")
    public String marketData(
            @P("标的符号，如 NVDA") String symbol,
            @P("回看年数，如 5") int years) {
        return marketDataAgent.collectMarketData(symbol, years);
    }

    @Tool("事件检索子agent：按候选窗口检索 Hacker News 新闻，按 URL 去重并打相关性分。"
            + "返回 JSON 数组，元素含 title/date/url/relevance。"
            + "参数 windowsJson 是数组，每个元素 {keyword, windowStart, windowEnd}")
    public String newsEvents(
            @P("标的符号") String symbol,
            @P("候选窗口 JSON 数组，每项含 keyword/windowStart/windowEnd") String windowsJson) {
        return newsEventAgent.collectEvents(symbol, windowsJson);
    }

    @Tool("关联分析子agent：把拐点与事件对齐，产出 EventMark JSON 数组"
            + "（含影响评级/关联强度/置信度/来源URL/推理），并落盘 work/alignments.json")
    public String align(@P("标的符号") String symbol) {
        return correlationAnalyst.align(symbol);
    }

    @Tool("渲染子agent：生成可视化与文档产物。R5/R6 实现前返回 {\"status\":\"not-implemented\"}")
    public String render(@P("标的符号") String symbol) {
        return renderAgent.render(symbol);
    }
}
