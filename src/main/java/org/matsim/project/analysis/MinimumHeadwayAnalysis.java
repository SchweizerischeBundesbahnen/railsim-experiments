package org.matsim.project.analysis;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.MatsimEventsReader;
import org.matsim.core.network.io.MatsimNetworkReader;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.project.scenario.plan.OperationalPlan;
import org.matsim.project.scenario.plan.OperationalPlanReader;
import org.matsim.project.simulation.PostProcessingResult;
import org.matsim.project.simulation.PostProcessingTask;
import org.matsim.project.simulation.RailsimSimulationJob;
import org.matsim.project.simulation.RailsimSimulationResult;
import org.matsim.project.utils.ResourceLoader;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitScheduleReader;
import org.matsim.vehicles.MatsimVehicleReader;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Log4j2
@RequiredArgsConstructor
public class MinimumHeadwayAnalysis implements PostProcessingTask<MinimumHeadwayAnalysis.HeadwayReport> {

    private final Path analysisOutputPath;

    @Override
    public Class<HeadwayReport> getResultType() {
        return HeadwayReport.class;
    }

    @Override
    public HeadwayReport run(RailsimSimulationResult result) throws IOException {
        RailsimSimulationJob job = result.getJob();
        Config config = job.getConfig();

        // setup paths
        Path configDir = job.getConfigFilePath().getParent();
        Path schedulePath = configDir.resolve(config.transit().getTransitScheduleFile()).normalize();
        Path vehiclesPath = configDir.resolve(config.transit().getVehiclesFile()).normalize();
        Path networkPath = Path.of(config.network().getInputFile());
        Path eventsFile = result.getOutputDirectory()
                .resolve("ITERS")
                .resolve("it.0")
                .resolve(config.controller().getRunId() + ".0.events.xml.gz");

        if (!Files.exists(eventsFile)) {
            throw new IOException("Events file not found: " + eventsFile);
        }

        // load operational plan for headway definitions
        Path operationalPlanPath = ResourceLoader.getPath(job.getBuildingBlock().getUseCase().getOperationalPlanPath());
        OperationalPlan operationalPlan = new OperationalPlanReader().read(operationalPlanPath);

        // load scenario components
        Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        new MatsimNetworkReader(scenario.getNetwork()).readFile(networkPath.toString());
        new MatsimVehicleReader(scenario.getTransitVehicles()).readFile(vehiclesPath.toString());
        new TransitScheduleReader(scenario).readFile(schedulePath.toString());

        // run event analysis
        log.debug("Processing headway events for run {}.", config.controller().getRunId());
        MinimumHeadwayEventHandler handler = new MinimumHeadwayEventHandler(scenario, operationalPlan);
        EventsManager eventsManager = EventsUtils.createEventsManager();
        eventsManager.addHandler(handler);
        new MatsimEventsReader(eventsManager).readFile(eventsFile.toString());
        log.debug("Finished processing headway events for run {}.", config.controller().getRunId());

        // Write analysis results to file
        HeadwayReport report = new HeadwayReport(handler.getHeadwayEvents());
        Path headwayOutputPath = this.analysisOutputPath.resolve(job.getSubVariant().getId().toLowerCase())
                .resolve(job.getRunId());
        Files.createDirectories(headwayOutputPath);
        new MinimumHeadwayWriter(job, report).write(headwayOutputPath);

        return report;
    }

    public record HeadwayInfo(double time, Id<Link> linkId, Id<Vehicle> vehicleId, Id<Departure> departureId,
                              Id<TransitRoute> routeId, Id<VehicleType> vehicleTypeId, double headway,
                              double minimumHeadway, double previousVehicleTime, Id<Vehicle> previousVehicleId,
                              Id<VehicleType> previousVehicleTypeId) {
    }

    public record HeadwayReport(List<HeadwayInfo> detailedData) implements PostProcessingResult {
    }
}