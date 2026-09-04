package com.ripple.domain;

import java.util.List;

/** 对齐输入：一个拐点与其候选窗口内检索到的新闻（work/{symbol}_events.json 的元素）。 */
public record EventCandidates(InflectionPoint pivot, List<NewsItem> news) {
}
