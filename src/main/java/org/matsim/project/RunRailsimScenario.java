package org.matsim.project;

import lombok.extern.log4j.Log4j2;
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

    @CommandLine.Option(names = {"-t", "--simulation-time"}, description = "Total simulation time in seconds.", defaultValue = "10800")
    private int simulationTime;

    @CommandLine.Option(names = {"-a", "--analysis-start"}, description = "Start time of the analysis window in seconds (excludes warm-up).", defaultValue = "3600")
    private int analysisStartTime;

    @CommandLine.Option(names = {"-A", "--analysis-duration"}, description = "Duration of the analysis window in seconds.", defaultValue = "3600")
    private int analysisDuration;

    @CommandLine.Option(names = {"-w", "--worker-threads"}, description = "The number of worker threads in the simulation executor (default is number of cores).", defaultValue = "-1")
    private int workerThreads;

    @CommandLine.Option(names = {"-d", "--departure-sampling"}, description = "Departure sampling strategy (RANDOM, HEADWAY)", defaultValue = "RANDOM")
    private ProjectConfig.DepartureSampling departureSampling;

    @CommandLine.Option(names = {"--overwrite"}, description = "Overwrite output directory if it exists", defaultValue = "false")
    private boolean overwriteOutput;

    static void main(String[] args) {
        System.setProperty("matsim.preferLocalDtds", "true");

        int exitCode = new CommandLine(new RunRailsimScenario()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws IOException {
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
                .simulationTime(simulationTime)
                .analysisStartTime(analysisStartTime)
                .analysisDuration(analysisDuration)
                .workerThreads(workerThreads)
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
                .append(" -t ")
                .append(config.getSimulationTime())
                .append(" -a ")
                .append(config.getAnalysisStartTime())
                .append(" -A ")
                .append(config.getAnalysisDuration())
                .append(" -w ")
                .append(config.getWorkerThreads())
                .append(" -d ")
                .append(config.getDepartureSampling())
                .append(" --overwrite ")
                .append(config.isOverwriteOutput());
        log.info("Running with command-line equivalent: {}", sb);
    }
}