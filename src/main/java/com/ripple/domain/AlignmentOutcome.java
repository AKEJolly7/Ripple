package com.ripple.domain;

import java.util.List;

/** 对齐结果全集：已归因 marks + 事件缺失 missing（互斥，合计覆盖全部候选拐点）。 */
public record AlignmentOutcome(List<EventMark> marks, List<MissingEvent> missing) {

    public static AlignmentOutcome empty() {
        return new AlignmentOutcome(List.of(), List.of());
    }
}
