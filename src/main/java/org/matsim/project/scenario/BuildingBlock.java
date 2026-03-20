package org.matsim.project.scenario;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
@Getter
public enum BuildingBlock {

    /**
     * Use case 0 - Fixed block calibration: 500m fixed block before and after a pseudo-building block element.
     */
    UC0_BB1(UseCase.UC_0, "/scenarios/use_case_0/building_block_1"),

    /**
     * Use case 0 - Moving block calibration: 500m moving block before and after a pseudo-building block element.
     */
    UC0_BB2(UseCase.UC_0, "/scenarios/use_case_0/building_block_2"),

    /**
     * Use case 1 - Station with one platform track.
     */
    UC1_BB1(UseCase.UC_1, "/scenarios/use_case_1/building_block_1"),

    /**
     * Use case 1 - Station with two platform tracks.
     */
    UC1_BB2(UseCase.UC_1, "/scenarios/use_case_1/building_block_2"),

    /**
     * Use case 1 - Station with three platform tracks.
     */
    UC1_BB3(UseCase.UC_1, "/scenarios/use_case_1/building_block_3"),

    /**
     * Use case 2 - Crossing with a flat layout.
     */
    UC2_BB1(UseCase.UC_2, "/scenarios/use_case_2/building_block_1"),

    /**
     * Use case 2 - Crossing with a grade-separated layout.
     */
    UC2_BB2(UseCase.UC_2, "/scenarios/use_case_2/building_block_2"),

    /**
     * Use case 2 - Reversing with side track.
     */
    UC3_BB1(UseCase.UC_3, "/scenarios/use_case_3/building_block_1");

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

