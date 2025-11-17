package org.matsim.project.analysis;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.matsim.project.analysis.delay.TrainDelayAnalysis;
import org.matsim.project.analysis.headway.HeadwayInfo;
import org.matsim.project.analysis.headway.MinimumHeadwayAnalysis;
import org.matsim.project.simulation.RailsimSimulationResult;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Log4j2
@RequiredArgsConstructor
public class RunSummaryWriter {

    private static final String SUMMARY_CSV = "summary_runs.csv";
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

    private static double sumTailToHeadViolations(MinimumHeadwayAnalysis.HeadwayReport report) {
        return report.detailedData().stream().mapToDouble(HeadwayInfo::getViolationTailToHead).sum();
    }

    private static double sumHeadToHeadViolations(MinimumHeadwayAnalysis.HeadwayReport report) {
        return report.detailedData().stream().mapToDouble(HeadwayInfo::getViolationHeadToHead).sum();
    }

    public void write(Path outputDirectory) throws IOException {
        Path summaryPath = outputDirectory.resolve(SUMMARY_CSV);
        log.debug("Aggregating {} results into summary at {}", results.size(), summaryPath);

        List<ReportableResult> reportableResults = results.stream()
                // filter for successful runs
                .filter(result -> result.getStatus() == RailsimSimulationResult.Status.SUCCESS)
                // unpack all required reports into the new sortable record
                .map(result -> {
                    Optional<TrainDelayAnalysis.DelayReport> delayOpt = result.getPostProcessingResult(
                            TrainDelayAnalysis.DelayReport.class);
                    Optional<MinimumHeadwayAnalysis.HeadwayReport> headwayOpt = result.getPostProcessingResult(
                            MinimumHeadwayAnalysis.HeadwayReport.class);
                    return new ReportableResult(result, delayOpt.orElse(null), headwayOpt.orElse(null));
                })
                // filter out any results where essential reports might be missing
                .filter(res -> res.delayReport() != null)
                // multi-level comparator for sorting
                .sorted(Comparator
                        // 1. sub-variant ID (alphabetical)
                        .comparing((ReportableResult res) -> res.result().getJob().getSubVariant().getId())
                        // 2. the number of stuck trains (ascending)
                        .thenComparingInt(res -> res.delayReport().getTrainsStuck())
                        // 3. total destination delay (ascending)
                        .thenComparingDouble(res -> sumDestinationDelays(res.delayReport()))
                        .thenComparingDouble(res -> sumTailToHeadViolations(res.headwayReport()))
                        .thenComparingDouble(res -> sumHeadToHeadViolations(res.headwayReport()))).toList();

        try (BufferedWriter writer = Files.newBufferedWriter(summaryPath)) {
            // write the header row
            writer.write(HEADER_ROW);
            writer.newLine();

            // write the data to the file
            for (ReportableResult res : reportableResults) {
                try {
                    String row = COLUMNS.stream()
                            .map(column -> column.valueExtractor.apply(res))
                            .collect(Collectors.joining(","));
                    writer.write(row);
                    writer.newLine();
                } catch (IOException e) {
                    throw new UncheckedIOException("Error writing summary line for run " + res.result().getRunId(), e);
                }
            }
        }
    }

    @RequiredArgsConstructor
    private enum Column {
        RUN_ID("run_id", res -> res.result().getJob().getRunId()),
        VARIANT("variant_id", res -> res.result().getJob().getVariant().getId()),
        SUBVARIANT("subvariant_id", res -> res.result().getJob().getSubVariant().getId()),
        SAMPLE("sample", res -> String.valueOf(res.result().getJob().getSample())),
        TOTAL_DELAY_AT_DESTINATION("total_delay_at_destination",
                res -> String.format("%.2f", sumDestinationDelays(res.delayReport()))),

        TOTAL_HEADWAY_VIOLATION_TAIL_TO_HEAD("total_headway_violation_tail_to_head",
                res -> String.format("%.2f", sumTailToHeadViolations(res.headwayReport()))),
        TOTAL_HEADWAY_VIOLATION_HEAD_TO_HEAD("total_headway_violation_head_to_head",
                res -> String.format("%.2f", sumHeadToHeadViolations(res.headwayReport()))),

        TRAINS_DEPARTED("trains_departed", res -> String.valueOf(res.delayReport().getTrainsDeparted())),
        TRAINS_ARRIVED("trains_arrived", res -> String.valueOf(res.delayReport().getTrainsArrived())),
        TRAINS_STUCK("trains_stuck", res -> String.valueOf(res.delayReport().getTrainsStuck()));

        private final String header;
        private final Function<ReportableResult, String> valueExtractor;
    }

    // hold all necessary reports for sorting and writing
    private record ReportableResult(RailsimSimulationResult result, TrainDelayAnalysis.DelayReport delayReport,
                                    MinimumHeadwayAnalysis.HeadwayReport headwayReport) {
    }
}