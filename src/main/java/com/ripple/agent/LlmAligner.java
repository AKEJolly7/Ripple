package com.ripple.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ripple.agent.api.CorrelationAnalyst;
import com.ripple.agent.tools.AlignmentTools;
import com.ripple.agent.tools.RippleTools;
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
 * LLM 对齐：经 AiServices 构造 CorrelationAnalyst（tool = AlignmentTools，只能读检查点证据），
 * 输出经 schema 校验——attributed/missing 双数组，URL 必须属于证据集合、拐点日期必须真实存在、
 * correlation/confidence 收敛到 [0,1]、枚举收敛到合法域，不合规条目直接丢弃
 * （LLM 只负责判断，不负责"知道"）。解析失败由上层降级为规则对齐。
 */
public final class LlmAligner {

    private static final Logger log = LoggerFactory.getLogger(LlmAligner.class);

    private final CorrelationAnalyst analyst;
    private final ObjectMapper mapper = new ObjectMapper();

    public LlmAligner(RippleTools toolkit) {
        this.analyst = AiServices.builder(CorrelationAnalyst.class)
                .chatModel(LlmModels.deepseek())
                .tools(new AlignmentTools(toolkit))   // 归因 agent 只能读对齐输入检查点
                .build();
    }

    public AlignmentOutcome align(String symbol, List<EventCandidates> candidates) {
        String raw = analyst.align(symbol);
        return validate(raw, candidates);
    }

    /**
     * 校验并收敛 LLM 输出。
     */
    AlignmentOutcome validate(String raw, List<EventCandidates> candidates) {
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

    private JsonNode parseObject(String json) {
        try {
            JsonNode root = mapper.readTree(json);
            if (root.isObject()) {
                return root;
            }
            throw new IllegalArgumentException("LLM 输出不是 JSON 对象");
        } catch (Exception e) {
            throw new IllegalStateException("LLM 输出解析失败: " + e.getMessage(), e);
        }
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
