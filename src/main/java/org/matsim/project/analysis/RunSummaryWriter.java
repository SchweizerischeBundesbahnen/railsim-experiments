package org.matsim.project.analysis;

import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.matsim.project.analysis.delay.TrainDelayAnalysis;
import org.matsim.project.analysis.headway.HeadwayInfo;
import org.matsim.project.analysis.headway.MinimumHeadwayAnalysis;
import org.matsim.project.analysis.utilization.UtilizationAnalysis;
import org.matsim.project.analysis.utilization.UtilizationInfo;
import org.matsim.project.simulation.RailsimSimulationResult;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Log4j2
@RequiredArgsConstructor
public class RunSummaryWriter {

    private static final String SUMMARY_CSV = "output_%s_summary.csv";
    private static final List<Column> COLUMNS = List.of(Column.values());
    private static final String HEADER_ROW = COLUMNS.stream().map(c -> c.header).collect(Collectors.joining(","));
    private final List<RailsimSimulationResult> results;
    private final int analysisStartTime;
    private final int analysisEndTime;

    public void write(Type type, Path outputDirectory) throws IOException {
        Path summaryPath = outputDirectory.resolve(String.format(SUMMARY_CSV, type.name().toLowerCase()));
        log.info("Aggregating {} results into global summary at {}", results.size(), summaryPath);

        // calculate all metrics and sort
        List<AnalyzedRun> analyzedRuns = results.stream()
                .filter(result -> result.getStatus() == RailsimSimulationResult.Status.SUCCESS)
                .map(this::analyze)
                .filter(ar -> ar.metrics != null) // filter out runs where analysis failed
                .sorted(comparator())
                .toList();

        // write the data
        try (BufferedWriter writer = Files.newBufferedWriter(summaryPath)) {
            writer.write(HEADER_ROW);
            writer.newLine();

            for (AnalyzedRun run : analyzedRuns) {
                try {
                    String row = COLUMNS.stream()
                            .map(column -> column.valueExtractor.apply(run))
                            .collect(Collectors.joining(","));
                    writer.write(row);
                    writer.newLine();
                } catch (UncheckedIOException e) {
                    throw new IOException("Error writing summary line for run " + run.result().getJob().getRunId(), e);
                }
            }
        }
    }

    /**
     * Transforms a raw simulation result into an AnalyzedRun containing metrics.
     */
    private AnalyzedRun analyze(RailsimSimulationResult result) {
        TrainDelayAnalysis.DelayReport delayReport =
                result.getPostProcessingResult(TrainDelayAnalysis.DelayReport.class).orElse(null);
        MinimumHeadwayAnalysis.HeadwayReport headwayReport =
                result.getPostProcessingResult(MinimumHeadwayAnalysis.HeadwayReport.class).orElse(null);
        UtilizationAnalysis.UtilizationReport utilizationReport =
                result.getPostProcessingResult(UtilizationAnalysis.UtilizationReport.class).orElse(null);

        if (delayReport == null || headwayReport == null || utilizationReport == null) {
            return new AnalyzedRun(result, null);
        }

        RunMetrics metrics = RunMetrics.builder()
                .trainsDeparted(delayReport.getTrainsDeparted())
                .trainsArrived(delayReport.getTrainsArrived())
                .trainsStuck(delayReport.getTrainsStuck())
                .windowUtilization(calculateAggregateUtilization(utilizationReport))
                .windowDelay(sumDestinationDelays(delayReport, analysisStartTime, analysisEndTime))
                .totalDelay(sumDestinationDelays(delayReport, 0, Integer.MAX_VALUE))
                .windowTailViolations(sumTailToHeadViolations(headwayReport, analysisStartTime, analysisEndTime))
                .windowHeadViolations(sumHeadToHeadViolations(headwayReport, analysisStartTime, analysisEndTime))
                .totalTailViolations(sumTailToHeadViolations(headwayReport, 0, Integer.MAX_VALUE))
                .totalHeadViolations(sumHeadToHeadViolations(headwayReport, 0, Integer.MAX_VALUE))
                .build();

        return new AnalyzedRun(result, metrics);
    }

    private Comparator<AnalyzedRun> comparator() {
        return Comparator
                // use case
                .comparing((AnalyzedRun ar) -> ar.result.getJob().getBuildingBlock().getUseCase().name())
                // building block
                .thenComparing((AnalyzedRun ar) -> ar.result.getJob().getBuildingBlock().name())
                // operating mode
                .thenComparing((AnalyzedRun ar) -> ar.result.getJob().getOperatingMode().getId())
                // train volume
                .thenComparingInt(ar -> ar.result.getJob().getTrainVolume())
                // trains stuck
                .thenComparingInt(ar -> ar.metrics.trainsStuck)
                // window delay
                .thenComparingDouble(ar -> ar.metrics.windowDelay);
    }

    private double sumDestinationDelays(TrainDelayAnalysis.DelayReport report, int from, int to) {
        Map<Object, List<TrainDelayAnalysis.DetailedStopInfo>> trainRuns = report.getDetailedData()
                .stream()
                .collect(Collectors.groupingBy(TrainDelayAnalysis.DetailedStopInfo::vehicleId));

        return trainRuns.values()
                .stream()
                .filter(journey -> journey.stream()
                        .min(Comparator.comparingInt(TrainDelayAnalysis.DetailedStopInfo::stopSequence))
                        .map(firstStop -> firstStop.plannedDeparture() >= from && firstStop.plannedDeparture() <= to)
                        .orElse(false))
                .mapToDouble(journey -> journey.stream()
                        .max(Comparator.comparingInt(TrainDelayAnalysis.DetailedStopInfo::stopSequence))
                        .map(TrainDelayAnalysis.DetailedStopInfo::arrivalDelay)
                        .orElse(0.0))
                .sum();
    }

    private double sumTailToHeadViolations(MinimumHeadwayAnalysis.HeadwayReport report, int from, int to) {
        if (report == null) {
            return 0.0;
        }
        return report.detailedData()
                .stream()
                .filter(h -> h.getFollowingVehicleEnterTime() >= from && h.getFollowingVehicleEnterTime() <= to)
                .mapToDouble(HeadwayInfo::getViolationTailToHead)
                .sum();
    }

    private double sumHeadToHeadViolations(MinimumHeadwayAnalysis.HeadwayReport report, int from, int to) {
        if (report == null) {
            return 0.0;
        }
        return report.detailedData()
                .stream()
                .filter(h -> h.getFollowingVehicleEnterTime() >= from && h.getFollowingVehicleEnterTime() <= to)
                .mapToDouble(HeadwayInfo::getViolationHeadToHead)
                .sum();
    }

    private double calculateAggregateUtilization(UtilizationAnalysis.UtilizationReport report) {
        if (report == null || report.detailedData().isEmpty()) {
            return 0.0;
        }
        double totalExhausted = 0.0;
        double totalObservation = 0.0;
        for (UtilizationInfo info : report.detailedData()) {
            totalExhausted += info.getExhaustedTime();
            totalObservation += info.getObservationTime();
        }
        return totalObservation == 0.0 ? 0.0 : totalExhausted / totalObservation;
    }

    public enum Type {
        RUN, RECONSTRUCT
    }

    @RequiredArgsConstructor
    private enum Column {
        USE_CASE("use_case", ar -> ar.result().getJob().getBuildingBlock().getUseCase().name()),
        BUILDING_BLOCK("building_block", ar -> ar.result().getJob().getBuildingBlock().name()),
        RUN_ID("run_id", ar -> ar.result().getJob().getRunId()),
        OPERATING_MODE_ID("operating_mode", ar -> ar.result().getJob().getOperatingMode().getId()),
        PRODUCT_MIX("product_mix", ar -> ar.result().getJob().getOperatingMode().getProductMix().getId()),
        FLOW_PATTERN("flow_pattern", ar -> ar.result().getJob().getOperatingMode().getFlowPattern().getId()),
        VOLUME("train_volume", ar -> String.valueOf(ar.result().getJob().getTrainVolume())),
        SAMPLE("sample_index", ar -> String.valueOf(ar.result().getJob().getSampleIndex())),

        // --- analysis window metrics ---
        ANALYSIS_WINDOW_UTILIZATION("analysis_window_utilization",
                ar -> String.format("%.4f", ar.metrics().windowUtilization())),
        ANALYSIS_WINDOW_DELAY_AT_DESTINATION("analysis_window_delay_at_destination",
                ar -> String.format("%.2f", ar.metrics().windowDelay())),
        ANALYSIS_WINDOW_HEADWAY_VIOLATION_TAIL_TO_HEAD("analysis_window_headway_violation_tail_to_head",
                ar -> String.format("%.2f", ar.metrics().windowTailViolations())),
        ANALYSIS_WINDOW_HEADWAY_VIOLATION_HEAD_TO_HEAD("analysis_window_headway_violation_head_to_head",
                ar -> String.format("%.2f", ar.metrics().windowHeadViolations())),

        // --- total (simulation end) metrics ---
        TOTAL_DELAY_AT_DESTINATION("total_delay_at_destination",
                ar -> String.format("%.2f", ar.metrics().totalDelay())),
        TOTAL_HEADWAY_VIOLATION_TAIL_TO_HEAD("total_headway_violation_tail_to_head",
                ar -> String.format("%.2f", ar.metrics().totalTailViolations())),
        TOTAL_HEADWAY_VIOLATION_HEAD_TO_HEAD("total_headway_violation_head_to_head",
                ar -> String.format("%.2f", ar.metrics().totalHeadViolations())),

        TRAINS_DEPARTED("trains_departed", ar -> String.valueOf(ar.metrics().trainsDeparted())),
        TRAINS_ARRIVED("trains_arrived", ar -> String.valueOf(ar.metrics().trainsArrived())),
        TRAINS_STUCK("trains_stuck", ar -> String.valueOf(ar.metrics().trainsStuck()));

        private final String header;
        private final Function<AnalyzedRun, String> valueExtractor;
    }

    /**
     * Holds the raw result and the computed metrics.
     */
    private record AnalyzedRun(RailsimSimulationResult result, RunMetrics metrics) {
    }

    /**
     * Pure data holder for double/int values to avoid re-calculation.
     */
    @Builder
    private record RunMetrics(int trainsDeparted, int trainsArrived, int trainsStuck, double windowUtilization,
                              double windowDelay, double totalDelay, double windowTailViolations,
                              double windowHeadViolations, double totalTailViolations, double totalHeadViolations) {
    }
}