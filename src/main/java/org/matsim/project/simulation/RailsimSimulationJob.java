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
import org.matsim.project.scenario.plan.SubVariant;
import org.matsim.project.scenario.plan.Variant;

import java.nio.file.Path;

/**
 * A single, runnable MATSim simulation with railsim enabled.
 */
@Getter
public class RailsimSimulationJob implements Runnable {

    private final Path configFilePath;
    private final Variant variant;
    private final BuildingBlock buildingBlock;
    private final SubVariant subVariant;
    private final int sample;

    private final String runId;
    private final Path outputDirectory;
    private final Config config;

    public RailsimSimulationJob(Path configFilePath, BuildingBlock buildingBlock, Variant variant,
                                SubVariant subVariant, int sample) {
        this.configFilePath = configFilePath;
        this.buildingBlock = buildingBlock;
        this.variant = variant;
        this.subVariant = subVariant;
        this.sample = sample;

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
    }
}