package org.matsim.project.analysis.utilization;

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
public class UtilizationWriter {

    private static final String UTILIZATION_CSV = "analysis_utilization.csv";
    private static final List<Column> COLUMNS = List.of(Column.values());
    private static final String HEADER_ROW = COLUMNS.stream().map(c -> c.header).collect(Collectors.joining(","));

    private final RailsimSimulationJob job;
    private final UtilizationAnalysis.UtilizationReport report;

    public void write(Path outputDirectory) throws IOException {
        Path path = outputDirectory.resolve(job.getRunId() + "." + UTILIZATION_CSV);
        log.debug("Writing utilization for run {} to {}", job.getRunId(), path);

        try (BufferedWriter writer = Files.newBufferedWriter(path)) {
            writer.write(HEADER_ROW);
            writer.newLine();

            for (UtilizationInfo info : this.report.detailedData()) {
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
        OBSERVATION_TIME("observation_time", info -> String.format("%.0f", info.getObservationTime())),
        EXHAUSTED_TIME("exhausted_time", info -> String.format("%.2f", info.getExhaustedTime())),
        UTILIZATION("utilization", info -> String.format("%.4f", info.getUtilization()));

        private final String header;
        private final Function<UtilizationInfo, String> valueExtractor;
    }
}