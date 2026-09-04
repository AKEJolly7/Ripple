package com.ripple.agent.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

/**
 * MarketDataAgent 的 tool 门面：只暴露行情与分析两个工具，
 * 在 tool 层强制该 agent 的能力边界（其余工具不可见，prompt 之外的第二道约束）。
 */
public final class MarketDataTools {

    private final RippleTools toolkit;

    public MarketDataTools(RippleTools toolkit) {
        this.toolkit = toolkit;
    }

    @Tool("拉取指定标的的日线行情（OHLCV，前复权，含成交量）。中间结果落盘 work/{symbol}_ohlcv.json，"
            + "已存在则直接复用（断点续跑）。返回 JSON：{symbol, source, count, firstDate, lastDate, file}")
    public String fetchDailyQuotes(
            @P("标的符号，如 NVDA、GLD、BTC-USD") String symbol,
            @P("回看年数，如 5") int years) {
        return toolkit.fetchDailyQuotes(symbol, years);
    }

    @Tool("对已落盘行情运行技术分析（MA5/20/60、RSI14、20日波动率、异常放量、拐点检测、趋势段划分）。"
            + "完整结果落盘 work/{symbol}_analysis.json（已存在则复用）。"
            + "返回 JSON 摘要：{topPivots:[{date,type,dayChangePct,volumeSpike,windowStart,windowEnd}...]}")
    public String runTechnicalAnalysis(@P("标的符号，须已运行过 fetchDailyQuotes") String symbol) {
        return toolkit.runTechnicalAnalysis(symbol);
    }
}
