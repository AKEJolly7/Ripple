package com.ripple.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ripple.agent.api.CorrelationAnalyst;
import com.ripple.domain.*;
import com.ripple.domain.enums.ImpactRating;
import com.ripple.domain.enums.LinkStrength;
import com.ripple.domain.enums.PivotType;
import dev.langchain4j.service.AiServices;
import org.apache.logging.log4j.util.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.*;

/**
 * LLM 对齐：按拐点分片调用 CorrelationAnalyst（alignOne，证据直接随用户消息下发，
 * 候选新闻经 RuleBasedAligner.score 预筛降序截取 top-N——整标的一次性送入会超出
 * 模型上下文与输出 token 上限）。输出经 schema 校验——attributed/missing 双数组，
 * URL 必须属于证据集合、拐点日期必须真实存在、correlation/confidence 收敛到 [0,1]、
 * 枚举收敛到合法域，不合规条目直接丢弃（LLM 只负责判断，不负责"知道"）。
 * 单拐点失败（异常/解析失败/返回为空/全被校验丢弃）降级为该拐点的"事件缺失"（附规则分
 * top 候选），不中断全局；LLM 整体不可用（归因为空）时由上层
 * {@link RippleOrchestrator#runSequential} 降级为规则对齐（mode 落盘为 rule）。
 */
public final class LlmAligner {

    private static final Logger log = LoggerFactory.getLogger(LlmAligner.class);

    /**
     * 单拐点 LLM 输入的候选新闻上限（规则分预筛降序后截取）。
     */
    private static final int MAX_NEWS_PER_PIVOT = 8;

    /**
     * LLM 失败兜底时事件缺失携带的候选条数（与 RuleBasedAligner 一致）。
     */
    private static final int MISSING_CANDIDATES = 3;

    private final CorrelationAnalyst analyst;
    private final RuleBasedAligner ranker = new RuleBasedAligner();
    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    public LlmAligner() {
        this(AiServices.builder(CorrelationAnalyst.class)
                .chatModel(LlmModels.deepseek())
                .build());
    }

    /**
     * 测试注入桩 analyst 用。
     */
    LlmAligner(CorrelationAnalyst analyst) {
        this.analyst = analyst;
    }

    public AlignmentOutcome align(List<EventCandidates> candidates) {
        List<EventMark> marks = new ArrayList<>();
        List<MissingEvent> missing = new ArrayList<>();
        for (EventCandidates c : candidates) {
            try {
                AlignmentOutcome one = validate(analyst.alignOne(toPivotInput(c)), List.of(c));
                marks.addAll(one.marks());
                missing.addAll(one.missing());
                if (one.marks().isEmpty() && one.missing().isEmpty()) {
                    // 返回为空或全被校验丢弃：回填事件缺失，不让该拐点凭空消失
                    missing.add(toMissing(c));
                }
            } catch (Exception e) {
                log.warn("拐点 {} LLM 对齐失败，降级为事件缺失: {}", c.pivot().date(), e.getMessage());
                missing.add(toMissing(c));
            }
        }
        marks.sort(Comparator.comparingDouble(EventMark::correlation).reversed());
        return new AlignmentOutcome(List.copyOf(marks), List.copyOf(missing));
    }

    /**
     * 校验并收敛 LLM 输出。
     */
    public AlignmentOutcome validate(String raw, List<EventCandidates> candidates) {
        Set<String> evidenceUrls = new HashSet<>();
        Set<String> pivotDates = new HashSet<>();
        for (EventCandidates c : candidates) {
            pivotDates.add(c.pivot().date().toString());
            for (NewsItem n : c.news()) {
                if (!Strings.isBlank(n.url())) {
                    evidenceUrls.add(n.url());
                }
            }
        }
        JsonNode root = parseObject(stripFences(raw));
        List<EventMark> marks = new ArrayList<>();
        for (JsonNode node : root.path("attributed")) {
            String url = node.path("url").asText(null);
            String pivotDate = node.path("pivotDate").asText(null);
            if (Strings.isBlank(url) || !evidenceUrls.contains(url)) {
                log.warn("丢弃编造 URL 的条目: {}", url);
                continue;
            }
            if (Strings.isBlank(pivotDate) || !pivotDates.contains(pivotDate)) {
                log.warn("丢弃拐点日期不存在的条目: {}", pivotDate);
                continue;
            }
            double correlation = clamp(node.path("correlation").asDouble(0), 0, 1);
            marks.add(new EventMark(
                    node.path("eventTitle").asText(Strings.EMPTY),
                    parseDate(node.path("eventDate").asText(null)),
                    url,
                    node.path("source").asText(Strings.EMPTY),
                    node.path("summary").asText(Strings.EMPTY),
                    LocalDate.parse(pivotDate),
                    parseEnum(node.path("pivotType").asText(null), PivotType.BIG_UP),
                    node.path("dayChangePct").asDouble(0),
                    node.path("volumeSpike").asBoolean(false),
                    correlation,
                    LinkStrength.of(correlation),
                    parseEnum(node.path("rating").asText(null), ImpactRating.NEUTRAL),
                    clamp(node.path("confidence").asDouble(0), 0, 1),
                    node.path("reasoning").asText(Strings.EMPTY)));
        }
        List<MissingEvent> missing = new ArrayList<>();
        for (JsonNode node : root.path("missing")) {
            String pivotDate = node.path("pivotDate").asText(null);
            if (Strings.isBlank(pivotDate) || !pivotDates.contains(pivotDate)) {
                continue;
            }
            List<NewsItem> candidatesOut = new ArrayList<>();
            for (JsonNode cn : node.path("candidates")) {
                String url = cn.path("url").asText(null);
                if (Strings.isBlank(url) || !evidenceUrls.contains(url)) {
                    continue;   // 缺失候选的 URL 同样必须来自证据集合
                }
                candidatesOut.add(new NewsItem(
                        cn.path("title").asText(Strings.EMPTY),
                        parseDate(cn.path("date").asText(null)),
                        Strings.EMPTY, url, cn.path("source").asText(Strings.EMPTY),
                        cn.path("externalId").asText(null)));
            }
            missing.add(new MissingEvent(LocalDate.parse(pivotDate),
                    parseEnum(node.path("pivotType").asText(null), PivotType.BIG_UP),
                    node.path("dayChangePct").asDouble(0),
                    node.path("volumeSpike").asBoolean(false),
                    List.copyOf(candidatesOut)));
        }
        return new AlignmentOutcome(List.copyOf(marks), List.copyOf(missing));
    }

    /**
     * 单拐点输入：候选新闻按规则分预筛降序，截取 top-N 控制 token 规模。
     */
    private String toPivotInput(EventCandidates c) throws Exception {
        List<NewsItem> news = c.news().stream()
                .sorted((a, b) -> Double.compare(ranker.score(c.pivot(), b), ranker.score(c.pivot(), a)))
                .limit(MAX_NEWS_PER_PIVOT)
                .toList();
        return mapper.writeValueAsString(new EventCandidates(c.pivot(), news));
    }

    /**
     * LLM 失败/被丢弃时的兜底：事件缺失 + 规则分 top 候选（不做归因）。
     */
    private MissingEvent toMissing(EventCandidates c) {
        List<NewsItem> top = c.news().stream()
                .sorted((a, b) -> Double.compare(ranker.score(c.pivot(), b), ranker.score(c.pivot(), a)))
                .limit(MISSING_CANDIDATES)
                .toList();
        return new MissingEvent(c.pivot().date(), c.pivot().type(),
                c.pivot().dayChangePct(), c.pivot().volumeSpike(), top);
    }

    private JsonNode parseObject(String json) {
        // 模型偶发在 JSON 前后夹散文（"Given…"）：提取首个配对完整的最外层对象再解析
        String extracted = extractOuterObject(json);
        try {
            JsonNode root = mapper.readTree(extracted);
            if (root.isObject()) {
                return root;
            }
            throw new IllegalArgumentException("LLM 输出不是 JSON 对象");
        } catch (Exception e) {
            throw new IllegalStateException("LLM 输出解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 提取字符串中首个花括号配对完整的 JSON 对象（跳过字符串字面量内的花括号）；无配对时原样返回。
     */
    private static String extractOuterObject(String s) {
        int start = s.indexOf('{');
        if (start < 0) {
            return s;
        }
        boolean inString = false;
        boolean escaped = false;
        int depth = 0;
        for (int i = start; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (escaped) {
                escaped = false;
            } else if (ch == '\\') {
                escaped = true;
            } else if (ch == '"') {
                inString = !inString;
            } else if (!inString) {
                if (ch == '{') {
                    depth++;
                } else if (ch == '}' && --depth == 0) {
                    return s.substring(start, i + 1);
                }
            }
        }
        return s;
    }

    private static LocalDate parseDate(String s) {
        try {
            return s == null ? null : LocalDate.parse(s);
        } catch (Exception e) {
            return null;
        }
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private static <E extends Enum<E>> E parseEnum(String value, E fallback) {
        try {
            return value == null ? fallback : Enum.valueOf(fallback.getDeclaringClass(),
                    value.toUpperCase(Locale.ROOT).trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String stripFences(String raw) {
        String s = Strings.isBlank(raw) ? Strings.EMPTY : raw.trim();
        if (s.startsWith("```")) {
            int first = s.indexOf('\n');
            int last = s.lastIndexOf("```");
            if (first >= 0 && last > first) {
                s = s.substring(first + 1, last).trim();
            }
        }
        return s;
    }
}
