package org.matsim.project;

import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import org.matsim.api.core.v01.Scenario;
import org.matsim.project.analysis.delay.TrainDelayAnalysisFactory;
import org.matsim.project.analysis.headway.MinimumHeadwayAnalysisFactory;
import org.matsim.project.analysis.utilization.UtilizationAnalysisFactory;
import org.matsim.project.sampling.SimulationJobSampler;
import org.matsim.project.scenario.BuildingBlock;
import org.matsim.project.scenario.UseCase;
import org.matsim.project.scenario.plan.OperationalPlan;
import org.matsim.project.scenario.plan.OperationalPlanReader;
import org.matsim.project.simulation.PostProcessingTaskFactory;
import org.matsim.project.simulation.RailsimSimulationJob;
import org.matsim.project.trainrun.TrainRunCalculator;
import org.matsim.project.utils.ResourceLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Encapsulates the entire end-to-end process for a single {@link BuildingBlock}.
 * This class is responsible for the detailed steps of job preparation and result
 * finalization for its assigned block.
 */
@Log4j2
@Getter
public class BuildingBlockWorkflow {

    private final ProjectConfig config;
    private final BuildingBlock buildingBlock;
    private final ProjectPaths paths;

    public BuildingBlockWorkflow(ProjectConfig config, BuildingBlock buildingBlock) {
        this.config = config;
        this.buildingBlock = buildingBlock;
        this.paths = new ProjectPaths(config.getOutputDirectory(), buildingBlock);
    }

    /**
     * Prepares all simulation jobs for this building block.
     * This method is thread-safe and is designed to be called in parallel.
     */
    public List<RailsimSimulationJob> prepareJobs() throws IOException {
        log.info("Starting job preparation for: {}", buildingBlock.name());

        // prepare the file system
        ensureUseCaseResourcesAreCopied();
        Files.createDirectories(paths.getBuildingBlockDirectory());

        // calculate base train run times to get a template scenario
        Path trainRunCalcPath = paths.getAndEnsure(ProjectPaths.Folder.TRAIN_RUN_CALCULATION);
        Scenario templateScenario = new TrainRunCalculator(buildingBlock, trainRunCalcPath).run();

        // load operational plan and sample schedules and generate simulation jobs
        Path scheduleSamplingPath = paths.getAndEnsure(ProjectPaths.Folder.SCHEDULE_SAMPLING);
        Path jobConfigPath = paths.getAndEnsure(ProjectPaths.Folder.SIMULATION_JOB_CONFIG);
        Path simulationRunOutputPath = paths.getAndEnsure(ProjectPaths.Folder.SIMULATION_RUN_OUTPUT);

        Path operationalPlanPath = ResourceLoader.getPath(buildingBlock.getUseCase().getOperationalPlanPath());
        Path templateConfigFilePath = ResourceLoader.getPath(buildingBlock.getConfigFilePath());
        OperationalPlan operationalPlan = new OperationalPlanReader().read(operationalPlanPath);
        SimulationJobSampler sampler =
                new SimulationJobSampler(config.getSeed(), templateConfigFilePath, templateScenario, buildingBlock,
                        operationalPlan);

        return sampler.sample(config.getSamplesPerSubvariant(), config.getSimulationTime(),
                config.getDepartureSamplingStrategy(), scheduleSamplingPath, jobConfigPath, simulationRunOutputPath);
    }

    /**
     * Creates the list of post-processing factories that define the analysis pipeline.
     */
    public List<PostProcessingTaskFactory> createPostProcessingTaskFactories() throws IOException {
        Path analysisOutputPath = paths.getAndEnsure(ProjectPaths.Folder.ANALYSIS);

        return List.of(new TrainDelayAnalysisFactory(analysisOutputPath),
                new MinimumHeadwayAnalysisFactory(analysisOutputPath),
                new UtilizationAnalysisFactory(analysisOutputPath, config.getAnalysisStartTime(),
                        config.getAnalysisEndTime()));
    }

    /**
     * Ensures the operational plan for the associated use case is copied to the output directory
     * for reproducibility. This method is thread-safe and uses synchronization to prevent
     * race conditions, ensuring the file is copied only once per use case.
     */
    private void ensureUseCaseResourcesAreCopied() throws IOException {
        UseCase useCase = buildingBlock.getUseCase();
        Path destDir = paths.getUseCaseDirectory();
        Path sourcePlanPath = ResourceLoader.getPath(useCase.getOperationalPlanPath());
        Path destPlanPath = destDir.resolve(sourcePlanPath.getFileName());

        // synchronize on the use case enum to prevent a race condition where multiple threads
        // for the same use case (e.g., UC1_BB1, UC1_BB2) try to copy the file simultaneously
        synchronized (useCase) {
            if (Files.notExists(destPlanPath)) {
                Files.createDirectories(destDir);
                Files.copy(sourcePlanPath, destPlanPath, StandardCopyOption.REPLACE_EXISTING);
                log.info("Copied operational plan for {} to {}", useCase, destDir);
            }
        }
    }
}