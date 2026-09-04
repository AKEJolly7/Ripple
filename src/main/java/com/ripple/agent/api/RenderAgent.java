package com.ripple.agent.api;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.V;

/**
 * 子 agent ④：渲染 agent —— 接口占位，R5（NVDA 单文件 HTML）/ R6（黄金 vs 比特币 Excel/PPT/Word）实现。
 * 届时 tool 集预计为：加载 alignments.json / 加载回测结果 / 写出产物文件。
 */
public interface RenderAgent {

    @SystemMessage("""
            你是观澜（Ripple）系统的渲染 agent。当前为占位实现（R5 实现 K 线事件标注 HTML，
            R6 实现 Excel/PPT/Word 三文档）。收到调用时只回：
            {"status":"not-implemented","milestone":"R5/R6"}
            """)
    String render(@V("symbol") String symbol);
}
