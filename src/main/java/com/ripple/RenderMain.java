package com.ripple;

import com.ripple.render.HtmlReportRenderer;
import com.ripple.render.PreviewServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * 渲染 CLI：--symbols NVDA,GLD,BTC-USD [--in work] [--out output/nvda-events.html] [--serve [--port 8080]]
 * 从 work/ 检查点生成单文件可交互 K 线 HTML（ECharts 本地打包、数据内嵌、断网可开）。
 */
public final class RenderMain {

    private static final Logger log = LoggerFactory.getLogger(RenderMain.class);

    public static void main(String[] args) {
        System.exit(run(args));
    }

    /**
     * 可内部调用的入口（返回退出码，不调 System.exit——run-all 编排复用）。
     */
    public static int run(String[] args) {
        Map<String, String> opts = parseArgs(args);
        var symbols = Arrays.stream(opts.getOrDefault("symbols", "NVDA,GLD,BTC-USD").split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
        Path workDir = Path.of(opts.getOrDefault("in", "work"));
        Path outFile = Path.of(opts.getOrDefault("out", "output/nvda-events.html"));
        boolean serve = opts.containsKey("serve");
        int port = Integer.parseInt(opts.getOrDefault("port", "8080"));

        try {
            var result = new HtmlReportRenderer().render(symbols, workDir, outFile);
            for (var r : result.reports()) {
                System.out.printf("标的 %s：%d 根 K 线（%s），归因 %d 条，事件缺失 %d 个%n",
                        r.symbol(), r.candles().size(), r.source(),
                        r.outcome().marks().size(), r.outcome().missing().size());
            }
            System.out.printf("产物: %s（%.1fMB，无外链，file:// 可直接打开）%n",
                    result.file(), result.bytes() / 1048576.0);
            if (serve) {
                PreviewServer.start(result.file(), port);
                Thread.currentThread().join();   // 阻塞直至 Ctrl+C
            }
            return 0;
        } catch (Exception e) {
            log.error("渲染失败: {}", e.getMessage(), e);
            return 1;
        }
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

    private RenderMain() {
    }
}
