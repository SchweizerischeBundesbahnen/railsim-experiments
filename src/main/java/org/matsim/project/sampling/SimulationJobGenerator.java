package org.matsim.project.sampling;

import lombok.extern.log4j.Log4j2;
import org.matsim.api.core.v01.Scenario;
import org.matsim.project.ProjectConfig;
import org.matsim.project.scenario.BuildingBlock;
import org.matsim.project.scenario.plan.OperatingMode;
import org.matsim.project.scenario.plan.OperationalPlan;
import org.matsim.project.scenario.plan.TrainVolumes;
import org.matsim.project.simulation.RailsimSimulationJob;

import java.nio.file.Path;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * A lazy generator for railsim simulation job definitions.
 */
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

    public int countExpectedJobs() {
        TrainVolumes v = operationalPlan.getTrainVolumes();
        int volumeSteps = 0;
        for (int i = v.getMin(); i <= v.getMax(); i += v.getStep()) {
            volumeSteps++;
        }
        return operationalPlan.getOperatingModes().size() * volumeSteps * projectConfig.getSamplesPerSubvariant();
    }

    /**
     * Produces a lazy stream of jobs.
     */
    public Stream<RailsimSimulationJob> stream() {
        final TrainVolumes volumesConfig = operationalPlan.getTrainVolumes();
        final int sampleSize = projectConfig.getSamplesPerSubvariant();

        return operationalPlan.getOperatingModes()
                .stream()
                .flatMap(mode -> IntStream.iterate(volumesConfig.getMin(), v -> v <= volumesConfig.getMax(),
                                v -> v + volumesConfig.getStep())
                        .boxed()
                        .flatMap(volume -> IntStream.rangeClosed(1, sampleSize)
                                .mapToObj(sampleIdx -> createJobMetadata(mode, volume, sampleIdx,
                                        volumesConfig.isBidirectional()))));
    }

    private RailsimSimulationJob createJobMetadata(OperatingMode mode, int volume, int sampleIdx,
                                                   boolean bidirectional) {
        // deterministic seed ensures reproducibility even when executed in parallel
        long taskSeed = projectConfig.getSeed() + mode.getId().hashCode() + volume + sampleIdx;

        return new RailsimSimulationJob(projectConfig, templateScenario, templateConfigFileInputPath, buildingBlock,
                mode, volume, sampleIdx, taskSeed, trainVolumePeriod, bidirectional, scheduleSamplingOutputFolderPath,
                jobConfigOutputFolderPath, simulationRunOutputFolderPath, volumeFormat, sampleFormat);
    }
}