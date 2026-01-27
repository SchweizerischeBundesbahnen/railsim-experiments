package org.matsim.project.scenario.plan;

import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

@Value
@Builder
@Jacksonized
public class Product {
    String id;
    String description;
    int minHeadway;
}