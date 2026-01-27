package org.matsim.project.scenario.plan;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class OperatingMode {
    ProductMix productMix;
    FlowPattern flowPattern;

    public String getId() {
        return productMix.getId().toLowerCase() + "_" + flowPattern.getId().toLowerCase();
    }
}
