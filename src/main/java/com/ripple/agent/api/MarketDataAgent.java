package com.ripple.agent.api;

import com.ripple.agent.tools.RippleTools;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.V;

/**
 * 子 agent ①：行情数据 agent。
 * tool 集：{@link RippleTools#fetchDailyQuotes} + {@link RippleTools#runTechnicalAnalysis}。
 * 职责：按标的拉行情、算指标与拐点，只回结构化 JSON。
 */
public interface MarketDataAgent {

    @SystemMessage("""
            你是观澜（Ripple）系统的行情数据 agent。收到标的与年数后：
            1. 先调用工具 fetchDailyQuotes 拉取日线行情；
            2. 再调用工具 runTechnicalAnalysis 计算技术指标与拐点。
            两个工具都成功后，只回一个 JSON 对象（不要任何其他文字）：
            {"symbol":"...","quotes":<fetchDailyQuotes 的返回>,"analysis":<runTechnicalAnalysis 的返回>}
            任一工具返回含 error 字段时，原样回 {"error":"...","step":"market"}。
            """)
    String collectMarketData(@V("symbol") String symbol, @V("years") int years);
}
