package org.matsim.project.analysis;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.matsim.project.simulation.RailsimSimulationResult;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Aggregates results from multiple simulation runs and writes a final train delay summary report.
 * This class is instantiated with the full list of simulation results it needs to process.
 */
@Log4j2
@RequiredArgsConstructor
public class TrainDelaySummaryWriter {

    private static final String SUMMARY_CSV = "_SUMMARY.csv";
    private static final List<Column> COLUMNS = List.of(Column.values());
    private static final String HEADER_ROW = COLUMNS.stream().map(c -> c.header).collect(Collectors.joining(","));

    private final List<RailsimSimulationResult> results;

    // calculate the sum of arrival delays for only the final stop of each train run
    private static double sumDestinationDelays(TrainDelayAnalysis.DelayReport report) {
        // group all stop events by their vehicle ID to trace individual train runs
        Map<Object, List<TrainDelayAnalysis.DetailedStopInfo>> trainRuns = report.getDetailedData()
                .stream()
                .collect(Collectors.groupingBy(TrainDelayAnalysis.DetailedStopInfo::vehicleId));

        // for each train run, find the last stop and sum its arrival delay
        return trainRuns.values()
                .stream()
                .mapToDouble(journey -> journey.stream()
                        .max(Comparator.comparingInt(TrainDelayAnalysis.DetailedStopInfo::stopSequence))
                        .map(TrainDelayAnalysis.DetailedStopInfo::arrivalDelay)
                        .orElse(0.0))
                .sum();
    }

    /**
     * Aggregates the results and writes a single summary CSV file.
     *
     * @param outputDirectory The directory to write the summary file to.
     * @throws IOException If writing the summary file fails.
     */
    public void write(Path outputDirectory) throws IOException {
        Path summaryPath = outputDirectory.resolve(SUMMARY_CSV);
        log.info("Aggregating {} results into summary at {}", results.size(), summaryPath);

        try (BufferedWriter writer = Files.newBufferedWriter(summaryPath)) {
            // write the header row
            writer.write(HEADER_ROW);
            writer.newLine();

            // sort results by runId for consistent output and process them
            results.stream()
                    .filter(result -> result.getStatus() == RailsimSimulationResult.Status.SUCCESS)
                    .sorted(Comparator.comparing(RailsimSimulationResult::getRunId))
                    .forEach(result -> {
                        // retrieve the specific analysis report from the result object
                        result.getPostProcessingResult(TrainDelayAnalysis.DelayReport.class).ifPresent(report -> {
                            try {
                                String row = COLUMNS.stream()
                                        .map(column -> column.valueExtractor.apply(result, report))
                                        .collect(Collectors.joining(","));
                                writer.write(row);
                                writer.newLine();
                            } catch (IOException e) {
                                throw new RuntimeException("Error writing summary line for run " + result.getRunId(),
                                        e);
                            }
                        });
                    });
        }
    }

    @RequiredArgsConstructor
    private enum Column {
        RUN_ID("run_id", (result, report) -> result.getJob().getRunId()),
        VARIANT("variant_id", (result, report) -> result.getJob().getVariant().getId()),
        SUBVARIANT("subvariant_id", (result, report) -> result.getJob().getSubVariant().getId()),
        SAMPLE("sample", (result, report) -> String.valueOf(result.getJob().getSample())),
        TOTAL_DELAY_AT_DESTINATION("total_delay_at_destination",
                (result, report) -> String.format("%.2f", sumDestinationDelays(report))),
        TRAINS_DEPARTED("trains_departed", (result, report) -> String.valueOf(report.getTrainsDeparted())),
        TRAINS_ARRIVED("trains_arrived", (result, report) -> String.valueOf(report.getTrainsArrived())),
        TRAINS_STUCK("trains_stuck", (result, report) -> String.valueOf(report.getTrainsStuck()));

        private final String header;
        private final java.util.function.BiFunction<RailsimSimulationResult, TrainDelayAnalysis.DelayReport, String> valueExtractor;
    }
}