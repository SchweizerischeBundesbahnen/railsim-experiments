package org.matsim.project.simulation;

import lombok.extern.log4j.Log4j2;
import org.matsim.api.core.v01.Scenario;
import org.matsim.project.ProjectConfig;
import org.matsim.project.ProjectPaths;
import org.matsim.project.scenario.BuildingBlock;
import org.matsim.project.scenario.plan.OperatingMode;
import org.matsim.project.scenario.plan.OperationalPlan;
import org.matsim.project.scenario.plan.TrainVolumes;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * A lazy generator for railsim simulation job definitions.
 */
@Log4j2
public class RailsimSimulationJobGenerator {

    private final ProjectConfig projectConfig;
    private final ProjectPaths projectPaths;
    private final OperationalPlan operationalPlan;
    private final BuildingBlock buildingBlock;
    private final Scenario templateScenario;

    private final String volumeFormat;
    private final String sampleFormat;

    public RailsimSimulationJobGenerator(ProjectConfig projectConfig, ProjectPaths projectPaths,
                                         OperationalPlan operationalPlan, BuildingBlock buildingBlock,
                                         Scenario templateScenario) {
        this.projectConfig = projectConfig;
        this.projectPaths = projectPaths;
        this.operationalPlan = operationalPlan;
        this.buildingBlock = buildingBlock;
        this.templateScenario = templateScenario;

        this.volumeFormat = getZeroPaddedFormat(operationalPlan.getTrainVolumes().getMax());
        this.sampleFormat = getZeroPaddedFormat(projectConfig.getSamplesPerSubvariant());
    }

    private String getZeroPaddedFormat(int maxValue) {
        int digits = Integer.toString(Math.max(1, maxValue)).length();
        return "%0" + digits + "d";
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
                        volume -> createJobsForModeAndVolume(mode, volume, sampleSize)));
    }

    private Stream<Integer> streamVolumeRange(TrainVolumes config) {
        return IntStream.iterate(config.getMin(), v -> v <= config.getMax(), v -> v + config.getStep()).boxed();
    }

    private Stream<RailsimSimulationJob> createJobsForModeAndVolume(OperatingMode mode, int volume, int sampleSize) {
        String paddedVolume = String.format(volumeFormat, volume);
        String scenarioId = formatScenarioId(mode.getId(), paddedVolume);

        return IntStream.rangeClosed(1, sampleSize)
                .filter(sampleIdx -> isRunRequired(scenarioId, sampleIdx))
                .mapToObj(sampleIdx -> {
                    try {
                        return buildJob(mode, volume, paddedVolume, scenarioId, sampleIdx);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
    }

    private boolean isRunRequired(String scenarioId, int sampleIdx) {
        if (!projectConfig.isReconstructionMode()) {
            return true;
        }

        String runId = formatRunId(scenarioId, String.format(sampleFormat, sampleIdx));
        return projectConfig.getReconstructRuns().contains(runId);
    }

    private RailsimSimulationJob buildJob(OperatingMode mode, int volume, String paddedVolume, String scenarioId,
                                          int sampleIdx) throws IOException {

        String paddedSampleIdx = String.format(sampleFormat, sampleIdx);
        String runId = formatRunId(scenarioId, paddedSampleIdx);

        return new RailsimSimulationJob(projectConfig, projectPaths, operationalPlan, buildingBlock, templateScenario,
                mode, volume, paddedVolume, sampleIdx, paddedSampleIdx, scenarioId, runId);
    }

    private String formatScenarioId(String modeId, String paddedVolume) {
        return String.format("%s_%s_volume_%s", buildingBlock.name().toLowerCase(), modeId, paddedVolume);
    }

    private String formatRunId(String scenarioId, String paddedSample) {
        return String.format("%s_sample_%s", scenarioId, paddedSample);
    }
}