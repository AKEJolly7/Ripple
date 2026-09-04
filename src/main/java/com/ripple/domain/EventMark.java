package com.ripple.domain;

import com.ripple.domain.enums.ImpactRating;
import com.ripple.domain.enums.LinkStrength;
import com.ripple.domain.enums.PivotType;

import java.time.LocalDate;

/**
 * 对齐结论（R4 规范化后，schemaVersion 2）：
 * 一个行情拐点 × 一条候选新闻的归因结果。
 * 必带字段：事件标题/日期/来源URL/摘要/影响评级/置信度/一句话推理，
 * 另含关联度 correlation（0-1，时间窗吻合为主、主题一致性为辅）与分档 strength。
 */
public record EventMark(
        String eventTitle,
        LocalDate eventDate,
        String url,
        String source,
        String summary,
        LocalDate pivotDate,
        PivotType pivotType,
        double dayChangePct,
        boolean volumeSpike,
        double correlation,
        LinkStrength strength,
        ImpactRating rating,
        double confidence,
        String reasoning) {
}
