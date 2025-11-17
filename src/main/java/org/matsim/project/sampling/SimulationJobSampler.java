package org.matsim.project.sampling;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.matsim.api.core.v01.Scenario;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.project.sampling.strategy.DepartureSamplingStrategy;
import org.matsim.project.scenario.BuildingBlock;
import org.matsim.project.scenario.plan.OperationMode;
import org.matsim.project.scenario.plan.OperationalPlan;
import org.matsim.project.scenario.plan.SubVariant;
import org.matsim.project.scenario.plan.Variant;
import org.matsim.project.simulation.RailsimSimulationJob;
import org.matsim.project.utils.RailsimConfigHelper;
import org.matsim.project.utils.ResourceLoader;
import org.matsim.pt.transitSchedule.api.TransitScheduleWriter;
import org.matsim.vehicles.MatsimVehicleWriter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

@RequiredArgsConstructor
@Log4j2
public class SimulationJobSampler {

    private final long seed;
    private final Path templateConfigFileInputPath;
    private final Scenario templateScenario;
    private final BuildingBlock buildingBlock;
    private final OperationalPlan operationalPlan;

    public List<RailsimSimulationJob> sample(int nSamplesPerSubvariant, int simulationTime,
                                             DepartureSamplingStrategy strategy, Path scheduleSamplingOutputFolderPath,
                                             Path simulationJobConfigOutputFolderPath,
                                             Path simulationRunOutputFolderPath) {

        final int trainVolumePeriod = operationalPlan.getTrainVolumePeriod();

        // check for even multiple of the sampling period
        if (simulationTime % trainVolumePeriod != 0) {
            log.warn(
                    "For building block '{}': The simulation time ({}s) is not an even multiple of the sampling period ({}s). The last sampling window will be incomplete, which may result in fewer departures than planned for that interval.",
                    buildingBlock.name(), simulationTime, trainVolumePeriod);
        }

        // flatten the nested structure into a single list of tasks.
        List<SubVariantTask> tasks = new ArrayList<>();
        for (OperationMode opMode : operationalPlan.getOperationModes()) {
            for (Variant variant : opMode.getVariants()) {
                for (SubVariant subVariant : variant.getSubVariants()) {
                    tasks.add(new SubVariantTask(variant, subVariant));
                }
            }
        }

        log.info("Starting parallel sampling of {} jobs for {} sub-variants...", tasks.size() * nSamplesPerSubvariant,
                tasks.size());

        // process the list of tasks in parallel
        return tasks.parallelStream().flatMap(task -> {
            long taskSeed = seed + task.subVariant.getId().hashCode();

            StatefulScheduleSampler sampler = new StatefulScheduleSampler(taskSeed, templateScenario, task.subVariant,
                    trainVolumePeriod, simulationTime);

            return IntStream.rangeClosed(1, nSamplesPerSubvariant).mapToObj(sampleIndex -> {
                try {
                    return createJobForSample(sampleIndex, task.variant, task.subVariant, sampler, strategy,
                            scheduleSamplingOutputFolderPath, simulationJobConfigOutputFolderPath,
                            simulationRunOutputFolderPath);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }).toList();
    }

    private RailsimSimulationJob createJobForSample(int sampleIndex, Variant variant, SubVariant subVariant,
                                                    StatefulScheduleSampler sampler, DepartureSamplingStrategy strategy,
                                                    Path scheduleSamplingOutputFolderPath,
                                                    Path simulationJobConfigOutputFolderPath,
                                                    Path simulationRunOutputFolderPath) throws IOException {

        final String runId = String.format("%s_%s_sample_%d", buildingBlock.name().toLowerCase(),
                subVariant.getId().toLowerCase(), sampleIndex);

        log.debug("Sampling job: {}", runId);

        StatefulScheduleSampler.Sample sample = sampler.sample(strategy);

        Path sampleFilesPath = scheduleSamplingOutputFolderPath.resolve(subVariant.getId().toLowerCase())
                .resolve("sample_" + sampleIndex);
        Files.createDirectories(sampleFilesPath);
        Path schedulePath = sampleFilesPath.resolve("schedule.xml.gz");
        new TransitScheduleWriter(sample.schedule()).writeFile(schedulePath.toString());
        Path vehiclePath = sampleFilesPath.resolve("vehicles.xml.gz");
        new MatsimVehicleWriter(sample.vehicles()).writeFile(vehiclePath.toString());

        Path runOutputPath = simulationRunOutputFolderPath.resolve(subVariant.getId().toLowerCase()).resolve(runId);
        Path configFilePath = simulationJobConfigOutputFolderPath.resolve(subVariant.getId().toLowerCase())
                .resolve(runId + ".config.xml");
        Files.createDirectories(configFilePath.getParent());

        Config config = ConfigUtils.loadConfig(templateConfigFileInputPath.toString());
        config.controller().setRunId(runId);
        config.controller().setOutputDirectory(runOutputPath.toString());
        config.network().setInputFile(ResourceLoader.getPath(buildingBlock.getNetworkFilePath()).toString());
        config.transit().setTransitScheduleFile(configFilePath.getParent().relativize(schedulePath).toString());
        config.transit().setVehiclesFile(configFilePath.getParent().relativize(vehiclePath).toString());

        // set railsim specific config options: one iteration, disable unnecessary outputs
        RailsimConfigHelper.configure(config);

        ConfigUtils.writeConfig(config, configFilePath.toString());

        return new RailsimSimulationJob(configFilePath, buildingBlock, variant, subVariant, sampleIndex);
    }

    private record SubVariantTask(Variant variant, SubVariant subVariant) {
    }
}