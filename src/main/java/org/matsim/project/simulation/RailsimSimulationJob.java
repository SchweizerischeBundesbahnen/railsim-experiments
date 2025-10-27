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

import java.nio.file.Path;

/**
 * Represents a single, runnable Railsim MATSim simulation defined by a config file.
 * This class is self-contained and holds all necessary information to execute a run.
 */
@Getter
public class RailsimSimulationJob implements Runnable {

    private final Path configFilePath;
    private final String runId;
    private final Path outputDirectory;
    private final Config config;
    private final String subVariantId;

    public RailsimSimulationJob(Path configFilePath, String subVariantId) {
        this.configFilePath = configFilePath;
        this.config = ConfigUtils.loadConfig(configFilePath.toString());
        this.runId = config.controller().getRunId();
        this.outputDirectory = Path.of(config.controller().getOutputDirectory());
        this.subVariantId = subVariantId;
    }

    @Override
    public void run() {
        Scenario scenario = ScenarioUtils.loadScenario(config);
        Controller controller = ControllerUtils.createController(scenario);
        controller.addOverridingModule(new RailsimModule());
        controller.configureQSimComponents(c -> new RailsimQSimModule().configure(c));
        controller.run();
    }
}