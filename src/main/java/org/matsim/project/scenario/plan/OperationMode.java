package org.matsim.project.scenario.plan;

import lombok.Data;

import java.util.List;

@Data
public class OperationMode {
    private OperationModeType name;
    private String description;
    private List<ProductType> products;
    private List<Variant> variants;
}