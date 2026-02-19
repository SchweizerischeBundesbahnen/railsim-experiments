package org.matsim.project.analysis.utilization;

import lombok.Builder;
import lombok.Value;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;

@Value
@Builder
public class UtilizationInfo {
    Id<Link> linkId;
    double observationTime;
    double exhaustedTime;
    double utilization;
}