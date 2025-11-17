package org.matsim.project.analysis.delay;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.matsim.project.simulation.RailsimSimulationJob;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Writes the train delay analysis report to a CSV file.
 */
@Log4j2
@RequiredArgsConstructor
public class TrainDelayWriter {

    private static final String TRAIN_DELAYS_CSV = "analysis_train_delays.csv";
    private static final List<Column> COLUMNS = List.of(Column.values());
    private static final String HEADER_ROW = COLUMNS.stream().map(c -> c.header).collect(Collectors.joining(","));

    private final RailsimSimulationJob job;
    private final TrainDelayAnalysis.DelayReport report;

    /**
     * Writes a detailed CSV report for a single simulation run.
     * The report contains one row per stop for every train journey.
     *
     * @param outputDirectory The directory to write the file to.
     * @throws IOException If writing fails.
     */
    public void write(Path outputDirectory) throws IOException {
        Path detailedPath = outputDirectory.resolve(job.getRunId() + "." + TRAIN_DELAYS_CSV);
        log.debug("Writing detailed stop delays for run {} to {}", job.getRunId(), detailedPath);

        try (BufferedWriter writer = Files.newBufferedWriter(detailedPath)) {
            // write the header row
            writer.write(HEADER_ROW);
            writer.newLine();

            // write the data rows by applying the extractor function from the enum
            for (TrainDelayAnalysis.DetailedStopInfo info : this.report.getDetailedData()) {
                String row = COLUMNS.stream()
                        .map(column -> column.valueExtractor.apply(info))
                        .collect(Collectors.joining(","));
                writer.write(row);
                writer.newLine();
            }
        }
    }

    @RequiredArgsConstructor
    private enum Column {
        VEHICLE_ID("vehicle_id", info -> info.vehicleId().toString()),
        DEPARTURE_ID("departure_id", info -> info.departureId().toString()),
        ROUTE_ID("route_id", info -> info.routeId().toString()),
        VEHICLE_TYPE_ID("vehicle_type_id", info -> info.vehicleTypeId().toString()),
        STOP_SEQUENCE("stop_sequence", info -> String.valueOf(info.stopSequence())),
        STOP_ID("stop_id", info -> info.stopId().toString()),
        PLANNED_ARRIVAL("planned_arrival", info -> formatDouble(info.plannedArrival())),
        ACTUAL_ARRIVAL("actual_arrival", info -> formatDouble(info.actualArrival())),
        ARRIVAL_DELAY("arrival_delay", info -> String.format("%.2f", info.arrivalDelay())),
        PLANNED_DEPARTURE("planned_departure", info -> formatDouble(info.plannedDeparture())),
        ACTUAL_DEPARTURE("actual_departure", info -> formatDouble(info.actualDeparture())),
        DEPARTURE_DELAY("departure_delay", info -> String.format("%.2f", info.departureDelay()));

        private final String header;
        private final Function<TrainDelayAnalysis.DetailedStopInfo, String> valueExtractor;

        private static String formatDouble(double value) {
            return Double.isNaN(value) ? "" : String.format("%.2f", value);
        }
    }
}