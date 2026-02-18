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
import org.matsim.project.sampling.StatefulScheduleSampler;
import org.matsim.project.scenario.BuildingBlock;
import org.matsim.project.scenario.plan.OperatingMode;
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
    private final Scenario templateScenario;
    private final Path templateConfigPath;
    private final BuildingBlock buildingBlock;
    private final OperatingMode operatingMode;
    private final int trainVolume;
    private final int sampleIndex;
    private final long seed;
    private final int trainVolumePeriod;
    private final boolean bidirectional;

    private final Path scheduleSamplingRoot;
    private final Path jobConfigRoot;
    private final Path simulationRunRoot;

    private final String runId;
    private final String scenarioId;
    private final String paddedVolume;
    private final String paddedSample;
    private final Path outputDirectory;

    private Path configFilePath;
    private Config config;

    @Override
    public void run() {
        try {
            this.configFilePath = prepare();
            this.config = ConfigUtils.loadConfig(configFilePath.toString());

            Scenario scenario = ScenarioUtils.loadScenario(config);
            Controller controller = ControllerUtils.createController(scenario);
            controller.addOverridingModule(new RailsimModule());
            controller.configureQSimComponents(c -> new RailsimQSimModule().configure(c));
            controller.run();
            RailsimConfigHelper.writeStaticOutputFiles(controller);

        } catch (IOException e) {
            throw new RuntimeException("Failed to prepare or run job: " + runId, e);
        }
    }

    private Path prepare() throws IOException {
        // sample schedule
        StatefulScheduleSampler.Sample sample =
                new StatefulScheduleSampler(seed, templateScenario, operatingMode, trainVolumePeriod, trainVolume,
                        projectConfig.getSimulationTime(), bidirectional).sample(
                        projectConfig.getDepartureSamplingStrategy());

        // write sampled schedule
        Path samplePath = scheduleSamplingRoot.resolve(operatingMode.getId())
                .resolve("volume_" + paddedVolume)
                .resolve("sample_" + paddedSample);
        Files.createDirectories(samplePath);
        Path scheduleFile = samplePath.resolve("schedule.xml.gz");
        Path vehicleFile = samplePath.resolve("vehicles.xml.gz");
        new TransitScheduleWriter(sample.schedule()).writeFile(scheduleFile.toString());
        new MatsimVehicleWriter(sample.vehicles()).writeFile(vehicleFile.toString());

        // create config
        Path generatedConfigPath = jobConfigRoot.resolve(scenarioId).resolve(runId + ".config.xml");
        Files.createDirectories(generatedConfigPath.getParent());
        Config jobConfig = ConfigUtils.loadConfig(templateConfigPath.toString());
        jobConfig.controller().setRunId(runId);
        jobConfig.controller().setOutputDirectory(outputDirectory.toString());
        jobConfig.network().setInputFile(ResourceLoader.getPath(buildingBlock.getNetworkFilePath()).toString());

        Path configParent = generatedConfigPath.getParent();
        jobConfig.transit().setTransitScheduleFile(configParent.relativize(scheduleFile).toString());
        jobConfig.transit().setVehiclesFile(configParent.relativize(vehicleFile).toString());

        RailsimConfigHelper.configure(jobConfig);
        ConfigUtils.writeConfig(jobConfig, generatedConfigPath.toString());

        return generatedConfigPath;
    }

    public Path getOutputMirrorPath(Path targetRoot) {
        Path suffix = simulationRunRoot.relativize(this.outputDirectory);
        return targetRoot.toAbsolutePath().normalize().resolve(suffix).normalize();
    }
}