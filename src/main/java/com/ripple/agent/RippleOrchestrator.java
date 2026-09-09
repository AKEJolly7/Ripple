package com.ripple.agent;

import com.ripple.agent.api.*;
import com.ripple.agent.tools.*;
import com.ripple.domain.EventCandidates;
import com.ripple.domain.EventMark;
import dev.langchain4j.service.AiServices;
import org.apache.logging.log4j.util.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.List;

/**
 * 编排入口：两条路径共享同一套 tools 层与 work/ 检查点。
 * <p>
 * 【为什么默认用顺序循环，agent-as-tool 作为增强】
 * 本流水线（行情→分析→事件→对齐）步骤固定、每步产物落盘可校验，顺序循环：
 * ① 可单测——每步是纯函数式步骤，mock toolkit 即可测全链；
 * ② 可断点续跑——检查点文件即状态，重跑天然幂等；
 * ③ 无 LLM 也能跑（--no-llm 降级）。
 * agent-as-tool（OrchestratorAgent + SubAgentTools，--orchestrate 模式）保留给
 * 分支依赖运行时内容的场景：如某窗口检索为空时要不要换关键词扩大窗口、
 * 对齐置信度普遍偏低时要不要补充检索——这些"要不要"的判断交给主 agent 的 LLM。
 * <p>
 * 【为什么不用 DAG 引擎 / 何时演进到 LangGraph4j】
 * langchain4j 无原生 DAG 编排，自建 DAG 引擎需要图定义/拓扑排序/节点状态机三套代码，
 * 而本任务五步线性依赖，DAG 是过度设计。演进触发条件（满足其一）：
 * ① 步骤间出现条件分支与环（如"对齐置信度低→回到事件检索补充证据"的循环）；
 * ② 需要并行扇出（多标的、多数据源并发拉取）与人审暂停点（LangGraph4j 的interrupt/checkpoint 语义）；
 * ③ 步骤数增长到需要图结构做可视化与影响分析。
 * 到那时把顺序循环的每一步映射为 graph 节点、检查点映射为 state，迁移路径是平滑的。
 */
public final class RippleOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(RippleOrchestrator.class);

    private final RippleTools toolkit;

    public RippleOrchestrator(RippleTools toolkit) {
        this.toolkit = toolkit;
    }

    /**
     * 顺序循环编排（默认路径；LLM 仅在“对齐”一步介入，无 key 时规则对齐）。
     */
    public com.ripple.domain.AlignmentOutcome runSequential(String symbol, int years,
                                                            boolean noLlm, boolean refresh)
            throws Exception {
        // Step 1 行情（toolkit 内部断点续跑：work/{symbol}_ohlcv.json 存在即复用）
        String quotes = toolkit.fetchDailyQuotes(symbol, years);
        log.info("[Step1 行情] {}", quotes);

        // Step 2 技术分析（同理复用 work/{symbol}_analysis.json）
        String analysis = toolkit.runTechnicalAnalysis(symbol);
        log.info("[Step2 分析] {}", analysis);

        // Step 3 事件收集（确定性：按拐点窗口检索 HN + URL 去重）
        List<EventCandidates> candidates = toolkit.collectEvents(symbol, refresh);
        int newsCount = candidates.stream().mapToInt(c -> c.news().size()).sum();
        log.info("[Step3 事件] {} 个拐点窗口，共 {} 条候选新闻", candidates.size(), newsCount);

        // Step 4 对齐：LLM（CorrelationAnalyst）或规则（--no-llm）
        boolean useLlm = !noLlm && LlmModels.available();
        com.ripple.domain.AlignmentOutcome outcome;
        if (useLlm) {
            outcome = new LlmAligner().align(candidates);
            if (outcome.marks().isEmpty() && !candidates.isEmpty()) {
                // 兑现 LlmAligner 的承诺：LLM 整体不可用（key 失效/网络不通/解析全败）时
                // 降级为规则对齐，而非带着 0 归因和 llm 标记落盘
                log.warn("LLM 归因为空（不可用或全被校验丢弃），降级为规则对齐");
                outcome = new RuleBasedAligner().align(candidates);
                useLlm = false;
            } else {
                log.info("[Step4 对齐] LLM 产出：归因 {} 条，事件缺失 {} 个",
                        outcome.marks().size(), outcome.missing().size());
            }
        } else {
            if (!noLlm) {
                log.warn("DEEPSEEK_API_KEY 未设置，降级为规则对齐（--no-llm 等价）");
            }
            outcome = new RuleBasedAligner().align(candidates);
            log.info("[Step4 对齐] 规则模式：归因 {} 条，事件缺失 {} 个",
                    outcome.marks().size(), outcome.missing().size());
        }
        toolkit.saveAlignments(symbol, useLlm ? "llm" : "rule",
                outcome.marks(), outcome.missing());
        return outcome;
    }

    /**
     * agent-as-tool 编排（--orchestrate，需 DEEPSEEK_API_KEY）。
     */
    public String runOrchestrated(String symbol, int years) {
        MarketDataAgent marketDataAgent = AiServices.builder(MarketDataAgent.class)
                .chatModel(LlmModels.deepseek()).tools(new MarketDataTools(toolkit)).build();
        NewsEventAgent newsEventAgent = AiServices.builder(NewsEventAgent.class)
                .chatModel(LlmModels.deepseek()).tools(new NewsTools(toolkit)).build();
        CorrelationAnalyst correlationAnalyst = AiServices.builder(CorrelationAnalyst.class)
                .chatModel(LlmModels.deepseek()).tools(new AlignmentTools(toolkit)).build();
        RenderAgent renderAgent = AiServices.builder(RenderAgent.class)
                .chatModel(LlmModels.deepseek()).build();   // 渲染占位，无工具

        OrchestratorAgent master = AiServices.builder(OrchestratorAgent.class)
                .chatModel(LlmModels.deepseek())
                .tools(new SubAgentTools(marketDataAgent, newsEventAgent,
                        correlationAnalyst, renderAgent))
                .build();

        String goal = ("对 %s 做近 %d 年行情×AI 行业事件归因，四步流水线全跑通，"
                + "对齐结论落盘 work/alignments.json").formatted(symbol, years);
        return master.orchestrate(goal);
    }

    /**
     * 打印前 N 条对齐结论与事件缺失统计（验收输出：每条含来源 URL 与摘要）。
     */
    public static void printMarks(com.ripple.domain.AlignmentOutcome outcome, int limit) {
        List<EventMark> marks = outcome.marks();
        List<EventMark> top = marks.stream()
                .sorted(Comparator.comparingDouble(EventMark::confidence).reversed())
                .limit(limit)
                .toList();
        System.out.printf("%n--- 对齐结论（打印 %d / 归因 %d / 事件缺失 %d，按置信度降序） ---%n",
                top.size(), marks.size(), outcome.missing().size());
        int i = 1;
        for (EventMark m : top) {
            System.out.printf("%d. [%s|%s|关联度%.2f|置信度%.2f] 拐点 %s 当日 %+.2f%%%s%n",
                    i++, m.rating().label(), m.strength().label(), m.correlation(), m.confidence(),
                    m.pivotType().label(), m.dayChangePct(),
                    m.volumeSpike() ? "（放量）" : "");
            System.out.printf("   拐点日: %s | 事件日: %s%n   事件: %s%n   摘要: %s%n   来源: %s%n   推理: %s%n",
                    m.pivotDate(), m.eventDate() == null ? "未知" : m.eventDate(),
                    truncate(m.eventTitle(), 80), truncate(m.summary(), 100),
                    m.url(), truncate(m.reasoning(), 120));
        }
        if (!outcome.missing().isEmpty()) {
            System.out.printf("%n--- 事件缺失（%d 个拐点未找到强相关事件，仅列同期候选，不做归因） ---%n",
                    outcome.missing().size());
            for (var miss : outcome.missing()) {
                System.out.printf("· %s %s 当日 %+.2f%%%s → 候选:%n",
                        miss.pivotDate(), miss.pivotType().label(), miss.dayChangePct(),
                        miss.volumeSpike() ? "（放量）" : "");
                for (var c : miss.candidates()) {
                    System.out.printf("    - %s（%s）%s%n", truncate(c.title(), 70),
                            c.date() == null ? "日期未知" : c.date(), c.url());
                }
            }
        }
    }

    /**
     * --verify 模式：逐条打印 EventMark 的来源 URL 供人工抽查。
     */
    public static void printVerification(com.ripple.domain.AlignmentOutcome outcome) {
        List<EventMark> marks = outcome.marks();
        System.out.printf("%n--- 溯源抽查清单（%d 条归因结论，逐条含 URL，供人工核验） ---%n", marks.size());
        int i = 1;
        for (EventMark m : marks) {
            System.out.printf("%d. 拐点 %s（%+.2f%%）%s ← %s%n   %s%n",
                    i++, m.pivotDate(), m.dayChangePct(),
                    "[" + m.rating().label() + "|" + m.strength().label() + "|关联度"
                            + "%.2f".formatted(m.correlation()) + "]",
                    truncate(m.eventTitle(), 70), m.url());
        }
        System.out.printf("%n（另有 %d 个事件缺失拐点，见 alignments.json 的 missing 数组）%n",
                outcome.missing().size());
    }

    private static String truncate(String s, int max) {
        return !Strings.isBlank(s) && s.length() > max ? s.substring(0, max) + "…" : s;
    }
}
