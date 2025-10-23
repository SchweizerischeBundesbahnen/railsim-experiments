package org.matsim.project.scenario.plan;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum OperationModeType {
    @JsonProperty("KM") KM, // Kernnetz-Mischbetrieb
    @JsonProperty("R") R,  // Restnetz
    @JsonProperty("M") M  // Metrobetrieb
}