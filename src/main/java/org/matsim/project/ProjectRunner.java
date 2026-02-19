package org.matsim.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.AllArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.jspecify.annotations.NonNull;
import org.matsim.core.utils.io.IOUtils;
import org.matsim.project.analysis.RunSummaryWriter;
import org.matsim.project.scenario.BuildingBlock;
import org.matsim.project.simulation.PostProcessingTaskFactory;
import org.matsim.project.simulation.RailsimSimulationExecutor;
import org.matsim.project.simulation.RailsimSimulationJobGenerator;
import org.matsim.project.simulation.RailsimSimulationResult;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Orchestrates the entire simulation project from configuration to final results.
 * <p>
 * Entry point for the simulation workflow, responsible for managing the high-level stages:
 * <ol>
 *     <li>Initializing the environment and workflows.</li>
 *     <li>Delegating parallel job preparation to the appropriate workflows.</li>
 *     <li>Executing all generated simulation jobs in parallel.</li>
 *     <li>Writing the final summarization of results.</li>
 * </ol>
 */
@Log4j2
@AllArgsConstructor
public class ProjectRunner {

    public static final String OUTPUT_PROJECT_CONFIG_JSON = "output_project_config.json";
    public static final String OUTPUT_RUN_INFO_JSON = "output_run_info.json";

    private ProjectConfig config;

    /**
     * Executes the full project pipeline.
     */
    public void run() throws IOException {
        long startTime = System.currentTimeMillis();

        if (config.isReconstructionMode()) {
            // reconstruction mode: read original config and set parameters for consistent reconstruction
            log.info("Starting project runner for reconstruction of {} runs.", config.getReconstructRuns().size());
            ProjectConfig originalConfig =
                    readConfig(Path.of(config.getOutputDirectory()).resolve(OUTPUT_PROJECT_CONFIG_JSON));
            config = config.toBuilder()
                    .seed(originalConfig.getSeed())
                    .samplesPerSubvariant(originalConfig.getSamplesPerSubvariant())
                    .simulationTime(originalConfig.getSimulationTime())
                    .analysisStartTime(originalConfig.getAnalysisStartTime())
                    .analysisDuration(originalConfig.getAnalysisDuration())
                    .departureSampling(originalConfig.getDepartureSampling())
                    .buildingBlocks(originalConfig.getBuildingBlocks())
                    .build();

        } else {
            // initialization and file system setup
            log.info("Starting project runner for {} building blocks.", config.getBuildingBlocks().size());
            prepareOutputDirectory();
        }

        Map<BuildingBlock, BuildingBlockWorkflow> workflows = createWorkflows();

        log.info("Preparing simulation job generators for all building blocks...");
        List<RailsimSimulationJobGenerator> generators = workflows.values().parallelStream().map(workflow -> {
            try {
                return workflow.prepareJobGenerator();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }).toList();

        // collect post-processing task factories
        Map<BuildingBlock, List<PostProcessingTaskFactory>> taskFactories =
                workflows.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, entry -> {
                    try {
                        return entry.getValue().createPostProcessingTaskFactories();
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }));

        // parallel simulation execution (default is number if cores)
        RailsimSimulationExecutor executor = config.getWorkerThreads() == -1 ?
                new RailsimSimulationExecutor(taskFactories) :
                new RailsimSimulationExecutor(config.getWorkerThreads(), taskFactories);
        List<RailsimSimulationResult> allResults = executor.runAll(generators);

        // write global summary to the root output directory
        new RunSummaryWriter(allResults, config.getAnalysisStartTime(), config.getAnalysisEndTime()).write(
                config.isReconstructionMode() ? RunSummaryWriter.Type.RECONSTRUCT : RunSummaryWriter.Type.RUN,
                Path.of(config.getOutputDirectory()));

        // clean empty directories
        if (config.isCleanupRuns()) {
            cleanEmptyDirectories(Path.of(config.getOutputDirectory()));
        }

        long duration = (System.currentTimeMillis() - startTime) / 1000;
        log.info("==========================================================================");
        log.info("PROJECT WORKFLOW FINISHED in {} seconds.", duration);
        log.info("Results are located in: {}", config.getOutputDirectory());
        log.info("==========================================================================");
    }

    private void prepareOutputDirectory() throws IOException {
        Path outputDir = Path.of(config.getOutputDirectory());

        if (Files.exists(outputDir)) {
            if (config.isOverwriteOutput()) {
                log.warn("Output directory {} already exists. Deleting it as per configuration.", outputDir);
                IOUtils.deleteDirectoryRecursively(outputDir);
            } else {
                throw new IOException(
                        "Output directory " + outputDir + " already exists. Set overwriteOutput=true to delete it.");
            }
        }

        Files.createDirectories(outputDir);
        createAndSaveRunInfo(outputDir.resolve(OUTPUT_RUN_INFO_JSON));
        saveJson(config, outputDir.resolve(OUTPUT_PROJECT_CONFIG_JSON));
    }

    private Map<BuildingBlock, BuildingBlockWorkflow> createWorkflows() {
        return config.getBuildingBlocks()
                .stream()
                .collect(Collectors.toMap(Function.identity(), block -> new BuildingBlockWorkflow(config, block)));
    }

    private void createAndSaveRunInfo(Path path) throws IOException {
        Properties gitProperties = new Properties();
        try (InputStream stream = ProjectRunner.class.getResourceAsStream("/git.properties")) {
            if (stream != null) {
                gitProperties.load(stream);
            } else {
                log.warn(
                        "Could not find git.properties file. Run 'mvn package' to generate it. Git info will be missing.");
            }
        }

        String implementationVersion =
                Optional.ofNullable(ProjectRunner.class.getPackage().getImplementationVersion()).orElse("dev-snapshot");
        RunInfo runInfo = RunInfo.builder()
                .executionTimestamp(Instant.now().toString())
                .executedBy(System.getProperty("user.name"))
                .jarVersion(implementationVersion)
                .gitBranch(gitProperties.getProperty("git.branch", "unknown"))
                .gitCommitId(gitProperties.getProperty("git.commit.id.abbrev", "unknown"))
                .gitCommitTime(gitProperties.getProperty("git.commit.time", "unknown"))
                .gitTags(gitProperties.getProperty("git.tags", "none"))
                .build();

        saveJson(runInfo, path);
    }

    private void saveJson(Object object, Path path) throws IOException {
        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        mapper.writeValue(path.toFile(), object);
        log.info("Project configuration saved for reproducibility: {}", path);
    }

    private ProjectConfig readConfig(Path path) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        ProjectConfig config = mapper.readValue(path.toFile(), ProjectConfig.class);
        log.info("Project configuration loaded from: {}", path);
        return config;
    }


    private void cleanEmptyDirectories(Path root) {
        log.info("Scanning for and removing empty directories in {}", root);
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult postVisitDirectory(@NonNull Path dir, IOException exc) throws IOException {
                    if (exc == null && !dir.equals(root)) {
                        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
                            if (!stream.iterator().hasNext()) {
                                Files.delete(dir);
                            }
                        }
                    }

                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("Failed to clean up empty directories in {}", root, e);
        }
    }
}