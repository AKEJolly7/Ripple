package com.ripple.agent;

import com.ripple.domain.AlignmentOutcome;
import com.ripple.domain.EventCandidates;
import com.ripple.domain.enums.ImpactRating;
import com.ripple.domain.InflectionPoint;
import com.ripple.domain.enums.LinkStrength;
import com.ripple.domain.NewsItem;
import com.ripple.domain.enums.PivotType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 规则对齐的确定性验证：关联分档、事件缺失标注、评级方向、证据 URL 约束（无网络）。
 */
class RuleBasedAlignerTest {

    private static final LocalDate D = LocalDate.of(2025, 1, 27);   // DeepSeek 冲击日

    private static InflectionPoint pivot(PivotType type, double pct, boolean spike) {
        return new InflectionPoint(D, type, pct, spike,
                D.minusDays(7), D.plusDays(7));
    }

    @Test
    void strongEventGetsCorrelationAndAllMandatoryFields() {
        InflectionPoint crash = pivot(PivotType.BIG_DOWN, -16.97, true);
        NewsItem ds = new NewsItem("DeepSeek R1 release shakes Nvidia GPU demand",
                D, "DeepSeek's efficient model triggered a selloff in AI chip stocks.",
                "https://example.com/deepseek", "HackerNews", "id-1");

        AlignmentOutcome out = new RuleBasedAligner().align(
                List.of(new EventCandidates(crash, List.of(ds))));

        assertEquals(1, out.marks().size());
        assertEquals(0, out.missing().size());
        var m = out.marks().get(0);

        // R4 必带字段全数在位
        assertTrue(m.eventTitle().contains("DeepSeek"));
        assertEquals(D, m.eventDate());
        assertEquals("https://example.com/deepseek", m.url());
        assertTrue(m.summary().contains("selloff"));       // 摘要优先取 news.summary
        assertEquals(ImpactRating.BEARISH, m.rating());    // -16.97% → 利空
        assertEquals(LinkStrength.STRONG, m.strength());   // 高分 → 强相关
        assertTrue(m.correlation() >= 0.65);
        assertTrue(m.confidence() > 0 && m.confidence() <= 0.95);
        assertTrue(m.reasoning().contains("同日发生"));
    }

    @Test
    void weakEventIsMarkedMissingWithCandidatesNoAttribution() {
        InflectionPoint p = pivot(PivotType.LOCAL_BOTTOM, -2.0, false);   // 小波动
        NewsItem noise = new NewsItem("Show HN: my weekend project",
                D.plusDays(3), "", "https://example.com/noise", "HackerNews", "id-2");
        NewsItem noise2 = new NewsItem("Ask HN: favorite keyboard?",
                D.plusDays(1), "", "https://example.com/kb", "HackerNews", "id-3");

        AlignmentOutcome out = new RuleBasedAligner().align(
                List.of(new EventCandidates(p, List.of(noise, noise2))));

        // 无关新闻得分低于 0.40 → 事件缺失，不做归因
        assertEquals(0, out.marks().size());
        assertEquals(1, out.missing().size());
        var miss = out.missing().get(0);
        assertEquals(D, miss.pivotDate());
        assertEquals(2, miss.candidates().size());           // 同期候选全数给出
        assertTrue(miss.candidates().stream().allMatch(c -> c.url() != null));
    }

    @Test
    void pivotWithoutAnyNewsIsMissingWithEmptyCandidates() {
        AlignmentOutcome out = new RuleBasedAligner().align(
                List.of(new EventCandidates(pivot(PivotType.BIG_DOWN, -5.5, false), List.of())));
        assertEquals(0, out.marks().size());
        assertEquals(1, out.missing().size());
        assertTrue(out.missing().get(0).candidates().isEmpty());
    }

    @Test
    void summaryFallsBackToTitleWhenStoryTextEmpty() {
        InflectionPoint p = pivot(PivotType.BIG_UP, 24.37, true);
        NewsItem noText = new NewsItem("Nvidia Q1 2023 earnings blowout",
                D, "", "https://example.com/nvda", "HackerNews", "id-4");

        var m = new RuleBasedAligner().align(
                List.of(new EventCandidates(p, List.of(noText)))).marks().get(0);

        assertEquals("Nvidia Q1 2023 earnings blowout", m.summary());   // 摘要兜底用标题
        assertFalse(m.summary().isEmpty());
    }

    @Test
    void strengthBandsFollowCorrelationThresholds() {
        assertEquals(LinkStrength.STRONG, LinkStrength.of(0.65));
        assertEquals(LinkStrength.STRONG, LinkStrength.of(0.9));
        assertEquals(LinkStrength.MODERATE, LinkStrength.of(0.40));
        assertEquals(LinkStrength.MODERATE, LinkStrength.of(0.64));
        assertEquals(LinkStrength.WEAK, LinkStrength.of(0.39));
    }
}
