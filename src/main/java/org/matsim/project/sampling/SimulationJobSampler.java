package org.matsim.project.sampling;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.matsim.api.core.v01.Scenario;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.project.sampling.strategy.DepartureSamplingStrategy;
import org.matsim.project.scenario.BuildingBlock;
import org.matsim.project.scenario.plan.OperatingMode;
import org.matsim.project.scenario.plan.OperationalPlan;
import org.matsim.project.scenario.plan.TrainVolumes;
import org.matsim.project.simulation.RailsimSimulationJob;
import org.matsim.project.utils.RailsimConfigHelper;
import org.matsim.project.utils.ResourceLoader;
import org.matsim.pt.transitSchedule.api.TransitScheduleWriter;
import org.matsim.vehicles.MatsimVehicleWriter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;

@RequiredArgsConstructor
@Log4j2
public class SimulationJobSampler {

    private final long seed;
    private final Path templateConfigFileInputPath;
    private final Scenario templateScenario;
    private final BuildingBlock buildingBlock;
    private final OperationalPlan operationalPlan;

    /**
     * Samples simulation jobs by iterating over all Operating Modes, scaling through the defined
     * train volume range, and creating multiple random samples per configuration.
     */
    public List<RailsimSimulationJob> sample(int samplesPerVolumeLevel, int simulationTime,
                                             DepartureSamplingStrategy strategy, Path scheduleSamplingOutputFolderPath,
                                             Path simulationJobConfigOutputFolderPath,
                                             Path simulationRunOutputFolderPath) {

        final TrainVolumes volumesConfig = operationalPlan.getTrainVolumes();
        final int trainVolumePeriod = volumesConfig.getPeriod();

        // check for even multiple of the sampling period
        if (simulationTime % trainVolumePeriod != 0) {
            log.warn("For building block '{}': Simulation time ({}s) is not a multiple of the period ({}s).",
                    buildingBlock.name(), simulationTime, trainVolumePeriod);
        }

        log.info("Starting parallel sampling for building block: {}", buildingBlock.name());
        return operationalPlan.getOperatingModes().parallelStream().flatMap(operatingMode -> {

            // scale volume (trains per period) of configured range
            return IntStream.iterate(volumesConfig.getMin(), v -> v <= volumesConfig.getMax(),
                    v -> v + volumesConfig.getStep()).boxed().flatMap(trainsPerPeriod -> {

                // draw random samples for this specific operating mode and trains per period
                return IntStream.rangeClosed(1, samplesPerVolumeLevel).mapToObj(sampleIndex -> {
                    try {
                        // unique seed for this specific combination
                        long taskSeed = seed + operatingMode.getId().hashCode() + trainsPerPeriod + sampleIndex;

                        // initialize the sampler for this specific volume level
                        StatefulScheduleSampler sampler =
                                new StatefulScheduleSampler(taskSeed, templateScenario, operatingMode,
                                        trainVolumePeriod, trainsPerPeriod, simulationTime,
                                        volumesConfig.isBidirectional());

                        return createJobForSample(sampleIndex, trainsPerPeriod, operatingMode, sampler, strategy,
                                scheduleSamplingOutputFolderPath, simulationJobConfigOutputFolderPath,
                                simulationRunOutputFolderPath);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
            });
        }).toList();
    }

    private RailsimSimulationJob createJobForSample(int sampleIndex, int trainsPerPeriod, OperatingMode mode,
                                                    StatefulScheduleSampler sampler, DepartureSamplingStrategy strategy,
                                                    Path scheduleSamplingOutputFolderPath,
                                                    Path simulationJobConfigOutputFolderPath,
                                                    Path simulationRunOutputFolderPath) throws IOException {

        // unique identifier for this specific product mix + pattern + volume combination (= scenario)
        String scenarioId = String.format("%s.%d", mode.getId(), trainsPerPeriod);

        // unique identifier for the specific random sample
        final String runId =
                String.format("%s_%s_sample_%d", buildingBlock.name().toLowerCase(), scenarioId, sampleIndex);

        log.debug("Sampling job: {}", runId);
        StatefulScheduleSampler.Sample sample = sampler.sample(strategy);

        // write sampled schedule and vehicles
        Path sampleFilesPath = scheduleSamplingOutputFolderPath.resolve(scenarioId).resolve("sample_" + sampleIndex);
        Files.createDirectories(sampleFilesPath);
        Path schedulePath = sampleFilesPath.resolve("schedule.xml.gz");
        new TransitScheduleWriter(sample.schedule()).writeFile(schedulePath.toString());
        Path vehiclePath = sampleFilesPath.resolve("vehicles.xml.gz");
        new MatsimVehicleWriter(sample.vehicles()).writeFile(vehiclePath.toString());

        Path runOutputPath = simulationRunOutputFolderPath.resolve(scenarioId).resolve(runId);
        Path configFilePath = simulationJobConfigOutputFolderPath.resolve(scenarioId).resolve(runId + ".config.xml");
        Files.createDirectories(configFilePath.getParent());

        // prepare matsim config for run
        Config config = ConfigUtils.loadConfig(templateConfigFileInputPath.toString());
        config.controller().setRunId(runId);
        config.controller().setOutputDirectory(runOutputPath.toString());
        config.network().setInputFile(ResourceLoader.getPath(buildingBlock.getNetworkFilePath()).toString());
        config.transit().setTransitScheduleFile(configFilePath.getParent().relativize(schedulePath).toString());
        config.transit().setVehiclesFile(configFilePath.getParent().relativize(vehiclePath).toString());

        // set railsim specific config options: one iteration, disable unnecessary outputs
        RailsimConfigHelper.configure(config);

        ConfigUtils.writeConfig(config, configFilePath.toString());

        return new RailsimSimulationJob(configFilePath, buildingBlock, mode.getProductMix(), mode.getFlowPattern(),
                sampleIndex);
    }
}