package org.matsim.project.analysis;

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

@Log4j2
@RequiredArgsConstructor
public class MinimumHeadwayWriter {

    private static final String HEADWAY_CSV = "analysis_minimum_headways.csv";
    private static final List<Column> COLUMNS = List.of(Column.values());
    private static final String HEADER_ROW = COLUMNS.stream().map(c -> c.header).collect(Collectors.joining(","));

    private final RailsimSimulationJob job;
    private final MinimumHeadwayAnalysis.HeadwayReport report;

    public void write(Path outputDirectory) throws IOException {
        Path detailedPath = outputDirectory.resolve(job.getRunId() + "." + HEADWAY_CSV);
        log.debug("Writing detailed headways for run {} to {}", job.getRunId(), detailedPath);

        try (BufferedWriter writer = Files.newBufferedWriter(detailedPath)) {
            writer.write(HEADER_ROW);
            writer.newLine();

            for (MinimumHeadwayAnalysis.HeadwayInfo info : this.report.detailedData()) {
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
        LINK_ID("link_id", info -> info.linkId().toString()),
        ROUTE_ID("route_id", info -> info.routeId().toString()),
        DEPARTURE_ID("departure_id", info -> info.departureId().toString()),
        VEHICLE_TYPE_ID("vehicle_type_id", info -> info.vehicleTypeId().toString()),
        VEHICLE_ID("vehicle_id", info -> info.vehicleId().toString()),
        VEHICLE_TIME("vehicle_time", info -> String.format("%.2f", info.time())),
        PREVIOUS_VEHICLE_TIME("headway_vehicle_time", info -> formatDouble(info.previousVehicleTime())),
        HEADWAY("headway", info -> formatDouble(info.headway())),
        MINIMUM_HEADWAY("minimum_headway", info -> formatDouble(info.minimumHeadway())),
        PREVIOUS_VEHICLE_TYPE_ID("headway_vehicle_type_id",
                info -> info.previousVehicleTypeId() != null ? info.previousVehicleTypeId().toString() : ""),
        PREVIOUS_VEHICLE_ID("headway_vehicle_id",
                info -> info.previousVehicleId() != null ? info.previousVehicleId().toString() : "");

        private final String header;
        private final Function<MinimumHeadwayAnalysis.HeadwayInfo, String> valueExtractor;

        private static String formatDouble(double value) {
            return Double.isNaN(value) ? "" : String.format("%.2f", value);
        }
    }
}