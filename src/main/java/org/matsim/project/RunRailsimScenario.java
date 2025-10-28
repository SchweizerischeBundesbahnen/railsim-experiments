package org.matsim.project;

import lombok.extern.log4j.Log4j2;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.Configurator;
import org.matsim.api.core.v01.Scenario;
import org.matsim.project.analysis.TrainDelayAnalysisFactory;
import org.matsim.project.analysis.TrainDelaySummaryWriter;
import org.matsim.project.sampling.SimulationJobSampler;
import org.matsim.project.sampling.strategy.DepartureSamplingStrategy;
import org.matsim.project.sampling.strategy.RandomDepartureSampling;
import org.matsim.project.scenario.BuildingBlock;
import org.matsim.project.scenario.UseCase;
import org.matsim.project.scenario.plan.OperationalPlan;
import org.matsim.project.scenario.plan.OperationalPlanReader;
import org.matsim.project.simulation.PostProcessingTaskFactory;
import org.matsim.project.simulation.RailsimSimulationExecutor;
import org.matsim.project.simulation.RailsimSimulationJob;
import org.matsim.project.simulation.RailsimSimulationResult;
import org.matsim.project.trainrun.TrainRunCalculator;
import org.matsim.project.utils.ResourceLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

@Log4j2
public final class RunRailsimScenario {

    private static final BuildingBlock BUILDING_BLOCK = BuildingBlock.UC1_BB2;

    private static final String OUTPUT_DIRECTORY = "results";
    private static final int N_SAMPLES_PER_SUBVARIANT = 20;
    private static final long SEED = 123;
    private static final DepartureSamplingStrategy DEPARTURE_SAMPLING_STRATEGY = new RandomDepartureSampling();

    public static void main(String[] args) throws IOException {

        // set matsim logs to warn, re-enable the current project
        Configurator.setLevel("org.matsim", Level.WARN);
        Configurator.setLevel("ch.sbb.matsim", Level.WARN);
        Configurator.setLevel("org.matsim.project", Level.INFO);

        // setup paths and directories
        Path templateConfigFilePath = ResourceLoader.getPath(BUILDING_BLOCK.getConfigFilePath());
        Path operationalPlanPath = ResourceLoader.getPath(BUILDING_BLOCK.getUseCase().getOperationalPlanPath());

        // calculate base train run times from template schedule
        Path trainRunCalcPath = getAndEnsure(ProjectFolder.TRAIN_RUN_CALCULATION);
        Scenario templateScenario = new TrainRunCalculator(templateConfigFilePath, trainRunCalcPath).run();

        // create simulation jobs based on the operational plan
        Path scheduleSamplingOutputFolderPath = getAndEnsure(ProjectFolder.SCHEDULE_SAMPLING);
        Path simulationJobConfigOutputFolderPath = getAndEnsure(ProjectFolder.SIMULATION_JOB_CONFIG);
        Path simulationRunOutputFolderPath = getAndEnsure(ProjectFolder.SIMULATION_RUN_OUTPUT);
        OperationalPlan operationalPlan = new OperationalPlanReader().read(operationalPlanPath);
        SimulationJobSampler sampler = new SimulationJobSampler(SEED, templateConfigFilePath, templateScenario,
                BUILDING_BLOCK, operationalPlan);
        List<RailsimSimulationJob> jobs = sampler.sample(N_SAMPLES_PER_SUBVARIANT, DEPARTURE_SAMPLING_STRATEGY,
                scheduleSamplingOutputFolderPath, simulationJobConfigOutputFolderPath, simulationRunOutputFolderPath);

        // define post-processing pipeline and run all simulations in parallel
        Path analysisOutputFolderPath = getAndEnsure(ProjectFolder.ANALYSIS);
        List<PostProcessingTaskFactory> taskFactories = List.of(
                new TrainDelayAnalysisFactory(analysisOutputFolderPath));
        RailsimSimulationExecutor simulator = new RailsimSimulationExecutor(taskFactories);
        List<RailsimSimulationResult> results = simulator.runAll(jobs);

        // write final aggregated analysis reports
        TrainDelaySummaryWriter summaryWriter = new TrainDelaySummaryWriter(results);
        summaryWriter.write(analysisOutputFolderPath);

        log.info("Workflow finished. Summary report and detailed files are in: {}", analysisOutputFolderPath);
    }

    private static Path getAndEnsure(ProjectFolder projectFolder) throws IOException {
        UseCase useCase = BUILDING_BLOCK.getUseCase();

        // ./results
        Path outputPath = Paths.get(OUTPUT_DIRECTORY)
                // ./uc_1
                .resolve(useCase.name().toLowerCase())
                // ./uc1_bb1
                .resolve(BUILDING_BLOCK.name().toLowerCase())
                // ./01_ , ./02_, ...
                .resolve(projectFolder.getDirectory());

        Files.createDirectories(outputPath);
        return outputPath;
    }
}