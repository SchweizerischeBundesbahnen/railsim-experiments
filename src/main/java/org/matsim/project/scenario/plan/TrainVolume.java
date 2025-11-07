package org.matsim.project.scenario.plan;

import lombok.Data;

@Data
public class TrainVolume {
    private ProductType product;
    private String route;
    private int amount;
}
