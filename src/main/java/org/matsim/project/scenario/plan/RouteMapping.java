package org.matsim.project.scenario.plan;

import lombok.Builder;
import lombok.Value;
import org.jspecify.annotations.Nullable;

@Value
@Builder
public class RouteMapping {
    String forwardRouteId;
    @Nullable String reverseRouteId;
}