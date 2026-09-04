package com.ripple;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 观澜（Ripple）总入口：打包后 java -jar ripple-0.1.0.jar run-all 一键跑通。
 * run-all = 任务 A（NVDA 行情 × AI 事件归因 → output/nvda-events.html）
 * + 任务 B（黄金 vs 比特币 → artifacts/ 三件套）。
 * 全程 --no-llm 规则对齐（无需 DEEPSEEK_API_KEY）。
 */
public final class RippleApplication {

    public static void main(String[] args) {
        boolean noLlm = Arrays.stream(args).anyMatch(a -> a.equals("--no-llm"));
        String cmd = Arrays.stream(args).filter(a -> !a.startsWith("--"))
                .findFirst().orElse("help");

        try {
            switch (cmd.toLowerCase(Locale.ROOT)) {
                case "run-all", "all" -> runAll(noLlm);
                default -> printHelp();
            }
        } catch (Exception e) {
            System.err.println("失败: " + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(1);
        }
    }

    /**
     * 任务 A + 任务 B 全流程（单标的失败不中断全局，缺失标的从渲染中剔除）。
     */
    private static void runAll(boolean noLlm) throws Exception {
        System.out.println("========== 任务 A：NVDA 行情 × AI 事件归因（HTML） ==========");
        // NVDA 走 LLM 归因（key 存在且未指定 --no-llm 时；AgentMain 自身会在无 key 时降级）
        List<String> symbols = new java.util.ArrayList<>();
        runStep("NVDA 主流水线", symbols, AgentMain.run(noLlm
                ? new String[]{"--symbol", "NVDA", "--years", "5", "--no-llm"}
                : new String[]{"--symbol", "NVDA", "--years", "5"}), "NVDA");
        // 事件检索覆盖 GLD/BTC（HTML 三标的切换需要各自检查点）
        runStep("GLD 流水线", symbols, AgentMain.run(
                new String[]{"--symbol", "GLD", "--years", "5", "--no-llm"}), "GLD");
        runStep("BTC-USD 流水线", symbols, AgentMain.run(
                new String[]{"--symbol", "BTC-USD", "--years", "5", "--no-llm"}), "BTC-USD");
        if (symbols.isEmpty()) {
            throw new IllegalStateException("三个标的的流水线全部失败，无法渲染");
        }
        RenderMain.run(new String[]{"--symbols", String.join(",", symbols)});

        System.out.println();
        System.out.println("========== 任务 B：黄金 vs 比特币 三件套 ==========");
        BuildArtifactsMain.main(new String[]{});

        System.out.println();
        System.out.println("全部完成：output/nvda-events.html + artifacts/ 三件套");
    }

    /**
     * 步骤执行与容错：失败的标的记录并从渲染清单剔除，不中断全局。
     */
    private static void runStep(String label, List<String> symbols, int exitCode, String symbol) {
        if (exitCode == 0) {
            symbols.add(symbol);
        } else {
            System.err.println("⚠ " + label + " 失败（退出码 " + exitCode + "），产物中将不含 " + symbol);
        }
    }

    private static void printHelp() {
        System.out.println("""
                观澜（Ripple）—— 观其澜，知其源 / Watch the waves, find the stones
                用法: java -jar ripple-0.1.0.jar <run-all> [--no-llm]
                  run-all   一键全流程（任务 A HTML + 任务 B 三件套）
                分步 CLI（见 README）：DataFetchMain / AnalysisMain / AgentMain / RenderMain / BuildArtifactsMain""");
    }

    private RippleApplication() {
    }
}
