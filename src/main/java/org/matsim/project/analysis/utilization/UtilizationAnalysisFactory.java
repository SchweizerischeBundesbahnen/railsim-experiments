package org.matsim.project.analysis.utilization;

import lombok.RequiredArgsConstructor;
import org.matsim.project.simulation.PostProcessingTask;
import org.matsim.project.simulation.PostProcessingTaskFactory;

import java.nio.file.Path;

@RequiredArgsConstructor
public class UtilizationAnalysisFactory implements PostProcessingTaskFactory {
    private final Path analysisOutputPath;
    private final int analysisStartTime;
    private final int analysisEndTime;

    @Override
    public PostProcessingTask<?> create() {
        return new UtilizationAnalysis(analysisOutputPath, analysisStartTime, analysisEndTime);
    }
}