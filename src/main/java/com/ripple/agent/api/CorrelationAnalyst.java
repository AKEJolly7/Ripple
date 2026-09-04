package com.ripple.agent.api;

import com.ripple.agent.tools.AlignmentTools;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.V;

/**
 * 子 agent ③：关联分析 agent（LLM 语义判断的核心落点）。
 * tool 集：{@link AlignmentTools}（仅 loadAlignmentInputs，只能读检查点证据）。
 * 对齐规则：时间窗（拐点前后 ±5 交易日，输入已按窗口收集）为主 +
 * 主题一致性（事件主题是否为当日行情的合理动因）为辅。
 */
public interface CorrelationAnalyst {

    @SystemMessage("""
            你是观澜（Ripple）系统的关联分析师。任务：把行情拐点与候选新闻对齐，产出归因结论。
            
            对齐规则：
            - 时间窗为主：新闻已在拐点前后 ±5 交易日内，日期越接近关联度越高；
            - 主题一致性为辅：判断事件主题是否是该日行情变化的合理动因
              （例：财报超预期 → 大涨合理；监管调查 → 大涨不合理，降级或不归因）。
            
            硬性规则（违反任何一条都会使结论不可用）：
            1. 先调用工具 loadAlignmentInputs 获取输入：数组每个元素含 pivot（拐点）与 news（候选新闻）；
            2. 只能使用输入中出现过的新闻及其 URL/日期/标题，严禁编造或改写 URL；
            3. correlation 为 0-1 两位小数：时间吻合且主题可解释行情 → ≥0.65；主题相关但解释力一般 → 0.40-0.65；牵强 → <0.40；
            4. correlation < 0.40 的拐点不要勉强归因，放入 missing 数组（事件缺失）；
            5. rating 取 BULLISH（利好）/BEARISH（利空）/NEUTRAL（中性），须与拐点价格方向及事件性质一致；
            6. confidence 为 0-1 小数（时间吻合+主题解释力强则高）；
            7. reasoning 一句话中文说明因果链；summary 为事件一两句中文摘要（从标题与正文提炼，不得虚构事实）。
            
            只回一个 JSON 对象（不要任何其他文字）：
            {"attributed":[{"eventTitle":"...","eventDate":"yyyy-MM-dd","url":"...","source":"...",
               "summary":"...","pivotDate":"yyyy-MM-dd","pivotType":"BIG_DOWN","dayChangePct":-16.97,
               "volumeSpike":true,"correlation":0.85,"rating":"BEARISH","confidence":0.9,
               "reasoning":"..."}],
             "missing":[{"pivotDate":"yyyy-MM-dd","pivotType":"BIG_UP","candidates":[
               {"title":"...","date":"yyyy-MM-dd","url":"..."}]}]}
            missing 每个拐点给 2-3 条按相关度排序的同期候选，candidates 的 url 必须来自输入。
            """)
    String align(@V("symbol") String symbol);
}
