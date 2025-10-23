package org.matsim.project.scenario.plan;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum ProductType {
    @JsonProperty("FV") FV, // Fernverkehr
    @JsonProperty("RV") RV, // Regionalverkehr
    @JsonProperty("GV") GV // Güterverkehr
}