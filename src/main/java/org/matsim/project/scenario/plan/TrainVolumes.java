package org.matsim.project.scenario.plan;

import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

@Value
@Builder
@Jacksonized
public class TrainVolumes {
    int period;
    int min;
    int max;
    int step;
    boolean bidirectional;
}