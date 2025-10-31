package org.matsim.project;

import lombok.extern.log4j.Log4j2;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.Configurator;
import org.matsim.project.scenario.BuildingBlock;

import java.io.IOException;
import java.util.List;

@Log4j2
public final class RunRailsimScenario {

    public static final String OUTPUT_DIRECTORY = "//filer22l/K-UE220L/IFI/FTO/SAM.A13783/04_projects/42_gzb_railsim/output_20251030_test_all_rerouting";
    public static final List<BuildingBlock> BUILDING_BLOCKS = List.of(BuildingBlock.UC1_BB1, BuildingBlock.UC1_BB2,
            BuildingBlock.UC1_BB3, BuildingBlock.UC1_BB4);
    public static final int SAMPLES_PER_SUBVARIANT = 5;
    public static final ProjectConfig.DepartureSampling DEPARTURE_SAMPLING = ProjectConfig.DepartureSampling.RANDOM;
    private static final boolean MUTE_MATSIM = true;

    public static void main(String[] args) throws IOException {

        if (MUTE_MATSIM) {
            // set matsim logs to warn, re-enable the current project
            Configurator.setLevel("org.matsim", Level.WARN);
            Configurator.setLevel("ch.sbb.matsim", Level.WARN);
            Configurator.setLevel("org.matsim.project", Level.INFO);
        }

        // configure project
        ProjectConfig config = ProjectConfig.builder()
                .outputDirectory(OUTPUT_DIRECTORY)
                .overwriteOutput(true)
                .buildingBlocks(BUILDING_BLOCKS)
                .samplesPerSubvariant(SAMPLES_PER_SUBVARIANT)
                .departureSampling(DEPARTURE_SAMPLING)
                .build();

        // run project pipeline
        new ProjectRunner(config).run();
    }
}