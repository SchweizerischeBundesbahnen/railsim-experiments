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
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

/**
 * Creates a set of {@link RailsimSimulationJob} by sampling new transit schedules from a template scenario.
 * <p>
 * The sampling process is thread-safe and guarantees reproducible results.
 * A master {@code seed} is used to derive a deterministic seed for each sub-variant, which isolates its
 * random number generation. This ensures that the concurrent processing of sub-variants remains deterministic.
 * <p>
 * For any given sub-variant, multiple samples are generated sequentially from a stateful sampler,
 * ensuring the sequence of samples (e.g., "sample 1", "sample 2") is also reproducible across runs.
 */
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
                                             Path simulationRunOutputFolderPath) {

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
            // derive a deterministic seed for each task, ensures each sub-variant's
            // sampling is reproducible across runs and independent of other variants
            long taskSeed = seed + task.subVariant.getId().hashCode();

            // stateful sampler: ensure a random, but repeatable sequence for each sub-variant
            StatefulScheduleSampler sampler = new StatefulScheduleSampler(taskSeed, templateScenario, task.subVariant);

            // for each sub variant, create a stream of samples (from 1 to n)
            return IntStream.rangeClosed(1, nSamplesPerSubvariant).mapToObj(sampleIndex -> {
                try {
                    // delegate the work for a single sample to a helper method.
                    return createJobForSample(sampleIndex, task.variant, task.subVariant, sampler, strategy,
                            scheduleSamplingOutputFolderPath, simulationJobConfigOutputFolderPath,
                            simulationRunOutputFolderPath);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }).toList();
    }

    /**
     * Performs all file I/O and config creation for a single simulation job sample.
     * This method is designed to be called in parallel.
     */
    private RailsimSimulationJob createJobForSample(int sampleIndex, Variant variant, SubVariant subVariant,
                                                    StatefulScheduleSampler sampler, DepartureSamplingStrategy strategy,
                                                    Path scheduleSamplingOutputFolderPath,
                                                    Path simulationJobConfigOutputFolderPath,
                                                    Path simulationRunOutputFolderPath) throws IOException {

        final String runId = String.format("%s_%s_sample_%d", buildingBlock.name().toLowerCase(),
                subVariant.getId().toLowerCase(), sampleIndex);

        log.debug("Sampling job: {}", runId);

        // sample departures and create a new schedule
        StatefulScheduleSampler.Sample sample = sampler.sample(strategy);

        // create directory for the new sample and write the schedule/vehicles files
        Path sampleFilesPath = scheduleSamplingOutputFolderPath.resolve(subVariant.getId().toLowerCase())
                .resolve("sample_" + sampleIndex);
        Files.createDirectories(sampleFilesPath);
        Path schedulePath = sampleFilesPath.resolve("schedule.xml.gz");
        new TransitScheduleWriter(sample.schedule()).writeFile(schedulePath.toString());
        Path vehiclePath = sampleFilesPath.resolve("vehicles.xml.gz");
        new MatsimVehicleWriter(sample.vehicles()).writeFile(vehiclePath.toString());

        // setup paths for the simulation run output and config file
        Path runOutputPath = simulationRunOutputFolderPath.resolve(subVariant.getId().toLowerCase()).resolve(runId);
        Path configFilePath = simulationJobConfigOutputFolderPath.resolve(subVariant.getId().toLowerCase())
                .resolve(runId + ".config.xml");
        Files.createDirectories(configFilePath.getParent());

        // create the simulation configuration
        Config config = ConfigUtils.loadConfig(templateConfigFileInputPath.toString());
        config.controller().setRunId(runId);
        config.controller().setOutputDirectory(runOutputPath.toString());
        config.controller()
                .setOverwriteFileSetting(OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);
        config.controller().setLastIteration(0);
        config.network().setInputFile(ResourceLoader.getPath(buildingBlock.getNetworkFilePath()).toString());

        // the relative path must be calculated from the config file's parent directory
        config.transit().setTransitScheduleFile(configFilePath.getParent().relativize(schedulePath).toString());
        config.transit().setVehiclesFile(configFilePath.getParent().relativize(vehiclePath).toString());

        // write the config file
        ConfigUtils.writeConfig(config, configFilePath.toString());

        // return runnable job
        return new RailsimSimulationJob(configFilePath, buildingBlock, variant, subVariant, sampleIndex);
    }

    /**
     * Bundle a SubVariant with its parent Variant, creating a self-contained "task" for parallel processing.
     */
    private record SubVariantTask(Variant variant, SubVariant subVariant) {
    }
}