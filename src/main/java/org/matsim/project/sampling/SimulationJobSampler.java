package org.matsim.project.sampling;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.matsim.api.core.v01.Scenario;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.project.sampling.strategy.DepartureSamplingStrategy;
import org.matsim.project.scenario.BuildingBlock;
import org.matsim.project.scenario.plan.OperationMode;
import org.matsim.project.scenario.plan.OperationalPlan;
import org.matsim.project.scenario.plan.SubVariant;
import org.matsim.project.scenario.plan.Variant;
import org.matsim.project.simulation.RailsimSimulationJob;
import org.matsim.project.utils.ResourceLoader;
import org.matsim.pt.transitSchedule.api.TransitScheduleWriter;
import org.matsim.vehicles.MatsimVehicleWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@RequiredArgsConstructor
@Log4j2
public class SimulationJobSampler {

    private final long seed;
    private final Path templateConfigFileInputPath;
    private final Scenario templateScenario;
    private final BuildingBlock buildingBlock;
    private final OperationalPlan operationalPlan;

    public List<RailsimSimulationJob> sample(int nSamplesPerSubvariant, DepartureSamplingStrategy strategy,
                                             Path scheduleSamplingOutputFolderPath,
                                             Path simulationJobConfigOutputFolderPath,
                                             Path simulationRunOutputFolderPath) throws IOException {

        // create simulation jobs for all sub-variants of the operation modes of the operational plan
        List<RailsimSimulationJob> jobs = new ArrayList<>();
        for (OperationMode opMode : operationalPlan.getOperationModes()) {
            for (Variant variant : opMode.getVariants()) {
                for (SubVariant subVariant : variant.getSubVariants()) {
                    log.info("Sampling {} simulation jobs for sub-variant {}", nSamplesPerSubvariant,
                            subVariant.getId());

                    // use one stateful sampler to ensure a random, but repeatable sequence
                    StatefulScheduleSampler sampler = new StatefulScheduleSampler(seed, templateScenario, subVariant);

                    // sample schedules n times
                    for (int i = 1; i <= nSamplesPerSubvariant; i++) {
                        final String runId = String.format("%s_%s_sample_%d", buildingBlock.name().toLowerCase(),
                                subVariant.getId().toLowerCase(), i);

                        // sample departures for new schedule
                        StatefulScheduleSampler.Sample sample = sampler.sample(strategy);

                        // create directory for new sample and write sampled schedule
                        Path sampleFilesPath = scheduleSamplingOutputFolderPath.resolve(
                                subVariant.getId().toLowerCase()).resolve("sample_" + i);
                        Files.createDirectories(sampleFilesPath);
                        Path schedulePath = sampleFilesPath.resolve("schedule.xml.gz");
                        new TransitScheduleWriter(sample.schedule()).writeFile(schedulePath.toString());
                        Path vehiclePath = sampleFilesPath.resolve("vehicles.xml.gz");
                        new MatsimVehicleWriter(sample.vehicles()).writeFile(vehiclePath.toString());

                        // create the simulation configuration for this specific run
                        Path runOutputPath = simulationRunOutputFolderPath.resolve(runId);
                        Config config = ConfigUtils.loadConfig(templateConfigFileInputPath.toString());
                        config.controller().setRunId(runId);
                        config.controller().setOutputDirectory(runOutputPath.toString());
                        config.controller()
                                .setOverwriteFileSetting(
                                        OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);
                        config.controller().setLastIteration(0);
                        config.network()
                                .setInputFile(ResourceLoader.getPath(buildingBlock.getNetworkFilePath()).toString());
                        config.transit()
                                .setTransitScheduleFile(
                                        simulationJobConfigOutputFolderPath.relativize(schedulePath).toString());
                        config.transit()
                                .setVehiclesFile(
                                        simulationJobConfigOutputFolderPath.relativize(vehiclePath).toString());

                        // write the config file and create the job object with necessary metadata
                        Path configFilePath = simulationJobConfigOutputFolderPath.resolve(runId + ".config.xml");
                        ConfigUtils.writeConfig(config, configFilePath.toString());

                        jobs.add(new RailsimSimulationJob(configFilePath, variant, subVariant, i));
                    }
                }
            }
        }

        return jobs;
    }
}
