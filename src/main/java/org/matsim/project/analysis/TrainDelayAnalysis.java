package org.matsim.project.analysis;

import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import org.matsim.api.core.v01.Scenario;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.MatsimEventsReader;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.project.simulation.PostSimulationTask;
import org.matsim.project.simulation.RailsimSimulationResult;
import org.matsim.pt.transitSchedule.api.TransitScheduleReader;
import org.matsim.vehicles.MatsimVehicleReader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Log4j2
public class TrainDelayAnalysis implements PostSimulationTask {

    public static final String RESULT_KEY = "train_delay_report";

    @Override
    public String getName() {
        return "TrainDelayAnalysis";
    }

    @Override
    public void run(RailsimSimulationResult result) throws IOException {
        Config config = result.getJob().getConfig();
        Path outputDirectory = result.getOutputDirectory();

        // 1. Resolve paths from config context
        Path configDir = result.getJob().getConfigFilePath().getParent();
        Path schedulePath = configDir.resolve(config.transit().getTransitScheduleFile()).normalize();
        Path vehiclesPath = configDir.resolve(config.transit().getVehiclesFile()).normalize();
        Path eventsFile = outputDirectory.resolve(config.controller().getRunId() + ".output_events.xml.gz");

        if (!Files.exists(eventsFile)) {
            throw new IOException("Events file not found: " + eventsFile);
        }

        // 2. Load necessary data
        Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        new TransitScheduleReader(scenario).readFile(schedulePath.toString());
        new MatsimVehicleReader(scenario.getTransitVehicles()).readFile(vehiclesPath.toString());

        // 3. Run event handler
        TrainDelayEventHandler handler = new TrainDelayEventHandler(scenario.getTransitSchedule(),
                scenario.getTransitVehicles());
        EventsManager eventsManager = EventsUtils.createEventsManager();
        eventsManager.addHandler(handler);
        new MatsimEventsReader(eventsManager).readFile(eventsFile.toString());
        log.info("Finished processing events for run {}.", config.controller().getRunId());

        // 4. Create report and attach it to the main result object
        DelayReport report = new DelayReport(result.getRunId(), handler.getStopEvents());
        result.addAnalysisResult(RESULT_KEY, report);
    }

    // Nested records for structured data
    public record StopDelayInfo(String subVariant, String train, String routeId, String vehicleType, String departureId,
                                int stopSequence, String stopId, double plannedArrival, double actualArrival,
                                double arrivalDelay, double plannedDeparture, double actualDeparture,
                                double departureDelay) {
    }

    @Getter
    public static class DelayReport {
        private final String runId;
        private final double totalArrivalDelay;
        private final double totalDepartureDelay;
        private final int delayedStopsCount;
        private final List<StopDelayInfo> detailedData;

        public DelayReport(String runId, List<StopDelayInfo> detailedData) {
            this.runId = runId;
            this.detailedData = detailedData;
            this.totalArrivalDelay = detailedData.stream().mapToDouble(StopDelayInfo::arrivalDelay).sum();
            this.totalDepartureDelay = detailedData.stream().mapToDouble(StopDelayInfo::departureDelay).sum();
            this.delayedStopsCount = (int) detailedData.stream()
                    .filter(d -> d.arrivalDelay() > 1 || d.departureDelay() > 1)
                    .count();
        }
    }
}