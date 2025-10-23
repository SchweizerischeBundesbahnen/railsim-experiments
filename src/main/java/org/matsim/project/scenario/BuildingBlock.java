package org.matsim.project.scenario;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
@Getter
public enum BuildingBlock {

    UC1_BB2(UseCase.UC_1, "/scenarios/use_case_1/building_block_2/input/config.xml"),
    UC1_BB3(UseCase.UC_1, "/scenarios/use_case_1/building_block_3/input/config.xml"),
    UC1_BB4(UseCase.UC_1, "/scenarios/use_case_1/building_block_4/input/config.xml"),
    UC2_BB1(UseCase.UC_2, "/scenarios/use_case_2/building_block_1/input/config.xml");

    private final UseCase useCase;
    private final String configFilePath;
}

