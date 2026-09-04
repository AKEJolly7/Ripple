package com.ripple.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ripple.domain.AlignmentOutcome;
import com.ripple.domain.EventCandidates;
import com.ripple.domain.EventMark;
import com.ripple.domain.enums.ImpactRating;
import com.ripple.domain.InflectionPoint;
import com.ripple.domain.enums.LinkStrength;
import com.ripple.domain.NewsItem;
import com.ripple.domain.enums.PivotType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对齐逻辑回归测试：R4 修复过的两个缺陷类别在此固化——
 * ① 无主题重合的新闻靠日期邻近混过归因线（词典硬约束）；
 * ② LLM 输出校验对编造 URL / 不存在拐点日期的丢弃。
 */
class AlignmentValidationTest {

    private static final LocalDate D = LocalDate.of(2025, 1, 27);

    private static InflectionPoint pivot(double pct) {
        return new InflectionPoint(D, PivotType.BIG_DOWN, pct, false, D.minusDays(7), D.plusDays(7));
    }

    @Test
    void offTopicNewsIsNeverAttributedEvenWhenSameDay() {
        // R4 缺陷回归：Ask HN 类无关新闻与拐点同日，日期分 1.0 曾把它抬过 0.40 归因线
        NewsItem noise = new NewsItem("Ask HN: What laptop do you use?",
                D, "", "https://example.com/noise", "HackerNews", "id-1");
        NewsItem onTopic = new NewsItem("Nvidia earnings guidance miss hits AI chip demand",
                D.plusDays(2), "", "https://example.com/nvda", "HackerNews", "id-2");

        var out = new RuleBasedAligner().align(List.of(
                new EventCandidates(pivot(-12.0), List.of(noise, onTopic))));

        assertEquals(1, out.marks().size());
        // 同日的无关新闻必须输给晚两天但主题吻合的新闻
        assertEquals("https://example.com/nvda", out.marks().getFirst().url());
        assertTrue(out.marks().getFirst().correlation() >= 0.40);
    }

    @Test
    void ratingDirectionMustFollowPriceMove() {
        // 评级方向一致性：大跌=利空、大涨=利好（规则对齐不变式）
        NewsItem n = new NewsItem("Nvidia beats earnings as AI chip demand surges",
                D, "", "https://example.com/e", "HackerNews", "id-3");
        var down = new RuleBasedAligner().align(List.of(
                new EventCandidates(pivot(-15.0), List.of(n)))).marks().getFirst();
        var up = new RuleBasedAligner().align(List.of(
                new EventCandidates(new InflectionPoint(D, PivotType.BIG_UP, 15.0, true,
                        D.minusDays(7), D.plusDays(7)), List.of(n)))).marks().getFirst();
        assertEquals(ImpactRating.BEARISH, down.rating());
        assertEquals(ImpactRating.BULLISH, up.rating());
    }

    @Test
    void llmValidatorDropsFabricatedUrlAndUnknownPivotDate() throws Exception {
        // R3/R4 LLM 校验：编造 URL、未知拐点日期的条目必须丢弃，证据内的保留
        NewsItem ev1 = new NewsItem("DeepSeek shakes Nvidia", D, "",
                "https://example.com/real", "HackerNews", "id-4");
        NewsItem ev2 = new NewsItem("Nvidia GTC B100 launch", D, "",
                "https://example.com/real2", "HackerNews", "id-5");
        var candidates = List.of(new EventCandidates(pivot(-16.97), List.of(ev1, ev2)));

        String llmOutput = """
                {"attributed":[
                  {"eventTitle":"编造来源","url":"https://fabricated.example.com/x","eventDate":"2025-01-27",
                   "pivotDate":"2025-01-27","pivotType":"BIG_DOWN","dayChangePct":-16.97,"volumeSpike":true,
                   "correlation":0.9,"rating":"BEARISH","confidence":0.9,"reasoning":"r","summary":"s","source":"HN"},
                  {"eventTitle":"拐点日期不存在","url":"https://example.com/real","eventDate":"2025-01-27",
                   "pivotDate":"2099-12-31","pivotType":"BIG_DOWN","dayChangePct":-16.97,"volumeSpike":true,
                   "correlation":0.9,"rating":"BEARISH","confidence":0.9,"reasoning":"r","summary":"s","source":"HN"},
                  {"eventTitle":"合法条目","url":"https://example.com/real2","eventDate":"2025-01-27",
                   "pivotDate":"2025-01-27","pivotType":"BIG_DOWN","dayChangePct":-16.97,"volumeSpike":true,
                   "correlation":0.88,"rating":"BEARISH","confidence":0.85,"reasoning":"合法","summary":"s","source":"HN"}],
                 "missing":[]}""";

        AlignmentOutcome out = new LlmAligner(null).validate(llmOutput, candidates);

        assertEquals(1, out.marks().size());
        EventMark m = out.marks().getFirst();
        assertEquals("https://example.com/real2", m.url());
        // correlation 经分档收敛：0.88 → STRONG
        assertEquals(LinkStrength.STRONG, m.strength());
        // 越界 confidence 被夹到 [0,1]
        assertTrue(m.confidence() >= 0 && m.confidence() <= 1);
    }

    @Test
    void llmValidatorToleratesMarkdownFences() throws Exception {
        NewsItem ev = new NewsItem("t", D, "", "https://example.com/r", "HN", "id");
        var candidates = List.of(new EventCandidates(pivot(-5.0), List.of(ev)));
        String fenced = "```json\n" + """
                {"attributed":[{"eventTitle":"t","url":"https://example.com/r","eventDate":"2025-01-27",
                "pivotDate":"2025-01-27","pivotType":"BIG_DOWN","dayChangePct":-5,"volumeSpike":false,
                "correlation":0.5,"rating":"BEARISH","confidence":0.5,"reasoning":"r","summary":"s","source":"HN"}],
                "missing":[]}""" + "\n```";
        assertEquals(1, new LlmAligner(null).validate(fenced, candidates).marks().size());
    }
}
