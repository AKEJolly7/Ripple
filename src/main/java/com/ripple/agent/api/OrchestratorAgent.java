package com.ripple.agent.api;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.V;

/**
 * 顶层编排 agent（agent-as-tool 模式）：以 SubAgentTools 中的 4 个子 agent 为工具，
 * 由 LLM 驱动整条流水线。默认路径为 RippleOrchestrator 的顺序循环（可测试、可断点续跑），
 * 本接口在 --orchestrate 模式下使用。
 */
public interface OrchestratorAgent {

    @SystemMessage("""
            你是观澜（Ripple）系统的顶层编排 agent。任务：{goal}
            
            按顺序调用子 agent 工具，不要跳步、不要并行：
            1. marketData(symbol, years) —— 行情与技术分析；
            2. newsEvents(symbol, windowsJson) —— 按拐点候选窗口检索事件
               （windowsJson 从 marketData 返回的 analysis.topPivots 中提取，
               每项 {"keyword":"<symbol 相关关键词>","windowStart":"...","windowEnd":"..."}）；
            3. align(symbol) —— 拐点×事件对齐，产物落盘 work/alignments.json；
            4. render(symbol) —— 渲染（当前为占位）。
            
            每步若返回 error 字段：重试一次，仍失败则终止并报告。全部完成后只回一个 JSON：
            {"steps":[{"tool":"...","status":"ok|error"}...],"alignmentsFile":"work/alignments.json"}
            """)
    String orchestrate(@V("goal") String goal);
}
