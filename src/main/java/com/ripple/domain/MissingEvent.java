package com.ripple.domain;

import com.ripple.domain.enums.PivotType;

import java.time.LocalDate;
import java.util.List;

/**
 * 事件缺失标注：候选窗口内找不到达到相关阈值的事件的拐点。
 * 只给同期候选（按规则打分排序的前 2-3 条），不做任何归因判断。
 */
public record MissingEvent(
        LocalDate pivotDate,
        PivotType pivotType,
        double dayChangePct,
        boolean volumeSpike,
        List<NewsItem> candidates) {
}
