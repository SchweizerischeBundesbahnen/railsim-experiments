package org.matsim.project.scenario.plan;

import lombok.Builder;
import lombok.Value;

import java.util.Map;

@Value
@Builder
public class TrafficFlow {
    String id;
    String description;
    Map<Product, RouteMapping> routes;
}