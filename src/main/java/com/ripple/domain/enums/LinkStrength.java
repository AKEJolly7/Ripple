package com.ripple.domain.enums;

/** 拐点与事件的关联强度（R4 对齐产出，按 correlation 分档）。枚举常量英文命名，中文标签经 label() 输出。 */
public enum LinkStrength {
    STRONG("强相关"),
    MODERATE("相关"),
    WEAK("弱相关");

    private final String label;

    LinkStrength(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** 按 correlation（0-1）分档：≥0.65 强相关，≥0.40 相关，否则弱相关。 */
    public static LinkStrength of(double correlation) {
        return correlation >= 0.65 ? STRONG : correlation >= 0.40 ? MODERATE : WEAK;
    }
}
