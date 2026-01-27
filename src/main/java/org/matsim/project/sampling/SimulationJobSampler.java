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

/**
 * Samples simulation jobs by iterating over operating modes, scaling through configured train volumes,
 * and creating multiple random samples per volume level.
 * <p>
 * Improvements:
 * - zero-padding of sample and volume indices (configurable based on max values)
 * - correct creation of directories and correct relative path references in produced MATSim configs
 * - clearer structure and helper methods
 */
@RequiredArgsConstructor
@Log4j2
public class SimulationJobSampler {

    private final long seed;
    private final Path templateConfigFileInputPath;
    private final Scenario templateScenario;
    private final BuildingBlock buildingBlock;
    private final OperationalPlan operationalPlan;

    /**
     * Samples simulation jobs.
     *
     * @param sampleSize                          number of random samples to draw for each volume level (samples will be numbered 1..N)
     * @param simulationTime                      simulation time in seconds
     * @param strategy                            sampling strategy
     * @param scheduleSamplingOutputFolderPath    base folder to write sampled schedules and vehicles (per mode/volume/sample)
     * @param simulationJobConfigOutputFolderPath base folder to write produced job config files (per scenario)
     * @param simulationRunOutputFolderPath       base folder for run outputs (set into produced config controller.outputDirectory)
     * @return list of job descriptors
     */
    public List<RailsimSimulationJob> sample(int sampleSize, int simulationTime, DepartureSamplingStrategy strategy,
                                             Path scheduleSamplingOutputFolderPath,
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

        // prepare padding widths
        final int volumeMax = Math.max(1, volumesConfig.getMax());
        final int widthVolume = Integer.toString(volumeMax).length();
        final int sampleMax = Math.max(1, sampleSize);
        final int widthSample = Integer.toString(sampleMax).length();
        final String volumeFormat = "%0" + widthVolume + "d";
        final String sampleFormat = "%0" + widthSample + "d";

        return operationalPlan.getOperatingModes().parallelStream().flatMap(operatingMode -> {

            // scale volume (trains per period) of configured range
            return IntStream.iterate(volumesConfig.getMin(), v -> v <= volumesConfig.getMax(),
                    v -> v + volumesConfig.getStep()).boxed().flatMap(trainVolume -> {

                // draw random samples for this specific operating mode and trains per period
                return IntStream.rangeClosed(1, sampleSize).mapToObj(sampleIndex -> {
                    try {
                        // unique seed for this specific combination
                        long taskSeed = seed + operatingMode.getId().hashCode() + trainVolume + sampleIndex;

                        // initialize the sampler for this specific volume level
                        StatefulScheduleSampler sampler =
                                new StatefulScheduleSampler(taskSeed, templateScenario, operatingMode,
                                        trainVolumePeriod, trainVolume, simulationTime,
                                        volumesConfig.isBidirectional());

                        return createJobForSample(sampleIndex, trainVolume, operatingMode, sampler, strategy,
                                scheduleSamplingOutputFolderPath, simulationJobConfigOutputFolderPath,
                                simulationRunOutputFolderPath, volumeFormat, sampleFormat);
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
                                                    Path simulationRunOutputFolderPath, String volumeFormat,
                                                    String sampleFormat) throws IOException {

        // build base scenario id (without padded indices)
        final String paddedSample = String.format(sampleFormat, sampleIndex);
        final String paddedVolume = String.format(volumeFormat, trainsPerPeriod);
        final String scenarioId =
                String.format("%s_%s_volume_%s", buildingBlock.name().toLowerCase(), mode.getId(), paddedVolume);
        final String runId = String.format("%s_sample_%s", scenarioId, paddedSample);

        log.debug("Sampling job: {}", runId);
        StatefulScheduleSampler.Sample sample = sampler.sample(strategy);

        // write sampled schedule and vehicles
        Path sampleFilesPath = scheduleSamplingOutputFolderPath.resolve(mode.getId())
                .resolve("volume_" + paddedVolume)
                .resolve("sample_" + paddedSample);
        Files.createDirectories(sampleFilesPath);
        Path schedulePath = sampleFilesPath.resolve("schedule.xml.gz");
        new TransitScheduleWriter(sample.schedule()).writeFile(schedulePath.toString());
        Path vehiclePath = sampleFilesPath.resolve("vehicles.xml.gz");
        new MatsimVehicleWriter(sample.vehicles()).writeFile(vehiclePath.toString());

        Path runOutputPath =
                simulationRunOutputFolderPath.resolve(mode.getId()).resolve("volume_" + paddedVolume).resolve(runId);
        Path configFilePath = simulationJobConfigOutputFolderPath.resolve(scenarioId).resolve(runId + ".config.xml");
        Files.createDirectories(configFilePath.getParent());

        // prepare MATSim config for run
        Config config = ConfigUtils.loadConfig(templateConfigFileInputPath.toString());
        config.controller().setRunId(runId);
        config.controller().setOutputDirectory(runOutputPath.toString());
        config.network().setInputFile(ResourceLoader.getPath(buildingBlock.getNetworkFilePath()).toString());
        Path configParent = configFilePath.getParent();
        Path relativeSchedulePath = configParent.relativize(schedulePath);
        Path relativeVehiclePath = configParent.relativize(vehiclePath);
        config.transit().setTransitScheduleFile(relativeSchedulePath.toString());
        config.transit().setVehiclesFile(relativeVehiclePath.toString());

        // set railsim specific config options: one iteration, disable unnecessary outputs
        RailsimConfigHelper.configure(config);
        ConfigUtils.writeConfig(config, configFilePath.toString());

        return new RailsimSimulationJob(configFilePath, buildingBlock, mode, trainsPerPeriod, sampleIndex);
    }
}
