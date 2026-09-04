package com.ripple.agent.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

/**
 * CorrelationAnalyst 的 tool 门面：只暴露对齐输入加载一个工具——
 * 归因 agent 只能读检查点证据，不能自行拉数据/改行情，证据集合封闭可校验。
 */
public final class AlignmentTools {

    private final RippleTools toolkit;

    public AlignmentTools(RippleTools toolkit) {
        this.toolkit = toolkit;
    }

    @Tool("加载对齐输入（来自 work/ 检查点）：候选拐点及其检索到的新闻。"
            + "返回 JSON 数组，每个元素 {pivot:{...}, news:[...]}。")
    public String loadAlignmentInputs(@P("标的符号，须已完成行情/分析/事件三步") String symbol) {
        return toolkit.loadAlignmentInputs(symbol);
    }
}
