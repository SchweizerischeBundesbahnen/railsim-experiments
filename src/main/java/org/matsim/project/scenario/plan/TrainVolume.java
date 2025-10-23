package org.matsim.project.scenario.plan;

import lombok.Data;

@Data
public class TrainVolume {
    private ProductType product;
    private String fromStop;
    private String toStop;
    private int amount;
}