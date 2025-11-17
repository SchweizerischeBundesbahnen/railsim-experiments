package org.matsim.project.analysis.headway;

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

            for (HeadwayInfo info : this.report.detailedData()) {
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
        LINK_ID("link_id", info -> info.getLinkId().toString()),
        FOLLOWING_VEHICLE_ID("following_vehicle_id", info -> info.getFollowingVehicleId().toString()),
        FOLLOWING_VEHICLE_TYPE_ID("following_vehicle_type_id", info -> info.getFollowingVehicleTypeId().toString()),
        FOLLOWING_VEHICLE_ENTER_TIME("following_vehicle_enter_time",
                info -> formatDouble(info.getFollowingVehicleEnterTime())),

        PREVIOUS_VEHICLE_ID("previous_vehicle_id", info -> info.getPreviousVehicleId().toString()),
        PREVIOUS_VEHICLE_TYPE_ID("previous_vehicle_type_id", info -> info.getPreviousVehicleTypeId().toString()),
        PREVIOUS_VEHICLE_ENTER_TIME("previous_vehicle_enter_time",
                info -> formatDouble(info.getPreviousVehicleEnterTime())),
        PREVIOUS_VEHICLE_LEAVE_TIME("previous_vehicle_leave_time",
                info -> formatDouble(info.getPreviousVehicleLeaveTime())),

        HEADWAY_HEAD_TO_HEAD("headway_head_to_head", info -> formatDouble(info.getHeadwayHeadToHead())),
        HEADWAY_TAIL_TO_HEAD("headway_tail_to_head", info -> formatDouble(info.getHeadwayTailToHead())),

        MINIMUM_HEADWAY("minimum_headway", info -> formatDouble(info.getMinimumHeadway())),
        HEADWAY_VIOLATION_HEAD_TO_HEAD("headway_violation_head_to_head",
                info -> formatDouble(info.getViolationHeadToHead())),
        HEADWAY_VIOLATION_TAIL_TO_HEAD("headway_violation_tail_to_head",
                info -> formatDouble(info.getViolationTailToHead()));

        private final String header;
        private final Function<HeadwayInfo, String> valueExtractor;

        private static String formatDouble(double value) {
            return Double.isNaN(value) ? "" : String.format("%.2f", value);
        }
    }
}