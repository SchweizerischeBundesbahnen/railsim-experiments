package org.matsim.project;

import lombok.extern.log4j.Log4j2;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.Configurator;
import org.matsim.api.core.v01.Scenario;
import org.matsim.project.analysis.TrainDelayAnalysis;
import org.matsim.project.sampling.SimulationJobSampler;
import org.matsim.project.sampling.strategy.DepartureSamplingStrategy;
import org.matsim.project.sampling.strategy.RandomDepartureSampling;
import org.matsim.project.scenario.BuildingBlock;
import org.matsim.project.scenario.UseCase;
import org.matsim.project.scenario.plan.OperationalPlan;
import org.matsim.project.scenario.plan.OperationalPlanReader;
import org.matsim.project.simulation.PostSimulationTask;
import org.matsim.project.simulation.RailsimSimulationJob;
import org.matsim.project.simulation.RailsimSimulationResult;
import org.matsim.project.simulation.RailsimSimulator;
import org.matsim.project.trainrun.TrainRunCalculator;
import org.matsim.project.utils.ResourceLoader;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

@Log4j2
public final class RunRailsimScenario {

    private static final BuildingBlock BUILDING_BLOCK = BuildingBlock.UC1_BB2;

    private static final String OUTPUT_DIRECTORY = "results";
    private static final int N_SAMPLES_PER_SUBVARIANT = 3;
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
        List<PostSimulationTask> postSimulationTasks = List.of(new TrainDelayAnalysis());
        RailsimSimulator simulator = new RailsimSimulator(postSimulationTasks);
        List<RailsimSimulationResult> results = simulator.runAll(jobs);

        // write final aggregated analysis reports
        Path analysisOutputFolderPath = getAndEnsure(ProjectFolder.ANALYSIS);
        writeOverallDetailed(analysisOutputFolderPath, results);
        writeOverallSummary(analysisOutputFolderPath, results);
        log.info("Workflow finished. Analysis reports are in: {}", analysisOutputFolderPath);
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

    private static void writeOverallSummary(Path analysisPath,
                                            List<RailsimSimulationResult> results) throws IOException {
        Path summaryPath = analysisPath.resolve("_SUMMARY.csv");
        log.info("Writing overall summary for {} successful runs to {}",
                results.stream().filter(r -> r.getStatus() == RailsimSimulationResult.Status.SUCCESS).count(),
                summaryPath);

        try (BufferedWriter writer = Files.newBufferedWriter(summaryPath)) {
            writer.write("run_id,total_arrival_delay_seconds,total_departure_delay_seconds,delayed_stops_count\n");
            for (RailsimSimulationResult result : results) {
                if (result.getStatus() == RailsimSimulationResult.Status.SUCCESS) {
                    result.getAnalysisResult(TrainDelayAnalysis.RESULT_KEY, TrainDelayAnalysis.DelayReport.class)
                            .ifPresent(report -> {
                                try {
                                    writer.write(String.format("%s,%.2f,%.2f,%d\n", report.getRunId(),
                                            report.getTotalArrivalDelay(), report.getTotalDepartureDelay(),
                                            report.getDelayedStopsCount()));
                                } catch (IOException e) {
                                    throw new RuntimeException(e);
                                }
                            });
                }
            }
        }
    }

    private static void writeOverallDetailed(Path analysisPath,
                                             List<RailsimSimulationResult> results) throws IOException {
        Path detailedPath = analysisPath.resolve("_all_stop_delays.csv");
        log.info("Writing detailed stop delays for all runs to {}", detailedPath);

        try (BufferedWriter writer = Files.newBufferedWriter(detailedPath)) {
            writer.write(
                    "run_id,subvariant,train,route_id,vehicle_type,departure_id,stop_sequence,stop_id,planned_arrival,actual_arrival,arrival_delay,planned_departure,actual_departure,departure_delay\n");
            for (RailsimSimulationResult result : results) {
                if (result.getStatus() == RailsimSimulationResult.Status.SUCCESS) {
                    result.getAnalysisResult(TrainDelayAnalysis.RESULT_KEY, TrainDelayAnalysis.DelayReport.class)
                            .ifPresent(report -> report.getDetailedData().forEach(info -> {
                                try {
                                    writer.write(
                                            String.format("%s,%s,%s,%s,%s,%s,%d,%s,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f\n",
                                                    result.getRunId(), info.subVariant(), info.train(), info.routeId(),
                                                    info.vehicleType(), info.departureId(), info.stopSequence(),
                                                    info.stopId(), info.plannedArrival(), info.actualArrival(),
                                                    info.arrivalDelay(), info.plannedDeparture(),
                                                    info.actualDeparture(), info.departureDelay()));
                                } catch (IOException e) {
                                    throw new RuntimeException(e);
                                }
                            }));
                }
            }
        }
    }
}