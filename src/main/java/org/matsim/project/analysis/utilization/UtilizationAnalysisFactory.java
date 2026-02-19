package org.matsim.project.analysis.utilization;

import lombok.RequiredArgsConstructor;
import org.matsim.project.simulation.PostProcessingTask;
import org.matsim.project.simulation.PostProcessingTaskFactory;

@RequiredArgsConstructor
public class UtilizationAnalysisFactory implements PostProcessingTaskFactory {
    private final int analysisStartTime;
    private final int analysisEndTime;

    @Override
    public PostProcessingTask<?> create() {
        return new UtilizationAnalysis(analysisStartTime, analysisEndTime);
    }
}