package com.ripple;

import com.ripple.agent.LlmModels;
import com.ripple.agent.RippleOrchestrator;
import com.ripple.agent.tools.RippleTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Agent 编排 CLI：--symbol NVDA --years 5 [--no-llm] [--refresh] [--orchestrate] [--verify]
 * 跑通 行情→指标→拐点→HN 事件→对齐 全流程，对齐结论落盘 work/alignments.json（schemaVersion 2）并打印。
 * --verify：不重跑流水线，只读 alignments.json 打印每条 EventMark 的来源 URL 供人工抽查。
 */
public final class AgentMain {

    private static final Logger log = LoggerFactory.getLogger(AgentMain.class);

    public static void main(String[] args) {
        System.exit(run(args));
    }

    /**
     * 可内部调用的入口（返回退出码，不调 System.exit——run-all 编排复用）。
     */
    public static int run(String[] args) {
        Map<String, String> opts = parseArgs(args);
        String symbol = opts.getOrDefault("symbol", "NVDA").toUpperCase(Locale.ROOT);
        int years = Integer.parseInt(opts.getOrDefault("years", "5"));
        boolean noLlm = opts.containsKey("no-llm");
        boolean refresh = opts.containsKey("refresh");
        boolean orchestrated = opts.containsKey("orchestrate");
        boolean verify = opts.containsKey("verify");
        Path workDir = Path.of(opts.getOrDefault("in", "work"));

        try {
            RippleTools toolkit = new RippleTools(workDir);
            RippleOrchestrator orchestrator = new RippleOrchestrator(toolkit);

            if (verify) {
                // 只读检查点，输出溯源抽查清单
                var outcome = toolkit.loadAlignmentsDoc();
                if (outcome.marks().isEmpty()) {
                    log.error("alignments.json 不存在或无归因结论，请先跑完整流水线");
                    return 2;
                }
                RippleOrchestrator.printVerification(outcome);
                return 0;
            }

            var outcome = orchestrated
                    ? runOrchestrated(toolkit, orchestrator, symbol, years, noLlm)
                    : orchestrator.runSequential(symbol, years, noLlm, refresh);
            if (outcome.marks().isEmpty() && outcome.missing().isEmpty()) {
                log.warn("对齐结果为空（候选新闻不足或全部被校验丢弃）");
                return 1;
            }
            RippleOrchestrator.printMarks(outcome, 8);
            log.info("对齐结论已落盘: {}", workDir.resolve("alignments.json"));
            return 0;
        } catch (Exception e) {
            log.error("编排失败: {}", e.getMessage(), e);
            return 1;
        }
    }

    /**
     * agent-as-tool 模式：主 agent 驱动子 agent 完成流水线，结论仍从检查点读回。
     */
    private static com.ripple.domain.AlignmentOutcome runOrchestrated(
            RippleTools toolkit, RippleOrchestrator orchestrator,
            String symbol, int years, boolean noLlm) throws Exception {
        if (noLlm || !LlmModels.available()) {
            throw new IllegalStateException("--orchestrate 需要 DEEPSEEK_API_KEY（且未指定 --no-llm）");
        }
        log.info("agent-as-tool 编排模式：主 agent 驱动 4 个子 agent");
        String summary = orchestrator.runOrchestrated(symbol, years);
        log.info("编排完成: {}", summary);
        return toolkit.loadAlignmentsDoc();
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> opts = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.startsWith("--")) {
                String key = a.substring(2);
                if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                    opts.put(key, args[++i]);
                } else {
                    opts.put(key, "true");
                }
            }
        }
        return opts;
    }

    private AgentMain() {
    }
}
