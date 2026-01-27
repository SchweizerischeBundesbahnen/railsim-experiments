package org.matsim.project.scenario.plan;

import lombok.Builder;
import lombok.Value;

import java.util.Map;

@Value
@Builder
public class FlowPattern {
    String id;
    String description;
    Map<Product, Map<TrafficFlow, Double>> shares;
}