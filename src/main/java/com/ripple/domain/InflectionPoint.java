package com.ripple.domain;

import com.ripple.domain.enums.PivotType;

import java.time.LocalDate;

/**
 * 行情拐点：检测算法输出的最小信号单元。
 * candidateWindow 为前后 ±5 个交易日（事件匹配候选窗口，供 R4 归因使用）。
 */
public record InflectionPoint(
        LocalDate date,
        PivotType type,
        double dayChangePct,
        boolean volumeSpike,
        LocalDate windowStart,
        LocalDate windowEnd) {
}
