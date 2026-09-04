package com.ripple.domain;

import java.time.LocalDate;

/**
 * 一条资讯（HN Algolia / GDELT 解析后的统一形态）。
 * externalId 为数据源侧唯一标识（如 HN objectID），与 url 一起构成溯源凭据。
 */
public record NewsItem(String title, LocalDate date, String summary, String url, String source, String externalId) {
}
