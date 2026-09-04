package com.ripple.domain;

import com.ripple.domain.enums.ImpactRating;

import java.time.LocalDate;

/**
 * 市场事件：在 NewsItem 字段之上叠加影响评级与置信度（R4 LLM 归因预留，
 * 未归因时 rating=null、confidence=null）。
 */
public record MarketEvent(
        String title,
        LocalDate date,
        String summary,
        String url,
        String source,
        ImpactRating rating,
        Double confidence) {

    /** 由资讯升级为待归因事件（R4 入口）。 */
    public static MarketEvent pending(NewsItem news) {
        return new MarketEvent(news.title(), news.date(), news.summary(), news.url(),
                news.source(), null, null);
    }
}
