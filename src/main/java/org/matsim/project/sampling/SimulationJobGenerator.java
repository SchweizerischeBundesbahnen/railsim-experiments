package org.matsim.project.sampling;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.matsim.api.core.v01.Scenario;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.project.ProjectConfig;
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
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * Samples simulation jobs lazily by iterating over operating modes, scaling through configured train volumes,
 * and creating multiple random samples per volume level.
 */
@RequiredArgsConstructor
@Log4j2
public class SimulationJobGenerator {

    private final ProjectConfig projectConfig;
    private final Path templateConfigFileInputPath;
    private final Path scheduleSamplingOutputFolderPath;
    private final Path jobConfigOutputFolderPath;
    private final Path simulationRunOutputFolderPath;
    private final Scenario templateScenario;
    private final BuildingBlock buildingBlock;
    private final OperationalPlan operationalPlan;

    private final String volumeFormat;
    private final String sampleFormat;
    private final int trainVolumePeriod;

    public SimulationJobGenerator(ProjectConfig projectConfig, Path templateConfigFileInputPath,
                                  Path scheduleSamplingOutputFolderPath, Path jobConfigOutputFolderPath,
                                  Path simulationRunOutputFolderPath, Scenario templateScenario,
                                  BuildingBlock buildingBlock, OperationalPlan operationalPlan) {
        this.projectConfig = projectConfig;
        this.templateConfigFileInputPath = templateConfigFileInputPath;
        this.scheduleSamplingOutputFolderPath = scheduleSamplingOutputFolderPath;
        this.jobConfigOutputFolderPath = jobConfigOutputFolderPath;
        this.simulationRunOutputFolderPath = simulationRunOutputFolderPath;
        this.templateScenario = templateScenario;
        this.buildingBlock = buildingBlock;
        this.operationalPlan = operationalPlan;

        TrainVolumes volumesConfig = operationalPlan.getTrainVolumes();
        this.trainVolumePeriod = volumesConfig.getPeriod();
        this.volumeFormat = "%0" + Integer.toString(Math.max(1, volumesConfig.getMax())).length() + "d";
        this.sampleFormat =
                "%0" + Integer.toString(Math.max(1, projectConfig.getSamplesPerSubvariant())).length() + "d";
    }

    /**
     * Returns the total number of jobs that will be generated.
     * Useful for progress tracking in the executor.
     */
    public int countExpectedJobs() {
        TrainVolumes v = operationalPlan.getTrainVolumes();
        int volumeSteps = 0;
        for (int i = v.getMin(); i <= v.getMax(); i += v.getStep()) {
            volumeSteps++;
        }
        return operationalPlan.getOperatingModes().size() * volumeSteps * projectConfig.getSamplesPerSubvariant();
    }

    /**
     * Generator method; disk operations only happen when the stream is consumed.
     */
    public Stream<RailsimSimulationJob> stream() {
        final TrainVolumes volumesConfig = operationalPlan.getTrainVolumes();
        final int sampleSize = projectConfig.getSamplesPerSubvariant();
        final DepartureSamplingStrategy strategy = projectConfig.getDepartureSamplingStrategy();
        final int simulationTime = projectConfig.getSimulationTime();

        return operationalPlan.getOperatingModes()
                .stream()
                .flatMap(mode -> IntStream.iterate(volumesConfig.getMin(), v -> v <= volumesConfig.getMax(),
                                v -> v + volumesConfig.getStep())
                        .boxed()
                        .flatMap(volume -> IntStream.rangeClosed(1, sampleSize)
                                .mapToObj(
                                        sampleIdx -> createJobLazily(mode, volume, sampleIdx, strategy, simulationTime,
                                                volumesConfig.isBidirectional()))));
    }

    private RailsimSimulationJob createJobLazily(OperatingMode mode, int volume, int sampleIdx,
                                                 DepartureSamplingStrategy strategy, int simTime,
                                                 boolean bidirectional) {
        try {
            long taskSeed = projectConfig.getSeed() + mode.getId().hashCode() + volume + sampleIdx;
            StatefulScheduleSampler sampler =
                    new StatefulScheduleSampler(taskSeed, templateScenario, mode, trainVolumePeriod, volume, simTime,
                            bidirectional);

            return createJobFilesAndConfig(sampleIdx, volume, mode, sampler, strategy);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private RailsimSimulationJob createJobFilesAndConfig(int sampleIndex, int trainsPerPeriod, OperatingMode mode,
                                                         StatefulScheduleSampler sampler,
                                                         DepartureSamplingStrategy strategy) throws IOException {

        // build base scenario id (without padded indices)
        final String paddedSample = String.format(sampleFormat, sampleIndex);
        final String paddedVolume = String.format(volumeFormat, trainsPerPeriod);
        final String scenarioId =
                String.format("%s_%s_volume_%s", buildingBlock.name().toLowerCase(), mode.getId(), paddedVolume);
        final String runId = String.format("%s_sample_%s", scenarioId, paddedSample);

        log.debug("Sampling and writing files for job: {}", runId);
        StatefulScheduleSampler.Sample sample = sampler.sample(strategy);

        // write sampled schedule and vehicles
        Path sampleFilesPath = scheduleSamplingOutputFolderPath.resolve(mode.getId())
                .resolve("volume_" + paddedVolume)
                .resolve("sample_" + paddedSample);
        Files.createDirectories(sampleFilesPath);
        new TransitScheduleWriter(sample.schedule()).writeFile(sampleFilesPath.resolve("schedule.xml.gz").toString());
        new MatsimVehicleWriter(sample.vehicles()).writeFile(sampleFilesPath.resolve("vehicles.xml.gz").toString());

        Path configFilePath = jobConfigOutputFolderPath.resolve(scenarioId).resolve(runId + ".config.xml");
        Files.createDirectories(configFilePath.getParent());

        // prepare MATSim config for run
        Config config = ConfigUtils.loadConfig(templateConfigFileInputPath.toString());
        config.controller().setRunId(runId);
        config.controller()
                .setOutputDirectory(simulationRunOutputFolderPath.resolve(mode.getId())
                        .resolve("volume_" + paddedVolume)
                        .resolve(runId)
                        .toString());
        config.network().setInputFile(ResourceLoader.getPath(buildingBlock.getNetworkFilePath()).toString());
        config.transit()
                .setTransitScheduleFile(
                        configFilePath.getParent().relativize(sampleFilesPath.resolve("schedule.xml.gz")).toString());
        config.transit()
                .setVehiclesFile(
                        configFilePath.getParent().relativize(sampleFilesPath.resolve("vehicles.xml.gz")).toString());

        // set railsim specific config options: one iteration, disable unnecessary outputs
        RailsimConfigHelper.configure(config);
        ConfigUtils.writeConfig(config, configFilePath.toString());

        return new RailsimSimulationJob(configFilePath, buildingBlock, mode, trainsPerPeriod, sampleIndex);
    }
}