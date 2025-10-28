package org.matsim.project.scenario.plan;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
public class OperationalPlan {
    @JsonProperty("operationalPlan")
    private List<OperationMode> operationModes;
}