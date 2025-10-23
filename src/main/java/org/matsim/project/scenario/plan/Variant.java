package org.matsim.project.scenario.plan;

import lombok.Data;

import java.util.List;

@Data
public class Variant {
    private String id;
    private List<DistributionShare> distribution;
    private List<SubVariant> subVariants;
}