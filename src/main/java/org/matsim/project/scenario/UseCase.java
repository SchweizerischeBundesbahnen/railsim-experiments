package org.matsim.project.scenario;

import lombok.Getter;

@Getter
public enum UseCase {
    UC_1("/scenarios/use_case_1/uc1_operational_plan.json"),
    UC_2("/scenarios/use_case_2/uc2_operational_plan.json");

    private final String operationalPlanPath;

    UseCase(String operationalPlanPath) {
        this.operationalPlanPath = operationalPlanPath;
    }
}