package org.matsim.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.log4j.Log4j2;
import org.matsim.project.scenario.BuildingBlock;
import picocli.CommandLine;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;

@Log4j2
@CommandLine.Command(name = "railsim-scenario", mixinStandardHelpOptions = true, subcommands = {
        RunRailsimScenario.RunCommand.class, RunRailsimScenario.ReconstructCommand.class})
public class RunRailsimScenario {

    static void main(String[] args) {
        System.setProperty("matsim.preferLocalDtds", "true");
        int exitCode = new CommandLine(new RunRailsimScenario()).execute(args);
        System.exit(exitCode);
    }

    @CommandLine.Command(name = "run", description = "Executes a new simulation experiment.")
    static class RunCommand implements Callable<Integer> {

        @CommandLine.Option(names = {"-o", "--output"}, description = "Output directory", required = true)
        private String outputDirectory;

        @CommandLine.Option(names = {"-b",
                "--building-blocks"}, description = "Comma-separated building blocks (e.g., UC1_BB1,UC1_BB2), or '*' for all.", defaultValue = "*")
        private String buildingBlocksInput;

        @CommandLine.Option(names = {"-s",
                "--samples"}, description = "Number of samples per operating mode and building block", defaultValue = "5")
        private int samplesPerSubvariant;

        @CommandLine.Option(names = {"-t",
                "--simulation-time"}, description = "Total simulation time in seconds.", defaultValue = "10800")
        private int simulationTime;

        @CommandLine.Option(names = {"-a",
                "--analysis-start"}, description = "Start time of the analysis window in seconds (excludes warm-up).", defaultValue = "3600")
        private int analysisStartTime;

        @CommandLine.Option(names = {"-A",
                "--analysis-duration"}, description = "Duration of the analysis window in seconds.", defaultValue = "3600")
        private int analysisDuration;

        @CommandLine.Option(names = {"-w",
                "--worker-threads"}, description = "The number of worker threads in the simulation executor (default is number of cores).", defaultValue = "-1")
        private int workerThreads;

        @CommandLine.Option(names = {"-d",
                "--departure-sampling"}, description = "Departure sampling strategy (RANDOM, HEADWAY)", defaultValue = "RANDOM")
        private ProjectConfig.DepartureSampling departureSampling;

        @CommandLine.Option(names = {
                "--cleanup"}, defaultValue = "false", description = "Delete run outputs after analysis to save space.")
        private boolean cleanupRuns;

        @CommandLine.Option(names = {
                "--overwrite"}, description = "Overwrite output directory if it exists", defaultValue = "false")
        private boolean overwriteOutput;

        @Override
        public Integer call() throws IOException {
            List<BuildingBlock> blocks = "*".equals(buildingBlocksInput) ?
                    List.of(BuildingBlock.values()) :
                    Arrays.stream(buildingBlocksInput.split(","))
                    .map(String::trim)
                    .map(BuildingBlock::valueOf)
                    .toList();

            ProjectConfig config = ProjectConfig.builder()
                    .outputDirectory(outputDirectory)
                    .overwriteOutput(overwriteOutput)
                    .buildingBlocks(blocks)
                    .samplesPerSubvariant(samplesPerSubvariant)
                    .simulationTime(simulationTime)
                    .analysisStartTime(analysisStartTime)
                    .analysisDuration(analysisDuration)
                    .workerThreads(workerThreads)
                    .departureSampling(departureSampling)
                    .cleanupRuns(cleanupRuns)
                    .build();

            new ProjectRunner(config).run();

            return 0;
        }
    }

    @CommandLine.Command(name = "reconstruct", description = "Reconstructs specific runs based on an existing configuration.")
    static class ReconstructCommand implements Callable<Integer> {

        @CommandLine.Option(names = {"-p", "--path"}, required = true, description = "Existing project root path.")
        private Path projectPath;

        @CommandLine.Option(names = {"-r",
                "--runs"}, required = true, split = ",", description = "Specific Run IDs to re-run.")
        private Set<String> runIds;

        @CommandLine.Option(names = {"-w",
                "--worker-threads"}, defaultValue = "-1", description = "The number of worker threads in the simulation executor (default is number of cores).")
        private int workerThreads;

        @Override
        public Integer call() throws IOException {
            File configFile = projectPath.resolve("output_project_config.json").toFile();
            if (!configFile.exists()) {
                throw new IOException("Config not found: " + configFile);
            }

            ProjectConfig originalConfig = new ObjectMapper().readValue(configFile, ProjectConfig.class);

            ProjectConfig reconstructConfig = originalConfig.toBuilder()
                    .outputDirectory(projectPath.toString())
                    .reconstructRuns(runIds)
                    .workerThreads(workerThreads)
                    .overwriteOutput(false)
                    .cleanupRuns(false)
                    .build();

            new ProjectRunner(reconstructConfig).run();

            return 0;
        }
    }
}