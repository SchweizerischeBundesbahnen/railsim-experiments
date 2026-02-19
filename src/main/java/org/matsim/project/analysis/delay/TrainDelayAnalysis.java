package org.matsim.project.analysis.delay;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.MatsimEventsReader;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.project.simulation.PostProcessingResult;
import org.matsim.project.simulation.PostProcessingTask;
import org.matsim.project.simulation.RailsimSimulationJob;
import org.matsim.project.simulation.RailsimSimulationResult;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitScheduleReader;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;
import org.matsim.vehicles.MatsimVehicleReader;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Log4j2
@RequiredArgsConstructor
public class TrainDelayAnalysis implements PostProcessingTask<TrainDelayAnalysis.DelayReport> {

    private final boolean writeCsv;

    @Override
    public Class<DelayReport> getResultType() {
        return DelayReport.class;
    }

    @Override
    public DelayReport run(RailsimSimulationResult result) throws IOException {
        RailsimSimulationJob job = result.getJob();
        Config config = job.getConfig();

        // setup paths
        Path configDir = job.getConfigFilePath().getParent();
        Path schedulePath = configDir.resolve(config.transit().getTransitScheduleFile()).normalize();
        Path vehiclesPath = configDir.resolve(config.transit().getVehiclesFile()).normalize();
        Path eventsFile = job.getRunOutputFolderPath()
                .resolve("ITERS")
                .resolve("it.0")
                .resolve(config.controller().getRunId() + ".0.events.xml.gz");

        if (!Files.exists(eventsFile)) {
            throw new IOException("Events file not found: " + eventsFile);
        }

        // load schedule and vehicles
        Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        new TransitScheduleReader(scenario).readFile(schedulePath.toString());
        new MatsimVehicleReader(scenario.getTransitVehicles()).readFile(vehiclesPath.toString());

        // event analysis
        log.debug("Processing events for run {}.", config.controller().getRunId());
        TrainDelayEventHandler handler =
                new TrainDelayEventHandler(scenario.getTransitSchedule(), scenario.getTransitVehicles());
        EventsManager eventsManager = EventsUtils.createEventsManager();
        eventsManager.addHandler(handler);
        new MatsimEventsReader(eventsManager).readFile(eventsFile.toString());
        log.debug("Finished processing events for run {}.", config.controller().getRunId());

        DelayReport report = new DelayReport(handler.getStopEvents(), handler.getDepartedTrains().size(),
                handler.getArrivedTrains().size());

        if (writeCsv) {
            new TrainDelayWriter(job, report).write(job.getAnalysisOutputFolderPath());
        }

        return report;
    }

    public record DetailedStopInfo(Id<Vehicle> vehicleId, Id<Departure> departureId, Id<TransitRoute> routeId,
                                   Id<VehicleType> vehicleTypeId, int stopSequence, Id<TransitStopFacility> stopId,
                                   double plannedArrival, double actualArrival, double arrivalDelay,
                                   double plannedDeparture, double actualDeparture, double departureDelay) {
    }

    @Getter
    public static class DelayReport implements PostProcessingResult {
        private final List<DetailedStopInfo> detailedData;
        private final int trainsDeparted;
        private final int trainsArrived;
        private final int trainsStuck;

        public DelayReport(List<DetailedStopInfo> detailedData, int trainsDeparted, int trainsArrived) {
            this.detailedData = detailedData;
            this.trainsDeparted = trainsDeparted;
            this.trainsArrived = trainsArrived;
            this.trainsStuck = trainsDeparted - trainsArrived;
        }
    }
}