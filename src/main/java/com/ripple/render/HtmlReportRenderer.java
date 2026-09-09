package com.ripple.render;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ripple.AnalysisMain;
import com.ripple.analysis.Indicators;
import com.ripple.analysis.model.AnalysisConfig;
import com.ripple.domain.*;
import org.apache.logging.log4j.util.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 单文件 HTML 报告渲染器（R5）。
 * 产物特性：ECharts 与数据全部内嵌，无任何外链（file:// 双击可开、断网可开、无 CDN 供应链风险）；
 * 溯源面板由 Java 静态生成（可被单测解析断言），K 线交互（缩放/切换/筛选/tooltip 链接）由内嵌 JS 提供。
 */
public final class HtmlReportRenderer {

    private static final Logger log = LoggerFactory.getLogger(HtmlReportRenderer.class);

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    /**
     * 单个标的的全部渲染输入（均来自 work/ 检查点）。
     */
    public record SymbolReport(String symbol, String source, String alignMode,
                               List<Ohlcv> candles, List<Segment> segments,
                               AlignmentOutcome outcome) {
    }

    /**
     * 渲染结果摘要。
     */
    public record RenderResult(Path file, List<SymbolReport> reports, long bytes) {

        public int totalMarks() {
            return reports.stream().mapToInt(r -> r.outcome().marks().size()).sum();
        }
    }

    public RenderResult render(List<String> symbols, Path workDir, Path outFile) throws IOException {
        List<SymbolReport> reports = new ArrayList<>();
        for (String s : symbols) {
            String sym = s.toUpperCase(Locale.ROOT);
            try {
                reports.add(load(sym, workDir));
            } catch (java.nio.file.NoSuchFileException e) {
                log.warn("跳过 {}：缺少检查点 {}（请先跑 AgentMain --symbol {}）", sym, e.getFile(), sym);
            }
        }
        if (reports.isEmpty()) {
            throw new IllegalStateException("没有任何标的具备渲染输入（work/ 下缺少 ohlcv/alignments 检查点）");
        }

        String template = readResource("/templates/report.html");
        String echarts = readResource("/static/echarts.min.js");

        Map<String, Object> dataMap = new LinkedHashMap<>();
        dataMap.put("defaultSymbol", reports.get(0).symbol());
        Map<String, Object> symMap = new LinkedHashMap<>();
        for (SymbolReport r : reports) {
            symMap.put(r.symbol(), dataOf(r));
        }
        dataMap.put("symbols", symMap);
        // JSON 内嵌 <script> 上下文：转义 "</" 防止标题中出现 </script> 截断页面
        String dataJson = mapper.writeValueAsString(dataMap).replace("</", "<\\/");

        String defaultSym = reports.get(0).symbol();
        String panel = reports.stream()
                .map(r -> panelSection(r, r.symbol().equals(defaultSym)))
                .collect(Collectors.joining("\n"));
        String footer = footerOf(reports);

        String html = template
                .replace("__ECHARTS_JS__", echarts)
                .replace("__DATA_JSON__", dataJson)
                .replace("__PANEL_SECTIONS__", panel)
                .replace("__FOOTER__", footer);
        for (String token : List.of("__ECHARTS_JS__", "__DATA_JSON__", "__PANEL_SECTIONS__", "__FOOTER__")) {
            if (html.contains(token)) {
                throw new IllegalStateException("模板占位符未替换: " + token);
            }
        }

        Path parent = outFile.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(outFile, html, StandardCharsets.UTF_8);
        log.info("HTML 报告已生成: {}（{}，{} 字节，{} 个标的，归因 {} 条）",
                outFile, formatBytes(html.length()), html.getBytes(StandardCharsets.UTF_8).length,
                reports.size(), reports.stream().mapToInt(r -> r.outcome().marks().size()).sum());
        return new RenderResult(outFile, List.copyOf(reports), html.getBytes(StandardCharsets.UTF_8).length);
    }

    // ------------------------------------------------------------------
    // 加载
    // ------------------------------------------------------------------

    private SymbolReport load(String symbol, Path workDir) throws IOException {
        AnalysisMain.JsonDoc doc = mapper.readValue(
                workDir.resolve(symbol + "_ohlcv.json").toFile(), AnalysisMain.JsonDoc.class);
        List<Segment> segments = List.of();
        if (Files.exists(workDir.resolve(symbol + "_analysis.json"))) {
            Map<String, Object> analysis = mapper.readValue(
                    workDir.resolve(symbol + "_analysis.json").toFile(),
                    new TypeReference<>() {
                    }
            );
            if (analysis.get("segments") instanceof List<?> l) {
                segments = mapper.convertValue(l, mapper.getTypeFactory()
                        .constructCollectionType(List.class, Segment.class));
            }
        }
        AlignmentOutcome outcome;
        try {
            outcome = new com.ripple.agent.tools.RippleTools(workDir).loadAlignmentsFor(symbol);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("读取 " + symbol + " 对齐检查点失败: " + e.getMessage(), e);
        }
        return new SymbolReport(symbol, doc.source(), "rule", doc.candles(), segments, outcome);
    }

    // ------------------------------------------------------------------
    // 内嵌数据
    // ------------------------------------------------------------------

    private Map<String, Object> dataOf(SymbolReport r) {
        AnalysisConfig cfg = AnalysisConfig.load();
        List<Ohlcv> candles = r.candles();
        int n = candles.size();
        double[] close = new double[n];
        List<String> dates = new ArrayList<>(n);
        List<double[]> ohlc = new ArrayList<>(n);
        List<Long> volume = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Ohlcv c = candles.get(i);
            close[i] = c.close();
            dates.add(c.date().toString());
            ohlc.add(new double[]{c.open(), c.high(), c.low(), c.close()});
            volume.add(c.volume());
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("source", r.source());
        m.put("dates", dates);
        m.put("ohlc", ohlc);
        m.put("volume", volume);
        m.put("ma5", nullsForNan(Indicators.sma(close, cfg.ma5Period())));
        m.put("ma20", nullsForNan(Indicators.sma(close, cfg.ma20Period())));
        m.put("ma60", nullsForNan(Indicators.sma(close, cfg.ma60Period())));
        m.put("segments", r.segments());
        m.put("marks", r.outcome().marks());
        m.put("missingCount", r.outcome().missing().size());
        return m;
    }

    private static Double[] nullsForNan(double[] a) {
        Double[] out = new Double[a.length];
        for (int i = 0; i < a.length; i++) {
            out[i] = Double.isNaN(a[i]) ? null : a[i];
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 静态溯源面板（Java 生成，供单测解析断言）
    // ------------------------------------------------------------------

    private String panelSection(SymbolReport r, boolean isDefault) {
        StringBuilder sb = new StringBuilder();
        sb.append("<section class=\"panelSym\" data-symbol=\"").append(htmlEsc(r.symbol())).append("\"")
                .append(isDefault ? "" : " hidden").append(">");
        for (EventMark m : r.outcome().marks()) {
            sb.append("<div class=\"mark\" data-rating=\"").append(m.rating()).append("\">");
            sb.append("<div class=\"meta\">");
            sb.append("<span class=\"mdate\">").append(m.pivotDate()).append("</span>");
            sb.append("<span class=\"rating rating-").append(m.rating()).append("\">")
                    .append(m.rating().label()).append("</span>");
            sb.append("<span>").append(m.strength().label()).append(' ')
                    .append(fmt2(m.correlation())).append("</span>");
            sb.append("<span>置信度 ").append(fmt2(m.confidence())).append("</span>");
            if (m.url() != null && m.url().startsWith("http")) {
                sb.append("<a href=\"").append(htmlEsc(m.url()))
                        .append("\" target=\"_blank\" rel=\"noopener\">来源 ↗</a>");
            }
            sb.append("</div>");
            sb.append("<div class=\"mtitle\">").append(htmlEsc(m.eventTitle())).append("</div>");
            sb.append("<div class=\"mreason\">").append(htmlEsc(m.reasoning())).append("</div>");
            sb.append("</div>");
        }
        for (MissingEvent miss : r.outcome().missing()) {
            sb.append("<div class=\"miss\">⚠ ").append(miss.pivotDate()).append(' ')
                    .append(miss.pivotType().label()).append(' ')
                    .append(String.format(Locale.ROOT, "%+.2f%%", miss.dayChangePct()))
                    .append("：事件缺失，同期候选：");
            if (miss.candidates().isEmpty()) {
                sb.append("（候选窗口内无新闻）");
            }
            for (NewsItem c : miss.candidates()) {
                if (!Strings.isBlank(c.url()) && c.url().startsWith("http")) {
                    sb.append(" <a href=\"").append(htmlEsc(c.url()))
                            .append("\" target=\"_blank\" rel=\"noopener\">")
                            .append(htmlEsc(truncate(c.title(), 40))).append("</a>");
                }
            }
            sb.append("</div>");
        }
        sb.append("</section>");
        return sb.toString();
    }

    private String footerOf(List<SymbolReport> reports) {
        // 只保留数据口径与来源（可溯源必需），不含生成时间等过程性信息
        return reports.stream().map(r -> "%s：%s，%d 根日 K（%s ~ %s），归因 %d 条、事件缺失 %d".formatted(
                        r.symbol(), r.source(), r.candles().size(),
                        r.candles().isEmpty() ? "-" : r.candles().get(0).date(),
                        r.candles().isEmpty() ? "-" : r.candles().get(r.candles().size() - 1).date(),
                        r.outcome().marks().size(), r.outcome().missing().size()))
                .collect(Collectors.joining(" · "))
                + " · ECharts 本地打包（无 CDN），数据与结论内嵌，断网可用";
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private static String readResource(String path) throws IOException {
        try (InputStream in = HtmlReportRenderer.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("classpath 资源缺失: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String htmlEsc(String s) {
        if (Strings.isBlank(s)) {
            return Strings.EMPTY;
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String truncate(String s, int max) {
        return !Strings.isBlank(s) && s.length() > max ? s.substring(0, max) + "…" : s;
    }

    private static String fmt2(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    private static String formatBytes(int n) {
        return n >= 1 << 20 ? String.format(Locale.ROOT, "%.1fMB", n / 1048576.0)
                : String.format(Locale.ROOT, "%.0fKB", n / 1024.0);
    }
}
