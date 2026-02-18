package org.matsim.project.simulation;

import lombok.extern.log4j.Log4j2;
import org.matsim.api.core.v01.Scenario;
import org.matsim.project.ProjectConfig;
import org.matsim.project.scenario.BuildingBlock;
import org.matsim.project.scenario.plan.OperatingMode;
import org.matsim.project.scenario.plan.OperationalPlan;
import org.matsim.project.scenario.plan.TrainVolumes;

import java.nio.file.Path;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * A lazy generator for railsim simulation job definitions.
 */
@Log4j2
public class RailsimSimulationJobGenerator {

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

    public RailsimSimulationJobGenerator(ProjectConfig projectConfig, Path templateConfigFileInputPath,
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

    public long count() {
        return stream().count();
    }

    /**
     * The core generator pipeline.
     */
    public Stream<RailsimSimulationJob> stream() {
        TrainVolumes trainVolumes = operationalPlan.getTrainVolumes();
        int sampleSize = projectConfig.getSamplesPerSubvariant();

        return operationalPlan.getOperatingModes()
                .stream()
                .flatMap(mode -> streamVolumeRange(trainVolumes).flatMap(
                        volume -> createJobsForModeAndVolume(mode, volume, sampleSize,
                                trainVolumes.isBidirectional())));
    }

    private Stream<Integer> streamVolumeRange(TrainVolumes config) {
        return IntStream.iterate(config.getMin(), v -> v <= config.getMax(), v -> v + config.getStep()).boxed();
    }

    private Stream<RailsimSimulationJob> createJobsForModeAndVolume(OperatingMode mode, int volume, int sampleSize,
                                                                    boolean bidirectional) {
        String paddedVolume = String.format(volumeFormat, volume);
        String scenarioId = formatScenarioId(mode.getId(), paddedVolume);

        return IntStream.rangeClosed(1, sampleSize)
                .filter(sampleIdx -> isRunRequired(scenarioId, sampleIdx))
                .mapToObj(sampleIdx -> buildJob(mode, volume, paddedVolume, scenarioId, sampleIdx, bidirectional));
    }

    private boolean isRunRequired(String scenarioId, int sampleIdx) {
        if (!projectConfig.isReconstructionMode()) {
            return true;
        }

        String runId = formatRunId(scenarioId, String.format(sampleFormat, sampleIdx));
        return projectConfig.getReconstructRuns().contains(runId);
    }

    private RailsimSimulationJob buildJob(OperatingMode mode, int volume, String paddedVolume, String scenarioId,
                                          int sampleIdx, boolean bidirectional) {

        String paddedSample = String.format(sampleFormat, sampleIdx);
        String runId = formatRunId(scenarioId, paddedSample);
        long taskSeed = projectConfig.getSeed() + mode.getId().hashCode() + volume + sampleIdx;

        Path outputDir =
                simulationRunOutputFolderPath.resolve(mode.getId()).resolve("volume_" + paddedVolume).resolve(runId);

        return new RailsimSimulationJob(projectConfig, templateScenario, templateConfigFileInputPath, buildingBlock,
                mode, volume, sampleIdx, taskSeed, trainVolumePeriod, bidirectional, scheduleSamplingOutputFolderPath,
                jobConfigOutputFolderPath, simulationRunOutputFolderPath, runId, scenarioId, paddedVolume, paddedSample,
                outputDir);
    }

    private String formatScenarioId(String modeId, String paddedVolume) {
        return String.format("%s_%s_volume_%s", buildingBlock.name().toLowerCase(), modeId, paddedVolume);
    }

    private String formatRunId(String scenarioId, String paddedSample) {
        return String.format("%s_sample_%s", scenarioId, paddedSample);
    }
}