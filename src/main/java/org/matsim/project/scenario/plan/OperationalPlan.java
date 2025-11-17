package org.matsim.project.scenario.plan;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class OperationalPlan {
    @JsonProperty("trainVolumePeriod")
    private int trainVolumePeriod;

    @JsonProperty("minimumHeadway")
    private Map<ProductType, Integer> minimumHeadway;

    @JsonProperty("operationModes")
    private List<OperationMode> operationModes;
}
