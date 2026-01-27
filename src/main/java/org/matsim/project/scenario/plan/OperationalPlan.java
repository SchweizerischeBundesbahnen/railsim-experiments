package org.matsim.project.scenario.plan;

import lombok.Builder;
import lombok.Value;

import java.util.List;
import java.util.Map;

@Value
@Builder
public class OperationalPlan {
    TrainVolumes trainVolumes;
    Map<String, Product> products;
    Map<String, TrafficFlow> trafficFlows;
    Map<String, FlowPattern> flowPatterns;
    Map<String, ProductMix> productMixes;
    List<OperatingMode> operatingModes;
}