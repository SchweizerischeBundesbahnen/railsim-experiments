package org.matsim.project;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
enum ProjectStructure {
    TRAIN_RUN_CALCULATION,
    SCHEDULE_SAMPLING,
    SIMULATION_JOBS,
    SIMULATION_OUTPUT;

    public String getDirectory() {
        return String.format("%02d", this.ordinal() + 1) + "_" + this.name().toLowerCase();
    }
}