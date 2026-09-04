package com.ripple.agent;

import com.ripple.domain.*;
import com.ripple.domain.enums.ImpactRating;
import com.ripple.domain.enums.LinkStrength;
import org.apache.logging.log4j.util.Strings;

import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * --no-llm 降级对齐（确定性，无 key 可跑）。
 * 规则：时间窗为主（新闻已按拐点 ±5 交易日窗口收集，日内距离越近分越高），
 * 关键词重合度 + 放量加成为辅；correlation ∈ [0,1]，≥0.65 强相关、≥0.40 相关、<0.40 归为事件缺失。
 * 缺失拐点只附 2-3 条同期候选（按分排序），不做归因——与 LLM 路径（CorrelationAnalyst）互为镜像。
 */
public final class RuleBasedAligner {

    /**
     * 归入"事件缺失"的分数线（低于此值不勉强归因）。可用 -Dalign.missing.threshold 覆盖。
     */
    static final double MISSING_THRESHOLD = Double.parseDouble(
            System.getProperty("align.missing.threshold", "0.40"));

    /**
     * 缺失标注携带的候选条数上限。
     */
    private static final int MISSING_CANDIDATES = 3;

    /**
     * 行业事件词典：标题命中加成（NVDA 场景为主，兼顾黄金/比特币/宏观财经词，供 GLD/BTC 对齐）。
     */
    private static final Set<String> LEXICON = Set.of(
            "nvidia", "nvda", "gpu", "chatgpt", "openai", "gpt", "deepseek",
            "blackwell", "b100", "b200", "earnings", "guidance", "ai", "chip",
            "tesla", "microsoft", "meta", "google", "tsmc", "export", "ban",
            "gold", "silver", "bitcoin", "btc", "crypto", "cryptocurrency", "ethereum",
            "etf", "fed", "federal", "inflation", "cpi", "rates", "treasury",
            "dollar", "haven", "halving", "recession", "tariff", "selloff",
            "rally", "plunge", "surge", "record", "bull", "bear");

    private static final Set<String> STOPWORDS = Set.of(
            "the", "a", "an", "of", "to", "and", "in", "on", "for", "with", "is",
            "how", "what", "why", "my", "your", "new", "using", "from", "at", "by");

    public AlignmentOutcome align(List<EventCandidates> candidates) {
        List<EventMark> marks = new ArrayList<>();
        List<MissingEvent> missing = new ArrayList<>();
        for (EventCandidates c : candidates) {
            Scored best = bestNews(c);
            if (best != null && best.score >= MISSING_THRESHOLD) {
                marks.add(toMark(c.pivot(), best.news, best.score));
            } else {
                missing.add(toMissing(c, best));
            }
        }
        marks.sort(Comparator.comparingDouble(EventMark::correlation).reversed());
        return new AlignmentOutcome(List.copyOf(marks), List.copyOf(missing));
    }

    private record Scored(NewsItem news, double score) {
    }

    private Scored bestNews(EventCandidates c) {
        Scored best = null;
        for (NewsItem n : c.news()) {
            double s = score(c.pivot(), n);
            if (best == null || s > best.score) {
                best = new Scored(n, s);
            }
        }
        return best;
    }

    /**
     * 相关性打分（即 correlation）：时间窗接近度为主，标题词典重合与行情动量为辅，区间 [0,1]。
     */
    double score(InflectionPoint pivot, NewsItem news) {
        String title = Strings.isBlank(news.title()) ? Strings.EMPTY : news.title().toLowerCase(Locale.ROOT);
        String[] tokens = title.split("[^a-z0-9]+");
        int informative = 0;
        int lexiconHits = 0;
        for (String t : tokens) {
            if (Strings.isBlank(t) || STOPWORDS.contains(t)) {
                continue;
            }
            informative++;
            if (LEXICON.contains(t)) {
                lexiconHits++;
            }
        }
        // 主题硬约束：标题无任何行业词典命中的新闻，时间再吻合也到不了归因线（宁缺毋滥）
        if (lexiconHits == 0) {
            return Math.min(0.39, dateOnlyScore(pivot, news));
        }
        double titleScore = Math.min(1.0, (double) lexiconHits / Math.max(1, informative) + 0.3);

        long days = news.date() == null ? 30
                : Math.abs(ChronoUnit.DAYS.between(pivot.date(), news.date()));
        double dateScore = days == 0 ? 1.0 : days <= 2 ? 0.7 : days <= 5 ? 0.4 : 0.1;

        double moveScore = Math.min(1.0, Math.abs(pivot.dayChangePct()) / 15);
        double spikeBonus = pivot.volumeSpike() ? 0.1 : 0;

        return Math.min(1.0, 0.35 * titleScore + 0.3 * dateScore + 0.25 * moveScore + 0.1 + spikeBonus);
    }

    /**
     * 无主题重合时的兜底分（仅日期+动量，恒低于 0.40 归因线）。
     */
    private double dateOnlyScore(InflectionPoint pivot, NewsItem news) {
        long days = news.date() == null ? 30
                : Math.abs(ChronoUnit.DAYS.between(pivot.date(), news.date()));
        double dateScore = days == 0 ? 1.0 : days <= 2 ? 0.7 : days <= 5 ? 0.4 : 0.1;
        double moveScore = Math.min(1.0, Math.abs(pivot.dayChangePct()) / 15);
        return 0.2 * dateScore + 0.15 * moveScore + 0.05;
    }

    private EventMark toMark(InflectionPoint pivot, NewsItem news, double correlation) {
        double abs = Math.abs(pivot.dayChangePct());
        LinkStrength strength = LinkStrength.of(correlation);
        // 事件缺失候选之外的归因，置信度与关联度一致方向但看行情显著性修正
        double confidence = Math.min(0.95, correlation * 0.7
                + Math.min(0.25, abs / 60) + (pivot.volumeSpike() ? 0.05 : 0));
        ImpactRating rating = pivot.dayChangePct() > 0 ? ImpactRating.BULLISH
                : pivot.dayChangePct() < 0 ? ImpactRating.BEARISH
                  : ImpactRating.NEUTRAL;
        long days = news.date() == null ? 99 : ChronoUnit.DAYS.between(pivot.date(), news.date());
        String reasoning = "规则对齐：拐点 %s（%s）当日 %+.2f%%%s，候选窗口内新闻《%s》（%s）与行情变化%s，"
                .formatted(pivot.date(), pivot.type().label(), pivot.dayChangePct(),
                        pivot.volumeSpike() ? "且异常放量" : "",
                        truncate(news.title(), 60), news.date() == null ? "日期未知" : news.date(),
                        days == 0 ? "同日发生" : "相距 " + days + " 天")
                + "按时间窗吻合度为主、关键词重合度为辅给出关联度 " + "%.2f".formatted(correlation) + "。";
        return new EventMark(news.title(), news.date(), news.url(), news.source(),
                summaryOf(news), pivot.date(), pivot.type(), pivot.dayChangePct(),
                pivot.volumeSpike(), correlation, strength, rating, confidence, reasoning);
    }

    private MissingEvent toMissing(EventCandidates c, Scored best) {
        List<NewsItem> top = c.news().stream()
                .sorted((a, b) -> Double.compare(score(c.pivot(), b), score(c.pivot(), a)))
                .limit(MISSING_CANDIDATES)
                .toList();
        return new MissingEvent(c.pivot().date(), c.pivot().type(), c.pivot().dayChangePct(),
                c.pivot().volumeSpike(), top);
    }

    private static String summaryOf(NewsItem news) {
        String s = Strings.isBlank(news.summary()) ? Strings.EMPTY : news.summary().trim();
        if (Strings.isBlank(s)) {
            // HN story_text 常为空：用标题前段做摘要兜底，保持字段必带
            s = Strings.isBlank(news.title()) ? Strings.EMPTY : news.title();
        }
        return truncate(s, 200);
    }

    private static String truncate(String s, int max) {
        return !Strings.isBlank(s) && s.length() > max ? s.substring(0, max) + "…" : s;
    }
}
