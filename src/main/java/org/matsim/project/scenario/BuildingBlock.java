package org.matsim.project.scenario;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
@Getter
public enum BuildingBlock {

    UC0_BB1(UseCase.UC_0, "/scenarios/use_case_0/building_block_1"),
    UC0_BB2(UseCase.UC_0, "/scenarios/use_case_0/building_block_2"),
    UC0_BB3(UseCase.UC_0, "/scenarios/use_case_0/building_block_3"),
    UC0_BB4(UseCase.UC_0, "/scenarios/use_case_0/building_block_4"),
    UC0_BB5(UseCase.UC_0, "/scenarios/use_case_0/building_block_5"),
    UC0_BB6(UseCase.UC_0, "/scenarios/use_case_0/building_block_6"),
    UC1_BB1(UseCase.UC_1, "/scenarios/use_case_1/building_block_1"),
    UC1_BB2(UseCase.UC_1, "/scenarios/use_case_1/building_block_2"),
    UC1_BB3(UseCase.UC_1, "/scenarios/use_case_1/building_block_3"),
    UC2_BB1(UseCase.UC_2, "/scenarios/use_case_2/building_block_1"),
    UC2_BB2(UseCase.UC_2, "/scenarios/use_case_2/building_block_2");

    private final UseCase useCase;
    private final String inputPath;

    public String getConfigFilePath() {
        return inputPath + "/config.xml";
    }

    public String getNetworkFilePath() {
        return inputPath + "/network.xml";
    }

    public String getTransitScheduleFilePath() {
        return inputPath + "/schedule.xml";
    }

    public String getVehiclesFilePath() {
        return inputPath + "/vehicles.xml";
    }
}

