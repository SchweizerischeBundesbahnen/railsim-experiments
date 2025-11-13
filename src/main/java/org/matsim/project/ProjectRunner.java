package org.matsim.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.matsim.core.utils.io.IOUtils;
import org.matsim.project.scenario.BuildingBlock;
import org.matsim.project.simulation.RailsimSimulationExecutor;
import org.matsim.project.simulation.RailsimSimulationJob;
import org.matsim.project.simulation.RailsimSimulationResult;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
 *     <li>Delegating the final parallel summarization of results.</li>
 * </ol>
 */
@Log4j2
@RequiredArgsConstructor
public class ProjectRunner {

    private final ProjectConfig config;

    /**
     * Executes the full project pipeline.
     */
    public void run() throws IOException {
        long startTime = System.currentTimeMillis();
        log.info("Starting project runner with {} building blocks.", config.getBuildingBlocks().size());

        // initialization and file system setup
        prepareOutputDirectory();
        Map<BuildingBlock, BuildingBlockWorkflow> workflows = createWorkflows();

        // job preparation (the job sampling is already parallel, so do not parallelize here again)
        log.info("Preparing simulation jobs for all building blocks...");
        List<RailsimSimulationJob> allJobs = workflows.values().stream().flatMap(workflow -> {
            try {
                return workflow.prepareJobs().stream();
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to prepare jobs for " + workflow.getBuildingBlock(), e);
            }
        }).toList();

        if (allJobs.isEmpty()) {
            log.warn("No simulation jobs were generated. Skipping execution and finishing.");
            return;
        }

        // parallel simulation execution
        RailsimSimulationExecutor executor = new RailsimSimulationExecutor(
                // collect post-processing task factories from all workflows
                workflows.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, entry -> {
                    try {
                        return entry.getValue().createPostProcessingTaskFactories();
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                })));
        List<RailsimSimulationResult> allResults = executor.runAll(allJobs);

        // write summary per building block
        log.info("Writing summary reports for all building blocks in parallel...");
        Map<BuildingBlock, List<RailsimSimulationResult>> resultsByBlock = allResults.stream()
                .collect(Collectors.groupingBy(result -> result.getJob().getBuildingBlock()));

        resultsByBlock.entrySet().parallelStream().forEach(entry -> {
            try {
                workflows.get(entry.getKey()).writeSummary(entry.getValue());
            } catch (IOException e) {
                log.error("Failed to write summary for building block {}", entry.getKey(), e);
            }
        });

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
        saveJson(config, outputDir.resolve("output_project_config.json"));
    }

    private Map<BuildingBlock, BuildingBlockWorkflow> createWorkflows() {
        return config.getBuildingBlocks()
                .stream()
                .collect(Collectors.toMap(Function.identity(), block -> new BuildingBlockWorkflow(config, block)));
    }

    private void saveJson(Object object, Path path) throws IOException {
        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        mapper.writeValue(path.toFile(), object);
        log.info("Project configuration saved for reproducibility: {}", path);
    }
}