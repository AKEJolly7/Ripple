package com.ripple.domain.enums;

/**
 * 事件对行情的影响评级（R4 由 LLM 归因产出）。
 * 枚举常量遵循常规命名（英文全大写），中文标签经 label() 输出到产物。
 */
public enum ImpactRating {
    BULLISH("利好"),
    BEARISH("利空"),
    NEUTRAL("中性");

    private final String label;

    ImpactRating(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
