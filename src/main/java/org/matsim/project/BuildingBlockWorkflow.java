package org.matsim.project;

import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import org.matsim.api.core.v01.Scenario;
import org.matsim.project.analysis.delay.TrainDelayAnalysisFactory;
import org.matsim.project.analysis.headway.MinimumHeadwayAnalysisFactory;
import org.matsim.project.analysis.utilization.UtilizationAnalysisFactory;
import org.matsim.project.scenario.BuildingBlock;
import org.matsim.project.scenario.UseCase;
import org.matsim.project.scenario.plan.OperationalPlan;
import org.matsim.project.scenario.plan.OperationalPlanReader;
import org.matsim.project.simulation.PostProcessingTaskFactory;
import org.matsim.project.simulation.RailsimSimulationJobGenerator;
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
    public RailsimSimulationJobGenerator prepareJobGenerator() throws IOException {
        log.info("Starting job preparation for: {}", buildingBlock.name());

        // prepare the file system
        ensureUseCaseResourcesAreCopied();
        Files.createDirectories(paths.getBuildingBlockDirectory());

        // calculate base train run times to get a template scenario
        Path trainRunCalcPath = paths.getAndEnsure(ProjectPaths.Folder.TRAIN_RUN_CALCULATION);
        Scenario templateScenario = new TrainRunCalculator(buildingBlock, trainRunCalcPath).run();

        // load operational plan and sample schedules and generate simulation job generators
        Path operationalPlanPath = ResourceLoader.getPath(buildingBlock.getUseCase().getOperationalPlanPath());
        OperationalPlan operationalPlan = new OperationalPlanReader().read(operationalPlanPath);

        return new RailsimSimulationJobGenerator(config, paths, operationalPlan, buildingBlock, templateScenario);
    }

    /**
     * Creates the list of post-processing factories that define the analysis pipeline.
     */
    public List<PostProcessingTaskFactory> createPostProcessingTaskFactories() throws IOException {
        return List.of(new TrainDelayAnalysisFactory(), new MinimumHeadwayAnalysisFactory(),
                new UtilizationAnalysisFactory(config.getAnalysisStartTime(), config.getAnalysisEndTime()));
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