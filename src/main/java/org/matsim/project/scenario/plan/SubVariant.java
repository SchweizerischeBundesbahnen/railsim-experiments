package org.matsim.project.scenario.plan;

import lombok.Data;

import java.util.List;

@Data
public class SubVariant {
    private String id;
    private List<TrainVolume> trainVolumes;
}