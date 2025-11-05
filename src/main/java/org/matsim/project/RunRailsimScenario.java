package org.matsim.project;

import lombok.extern.log4j.Log4j2;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.Configurator;
import org.matsim.project.scenario.BuildingBlock;
import picocli.CommandLine;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;

@Log4j2
@CommandLine.Command(name = "run-railsim-scenario", mixinStandardHelpOptions = true, description = "Runs Railsim simulation scenarios with configurable parameters.")
public class RunRailsimScenario implements Callable<Integer> {

    @CommandLine.Option(names = {"-o", "--output"}, description = "Output directory", required = true)
    private String outputDirectory;

    @CommandLine.Option(names = {"-b", "--building-blocks"}, description = "Comma-separated building blocks (e.g., UC1_BB1,UC1_BB2), or '*' for all.", defaultValue = "*")
    private String buildingBlocksInput;

    @CommandLine.Option(names = {"-s", "--samples"}, description = "Number of samples per sub-variant", defaultValue = "5")
    private int samplesPerSubvariant;

    @CommandLine.Option(names = {"-h", "--hours"}, description = "Simulation duration in hours", defaultValue = "3")
    private int simulationHours;

    @CommandLine.Option(names = {"-d", "--departure-sampling"}, description = "Departure sampling strategy (RANDOM, HEADWAY)", defaultValue = "RANDOM")
    private ProjectConfig.DepartureSampling departureSampling;

    @CommandLine.Option(names = {"-l", "--matsim-log-level"}, description = "MATSim log level (INFO, WARN, ERROR, DEBUG)", defaultValue = "INFO")
    private String matsimLogLevel;

    @CommandLine.Option(names = {"--overwrite"}, description = "Overwrite output directory if it exists", defaultValue = "false")
    private boolean overwriteOutput;

    public static void main(String[] args) {
        int exitCode = new CommandLine(new RunRailsimScenario()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws IOException {
        Level level = Level.valueOf(matsimLogLevel.toUpperCase());
        Configurator.setLevel("org.matsim", level);
        Configurator.setLevel("ch.sbb.matsim", level);
        Configurator.setLevel("org.matsim.project", Level.INFO);

        List<BuildingBlock> buildingBlocks;
        if ("*".equals(buildingBlocksInput)) {
            buildingBlocks = List.of(BuildingBlock.values());
            log.info("No specific building blocks provided; running all {} available blocks", buildingBlocks.size());
        } else {
            buildingBlocks = Arrays.stream(buildingBlocksInput.split(","))
                    .map(String::trim)
                    .map(BuildingBlock::valueOf)
                    .toList();
        }

        ProjectConfig config = ProjectConfig.builder()
                .outputDirectory(outputDirectory)
                .overwriteOutput(overwriteOutput)
                .buildingBlocks(buildingBlocks)
                .samplesPerSubvariant(samplesPerSubvariant)
                .simulationHours(simulationHours)
                .departureSampling(departureSampling)
                .build();

        printConfiguration(config);
        new ProjectRunner(config).run();

        return 0;
    }

    private void printConfiguration(ProjectConfig config) {
        StringBuilder sb = new StringBuilder("-o ").append(config.getOutputDirectory())
                .append(" -b ")
                .append(config.getBuildingBlocks()
                        .stream()
                        .map(BuildingBlock::name)
                        .reduce((a, b) -> a + "," + b)
                        .orElse(""))
                .append(" -s ")
                .append(config.getSamplesPerSubvariant())
                .append(" -h ")
                .append(config.getSimulationHours())
                .append(" -d ")
                .append(config.getDepartureSampling())
                .append(" -l ")
                .append(matsimLogLevel)
                .append(" --overwrite ")
                .append(config.isOverwriteOutput());
        log.info("Configuration: {}", sb);
    }
}