package org.matsim.project.simulation;

import ch.sbb.matsim.contrib.railsim.RailsimModule;
import ch.sbb.matsim.contrib.railsim.qsimengine.RailsimQSimModule;
import lombok.Getter;
import org.matsim.api.core.v01.Scenario;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.Controller;
import org.matsim.core.controler.ControllerUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.project.scenario.BuildingBlock;
import org.matsim.project.scenario.plan.OperatingMode;
import org.matsim.project.utils.RailsimConfigHelper;

import java.nio.file.Path;

/**
 * A single, runnable MATSim simulation with railsim enabled.
 */
@Getter
public class RailsimSimulationJob implements Runnable {

    private final Path configFilePath;
    private final OperatingMode operatingMode;
    private final BuildingBlock buildingBlock;
    private final int trainVolume;
    private final int sampleIndex;

    private final String runId;
    private final Path outputDirectory;
    private final Config config;

    public RailsimSimulationJob(Path configFilePath, BuildingBlock buildingBlock, OperatingMode operatingMode,
                                int trainVolume, int sampleIndex) {
        this.configFilePath = configFilePath;
        this.buildingBlock = buildingBlock;
        this.operatingMode = operatingMode;
        this.trainVolume = trainVolume;
        this.sampleIndex = sampleIndex;

        this.config = ConfigUtils.loadConfig(configFilePath.toString());
        this.runId = config.controller().getRunId();
        this.outputDirectory = Path.of(config.controller().getOutputDirectory());
    }

    @Override
    public void run() {
        Scenario scenario = ScenarioUtils.loadScenario(config);
        Controller controller = ControllerUtils.createController(scenario);
        controller.addOverridingModule(new RailsimModule());
        controller.configureQSimComponents(c -> new RailsimQSimModule().configure(c));
        controller.run();
        RailsimConfigHelper.writeStaticOutputFiles(controller);
    }

    public Path getOutputMirrorPath(Path path) {
        Path suffix = getOutputSuffixRelativeTo(path);
        return path.toAbsolutePath().normalize().resolve(suffix).normalize();
    }

    /**
     * Returns the suffix of this job's output directory after the last common ancestor with the given base.
     * The returned Path is relative (no root) and can be resolved against any target directory.
     */
    private Path getOutputSuffixRelativeTo(Path base) {
        Path baseAbs = base.toAbsolutePath().normalize();
        Path outAbs = this.outputDirectory.toAbsolutePath().normalize();

        int baseCount = baseAbs.getNameCount();
        int outCount = outAbs.getNameCount();

        // find common prefix length
        int common = 0;
        int max = Math.min(baseCount, outCount);
        while (common < max && baseAbs.getName(common).equals(outAbs.getName(common))) {
            common++;
        }

        return outAbs.subpath(common + 1, outCount);
    }
}