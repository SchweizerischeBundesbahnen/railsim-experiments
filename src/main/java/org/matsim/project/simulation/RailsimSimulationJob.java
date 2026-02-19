package org.matsim.project.simulation;

import ch.sbb.matsim.contrib.railsim.RailsimModule;
import ch.sbb.matsim.contrib.railsim.qsimengine.RailsimQSimModule;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.matsim.api.core.v01.Scenario;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.Controller;
import org.matsim.core.controler.ControllerUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.project.ProjectConfig;
import org.matsim.project.ProjectPaths;
import org.matsim.project.sampling.StatefulScheduleSampler;
import org.matsim.project.scenario.BuildingBlock;
import org.matsim.project.scenario.plan.OperatingMode;
import org.matsim.project.scenario.plan.OperationalPlan;
import org.matsim.project.utils.RailsimConfigHelper;
import org.matsim.project.utils.ResourceLoader;
import org.matsim.pt.transitSchedule.api.TransitScheduleWriter;
import org.matsim.vehicles.MatsimVehicleWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Log4j2
@Getter
@RequiredArgsConstructor
public class RailsimSimulationJob implements Runnable {

    private final ProjectConfig projectConfig;
    private final ProjectPaths projectPaths;
    private final OperationalPlan operationalPlan;
    private final BuildingBlock buildingBlock;
    private final Scenario templateScenario;
    private final OperatingMode operatingMode;
    private final int trainVolume;
    private final int sampleIndex;
    private final String scenarioId;
    private final String runId;

    private final long seed;
    private Config config;
    private Path configFilePath;
    private Path sampleSchedulePath;
    private Path runOutputFolderPath;
    private Path analysisOutputFolderPath;

    public RailsimSimulationJob(ProjectConfig projectConfig, ProjectPaths projectPaths, OperationalPlan operationalPlan,
                                BuildingBlock buildingBlock, Scenario templateScenario, OperatingMode operatingMode,
                                int trainVolume, String paddedVolume, int sampleIndex, String paddedSampleIndex,
                                String scenarioId, String runId) throws IOException {
        this.projectConfig = projectConfig;
        this.projectPaths = projectPaths;
        this.operationalPlan = operationalPlan;
        this.buildingBlock = buildingBlock;
        this.templateScenario = templateScenario;
        this.operatingMode = operatingMode;
        this.trainVolume = trainVolume;
        this.sampleIndex = sampleIndex;
        this.scenarioId = scenarioId;
        this.runId = runId;

        this.seed = getSeed(projectConfig, buildingBlock, operatingMode, trainVolume, sampleIndex);

        this.configFilePath = projectPaths.getAndEnsure(ProjectPaths.Folder.SIMULATION_JOB_CONFIG)
                .resolve(operatingMode.getId())
                .resolve("volume_" + paddedVolume)
                .resolve(runId + ".config.xml");
        this.sampleSchedulePath = projectPaths.getAndEnsure(ProjectPaths.Folder.SCHEDULE_SAMPLING)
                .resolve(operatingMode.getId())
                .resolve("volume_" + paddedVolume)
                .resolve("sample_" + paddedSampleIndex);
        this.runOutputFolderPath = projectPaths.getAndEnsure(ProjectPaths.Folder.SIMULATION_RUN_OUTPUT)
                .resolve(operatingMode.getId())
                .resolve("volume_" + paddedVolume)
                .resolve(runId);
        this.analysisOutputFolderPath = projectPaths.getAndEnsure(ProjectPaths.Folder.ANALYSIS)
                .resolve(operatingMode.getId())
                .resolve("volume_" + paddedVolume)
                .resolve(runId);
    }

    // prime multiplier chain to avoid addition collisions (e.g. vol 10 + sample 1 != vol 1 + sample 10)
    private long getSeed(ProjectConfig projectConfig, BuildingBlock buildingBlock, OperatingMode operatingMode,
                         int trainVolume, int sampleIndex) {
        long tempSeed = projectConfig.getSeed();
        tempSeed = 31 * tempSeed + buildingBlock.name().hashCode();
        tempSeed = 31 * tempSeed + operatingMode.getId().hashCode();
        tempSeed = 31 * tempSeed + trainVolume;
        tempSeed = 31 * tempSeed + sampleIndex;

        return tempSeed;
    }

    @Override
    public void run() {
        try {
            prepare();
            this.config = ConfigUtils.loadConfig(configFilePath.toString());

            Scenario scenario = ScenarioUtils.loadScenario(config);
            Controller controller = ControllerUtils.createController(scenario);
            controller.addOverridingModule(new RailsimModule());
            controller.configureQSimComponents(c -> new RailsimQSimModule().configure(c));
            controller.run();

            if (!projectConfig.isCleanupRuns()) {
                RailsimConfigHelper.writeStaticOutputFiles(controller);
            }

        } catch (IOException e) {
            throw new RuntimeException("Failed to prepare or run job: " + runId, e);
        }
    }

    private void prepare() throws IOException {
        // ensure directories exist
        Path configParent = configFilePath.getParent();
        Files.createDirectories(sampleSchedulePath);
        Files.createDirectories(configParent);
        Files.createDirectories(runOutputFolderPath);
        Files.createDirectories(analysisOutputFolderPath);

        // sample schedule
        StatefulScheduleSampler.Sample sample = new StatefulScheduleSampler(seed, templateScenario, operatingMode,
                operationalPlan.getTrainVolumes().getPeriod(), trainVolume, projectConfig.getSimulationTime(),
                operationalPlan.getTrainVolumes().isBidirectional()).sample(
                projectConfig.getDepartureSamplingStrategy());

        // write sampled schedule
        Path scheduleFile = sampleSchedulePath.resolve("schedule.xml.gz");
        Path vehicleFile = sampleSchedulePath.resolve("vehicles.xml.gz");
        new TransitScheduleWriter(sample.schedule()).writeFile(scheduleFile.toString());
        new MatsimVehicleWriter(sample.vehicles()).writeFile(vehicleFile.toString());

        // create config
        Config jobConfig = ConfigUtils.loadConfig(ResourceLoader.getPath(buildingBlock.getConfigFilePath()).toString());
        jobConfig.controller().setRunId(runId);
        jobConfig.controller().setOutputDirectory(runOutputFolderPath.toString());
        jobConfig.network().setInputFile(ResourceLoader.getPath(buildingBlock.getNetworkFilePath()).toString());
        jobConfig.transit().setTransitScheduleFile(configParent.relativize(scheduleFile).toString());
        jobConfig.transit().setVehiclesFile(configParent.relativize(vehicleFile).toString());

        RailsimConfigHelper.configure(jobConfig);
        ConfigUtils.writeConfig(jobConfig, configFilePath.toString());
    }
}