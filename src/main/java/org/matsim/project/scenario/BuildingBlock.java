package org.matsim.project.scenario;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
@Getter
public enum BuildingBlock {

    UC1_BB2(UseCase.UC_1, "/scenarios/use_case_1/building_block_2/input"),
    UC1_BB3(UseCase.UC_1, "/scenarios/use_case_1/building_block_3/input"),
    UC1_BB4(UseCase.UC_1, "/scenarios/use_case_1/building_block_4/input"),
    UC2_BB1(UseCase.UC_2, "/scenarios/use_case_2/building_block_1/input");

    private final UseCase useCase;
    private final String inputPath;

    public String getConfigFilePath() {
        return inputPath + "/config.xml";
    }

    public String getNetworkFilePath() {
        return inputPath + "/network.xml";
    }
}

