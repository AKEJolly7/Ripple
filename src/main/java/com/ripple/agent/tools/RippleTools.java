package com.ripple.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ripple.AnalysisMain;
import com.ripple.DataFetchMain;
import com.ripple.analysis.PivotDetector;
import com.ripple.analysis.model.AnalysisConfig;
import com.ripple.analysis.model.AnalysisResult;
import com.ripple.dataprovider.HackerNewsProvider;
import com.ripple.domain.*;
import com.ripple.domain.enums.PivotType;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.logging.log4j.util.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;

/**
 * tools 层：R1 数据通道（MarketDataProvider/NewsProvider 的具体实现）与 R2 分析技能
 * （PivotDetector）的统一实现与 work/ 检查点管理（已存在则复用，保证断点续跑）。
 * 顺序编排直接调用本类的 public 方法；LLM 子 agent 经门面类（MarketDataTools/NewsTools/
 * AlignmentTools，各只暴露该 agent 职责内的 @Tool）访问，工具可见性按 agent 边界收敛。
 */
public class RippleTools {

    private static final Logger log = LoggerFactory.getLogger(RippleTools.class);

    /**
     * 无显式关键词时的默认检索词。
     */
    private static final Map<String, String> KEYWORDS = Map.of(
            "NVDA", "Nvidia", "GLD", "gold price", "BTC-USD", "Bitcoin");

    /**
     * 对齐拐点选择：每季度按显著性取前 3（时间全覆盖，避免高波动期挤占整个候选集），
     * 全局上限 48（控制 HN 检索次数与 LLM 输入体积）。
     */
    private static final int PER_QUARTER_PIVOTS = 3;
    private static final int MAX_ALIGNMENT_PIVOTS = 48;

    private final Path workDir;
    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public RippleTools(Path workDir) {
        this.workDir = workDir;
    }

    public Path workDir() {
        return workDir;
    }

    // ------------------------------------------------------------------
    // 数据/分析步骤实现（供顺序编排直接调用；@Tool 门面见 MarketDataTools 等，
    // 按 subagent 划分工具可见性，避免每个 agent 看到全部工具）
    // ------------------------------------------------------------------

    /**
     * 拉取日线行情，落盘检查点。返回 JSON：{symbol, source, count, firstDate, lastDate, file}
     */
    public String fetchDailyQuotes(String symbol, int years) {
        try {
            symbol = symbol.toUpperCase();
            Path file = ohlcvFile(symbol);
            if (Files.exists(file)) {
                return summaryJson(symbol, file);
            }
            LocalDate end = LocalDate.now();
            LocalDate start = end.minusYears(Math.max(1, years));
            DataFetchMain.MarketData md = DataFetchMain.fetchDailyWithFallback(symbol, start, end);
            Files.createDirectories(workDir);
            Map<String, Object> doc = new HashMap<>();
            doc.put("symbol", symbol);
            doc.put("source", md.source());
            doc.put("windowStart", start);
            doc.put("windowEnd", end);
            doc.put("candles", md.candles());
            mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), doc);
            log.info("行情落盘: {}（{} 根，来源 {}）", file, md.candles().size(), md.source());
            return summaryJson(symbol, file);
        } catch (Exception e) {
            return errorJson("fetchDailyQuotes 失败: " + e.getMessage());
        }
    }

    /**
     * 技术分析（MA/RSI/拐点/趋势段），落盘检查点。返回 {topPivots:[...]}
     */
    public String runTechnicalAnalysis(String symbol) {
        try {
            symbol = symbol.toUpperCase();
            Path file = analysisFile(symbol);
            if (Files.exists(file)) {
                return pivotSummaryJson(selectedPivots(symbol));
            }
            List<Ohlcv> candles = AnalysisMain.loadCandles(ohlcvFile(symbol));
            AnalysisResult result = new PivotDetector().analyze(candles, AnalysisConfig.load());
            List<InflectionPoint> top = selectAlignmentPivots(result.pivots());
            Map<String, Object> doc = new HashMap<>();
            doc.put("symbol", symbol);
            doc.put("generatedAt", LocalDate.now().toString());
            doc.put("pivotCount", result.pivots().size());
            doc.put("segmentCount", result.segments().size());
            doc.put("pivots", top);
            doc.put("segments", result.segments());
            mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), doc);
            log.info("分析落盘: {}（拐点 {}，趋势段 {}）", file, result.pivots().size(),
                    result.segments().size());
            return pivotSummaryJson(top);
        } catch (Exception e) {
            return errorJson("runTechnicalAnalysis 失败: " + e.getMessage());
        }
    }

    /**
     * 按时间窗检索 HN 资讯。返回 NewsItem JSON 数组。
     */
    public String searchNewsByWindow(String keyword, String startDate, String endDate) {
        try {
            List<NewsItem> items = new HackerNewsProvider().search(
                    keyword, LocalDate.parse(startDate), LocalDate.parse(endDate));
            return mapper.writeValueAsString(items);
        } catch (Exception e) {
            return errorJson("searchNewsByWindow 失败: " + e.getMessage());
        }
    }

    /**
     * 加载对齐输入检查点。返回 EventCandidates JSON 数组。
     */
    public String loadAlignmentInputs(String symbol) {
        try {
            return mapper.writeValueAsString(loadCandidates(symbol));
        } catch (Exception e) {
            return errorJson("loadAlignmentInputs 失败: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 确定性实现（顺序编排路径直接调用，LLM 子 agent 经 @Tool 调用同一逻辑）
    // ------------------------------------------------------------------

    /**
     * 事件收集（NewsEventAgent 的确定性等价物）：按拐点候选窗口检索 HN、窗口内按 URL 去重，落盘检查点。
     */
    public List<EventCandidates> collectEvents(String symbol, boolean refresh)
            throws Exception {
        symbol = symbol.toUpperCase();
        Path file = eventsFile(symbol);
        if (Files.exists(file) && !refresh) {
            return List.of(mapper.readValue(file.toFile(), EventCandidates[].class));
        }
        List<InflectionPoint> pivots = selectedPivots(symbol);
        String keyword = KEYWORDS.getOrDefault(symbol, symbol);
        List<EventCandidates> out = new ArrayList<>();
        for (InflectionPoint p : pivots) {
            List<NewsItem> windowNews = new ArrayList<>();
            // HN 偶发 500：窗口级两次尝试（传输层已做退避重试，此处兜底整体失败）
            for (int attempt = 0; attempt < 2 && CollectionUtils.isEmpty(windowNews); attempt++) {
                if (attempt > 0) {
                    Thread.sleep(2_000);
                }
                try {
                    List<NewsItem> found = new HackerNewsProvider().search(
                            keyword, p.windowStart(), p.windowEnd());
                    java.util.Set<String> seen = new java.util.HashSet<>();
                    for (NewsItem n : found) {
                        // 只收有 URL 的新闻并按 URL 去重（无 URL 的无法溯源，跳过）
                        if (!Strings.isBlank(n.url()) && seen.add(n.url())) {
                            windowNews.add(n);
                        }
                    }
                } catch (Exception e) {
                    log.warn("窗口 {}~{} 第 {} 次检索失败: {}", p.windowStart(), p.windowEnd(),
                            attempt + 1, e.getMessage());
                }
            }
            out.add(new EventCandidates(p, windowNews));
            Thread.sleep(250);   // HN Algolia 限速保护
        }
        Files.createDirectories(workDir);
        mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), out);
        log.info("事件落盘: {}（{} 个拐点窗口）", file, out.size());
        return out;
    }

    /**
     * 读取对齐输入检查点。
     */
    public List<EventCandidates> loadCandidates(String symbol) throws Exception {
        Path file = eventsFile(symbol.toUpperCase());
        if (!Files.exists(file)) {
            throw new IllegalStateException("缺少 " + file + "，请先完成行情/分析/事件三步");
        }
        return List.of(mapper.readValue(file.toFile(), EventCandidates[].class));
    }

    /**
     * alignments.json 的 schema 版本（R4 起为 2：marks+missing 双数组 + summary/correlation 字段）。
     */
    public static final String SCHEMA_VERSION = "2";

    /**
     * 对齐检查点落盘（schemaVersion 2：attributed marks + missing 事件缺失 + 生成时间）。
     * 同时写 alignments.json（当前运行）与 {symbol}_alignments.json（多标的渲染用）。
     */
    public void saveAlignments(String symbol, String mode,
                               List<EventMark> marks,
                               List<MissingEvent> missing)
            throws Exception {
        symbol = symbol.toUpperCase();
        Map<String, Object> doc = new HashMap<>();
        doc.put("symbol", symbol);
        doc.put("mode", mode);
        doc.put("schemaVersion", SCHEMA_VERSION);
        doc.put("generatedAt", java.time.OffsetDateTime.now().toString());
        doc.put("marks", marks);
        doc.put("missing", missing);
        String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(doc);
        Files.writeString(workDir.resolve("alignments.json"), json);
        Files.writeString(workDir.resolve(symbol + "_alignments.json"), json);
    }

    /**
     * 对齐检查点读取（断点续跑/--verify）。
     */
    public List<EventMark> loadAlignments() throws Exception {
        return loadAlignmentsDoc().marks();
    }

    /**
     * 读取完整对齐文档（marks + missing）。
     */
    public AlignmentOutcome loadAlignmentsDoc() throws Exception {
        return readAlignmentDoc(workDir.resolve("alignments.json"));
    }

    /**
     * 按标的读取对齐文档：优先 {symbol}_alignments.json，缺失时回退 alignments.json（symbol 须匹配）。
     */
    public AlignmentOutcome loadAlignmentsFor(String symbol) throws Exception {
        symbol = symbol.toUpperCase();
        Path perSymbol = workDir.resolve(symbol + "_alignments.json");
        if (Files.exists(perSymbol)) {
            return readAlignmentDoc(perSymbol);
        }
        Path current = workDir.resolve("alignments.json");
        if (Files.exists(current)) {
            Map<String, Object> doc = mapper.readValue(current.toFile(),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
            if (symbol.equals(doc.get("symbol"))) {
                return readAlignmentDoc(current);
            }
        }
        return AlignmentOutcome.empty();
    }

    private AlignmentOutcome readAlignmentDoc(Path file) throws Exception {
        if (!Files.exists(file)) {
            return AlignmentOutcome.empty();
        }
        Map<String, Object> doc = mapper.readValue(file.toFile(),
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                });
        List<EventMark> marks = doc.get("marks") instanceof List<?> l
                ? mapper.convertValue(l, mapper.getTypeFactory()
                                         .constructCollectionType(List.class, EventMark.class))
                : List.of();
        List<MissingEvent> missing = doc.get("missing") instanceof List<?> ml
                ? mapper.convertValue(ml, mapper.getTypeFactory()
                                          .constructCollectionType(List.class, MissingEvent.class))
                : List.of();
        return new AlignmentOutcome(marks, missing);
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /**
     * 对齐候选拐点筛选：剔除滞后的均线交叉，同日多类型只保留一个；
     * 每季度按显著性（|当日涨跌|+放量加成）取前 3，全局上限 48——
     * 保证 5 年每季度都有代表（时间全覆盖），不被高波动期挤占。
     */
    static List<InflectionPoint> selectAlignmentPivots(List<InflectionPoint> pivots) {
        List<InflectionPoint> ranked = new java.util.ArrayList<>(pivots.stream()
                .filter(p -> p.type() != PivotType.GOLDEN_CROSS && p.type() != PivotType.DEATH_CROSS)
                .sorted(Comparator.comparingDouble((InflectionPoint p) ->
                                Math.abs(p.dayChangePct()) + (p.volumeSpike() ? 3 : 0))
                        .reversed())
                .collect(java.util.stream.Collectors.toMap(
                        InflectionPoint::date, p -> p, (a, b) -> a, java.util.LinkedHashMap::new))
                .values());
        Map<String, Integer> quarterCount = new java.util.HashMap<>();
        List<InflectionPoint> selected = new java.util.ArrayList<>();
        for (InflectionPoint p : ranked) {
            if (selected.size() >= MAX_ALIGNMENT_PIVOTS) {
                break;
            }
            String quarter = p.date().getYear() + "Q" + ((p.date().getMonthValue() - 1) / 3 + 1);
            if (quarterCount.getOrDefault(quarter, 0) < PER_QUARTER_PIVOTS) {
                selected.add(p);
                quarterCount.merge(quarter, 1, Integer::sum);
            }
        }
        selected.sort(Comparator.comparing(InflectionPoint::date));
        return selected;
    }

    private List<InflectionPoint> selectedPivots(String symbol) throws Exception {
        Map<String, Object> doc = readAnalysis(symbol);
        @SuppressWarnings("unchecked")
        List<InflectionPoint> pivots = mapper.convertValue(doc.get("pivots"),
                mapper.getTypeFactory().constructCollectionType(List.class, InflectionPoint.class));
        return pivots;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readAnalysis(String symbol) throws Exception {
        return mapper.readValue(analysisFile(symbol).toFile(), Map.class);
    }

    private String summaryJson(String symbol, Path file) throws Exception {
        var doc = mapper.readValue(file.toFile(), AnalysisMain.JsonDoc.class);
        List<Ohlcv> c = doc.candles();
        Map<String, Object> out = new HashMap<>();
        out.put("symbol", symbol);
        out.put("source", doc.source());
        out.put("count", c.size());
        out.put("firstDate", c.isEmpty() ? null : c.getFirst().date().toString());
        out.put("lastDate", c.isEmpty() ? null : c.getLast().date().toString());
        out.put("file", file.toString());
        return mapper.writeValueAsString(out);
    }

    private String pivotSummaryJson(List<InflectionPoint> top) throws Exception {
        Map<String, Object> out = new HashMap<>();
        out.put("topPivots", top);
        return mapper.writeValueAsString(out);
    }

    private String errorJson(String message) {
        try {
            return mapper.writeValueAsString(Map.of("error", message));
        } catch (Exception e) {
            return "{\"error\":\"tool failed\"}";
        }
    }

    Path ohlcvFile(String symbol) {
        return workDir.resolve(symbol + "_ohlcv.json");
    }

    Path analysisFile(String symbol) {
        return workDir.resolve(symbol + "_analysis.json");
    }

    Path eventsFile(String symbol) {
        return workDir.resolve(symbol + "_events.json");
    }

    /**
     * 分析文档结构（持久化形态，供外部读取）。
     */
    public record AnalysisDoc(String symbol, String generatedAt, int pivotCount, int segmentCount,
                              List<InflectionPoint> pivots, List<Segment> segments,
                              List<InflectionPoint> topPivots) {
    }

    /**
     * 声明 Jackson 需要的 Set 类型（消除未用 import 提示用，保留 Set 供未来扩展）。
     */
    @SuppressWarnings("unused")
    private static final Set<String> SUPPORTED_SYMBOLS = Set.of("NVDA", "GLD", "BTC-USD");
}
