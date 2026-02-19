package org.matsim.project.analysis.headway;

import lombok.RequiredArgsConstructor;
import org.matsim.project.simulation.PostProcessingTask;
import org.matsim.project.simulation.PostProcessingTaskFactory;

@RequiredArgsConstructor
public class MinimumHeadwayAnalysisFactory implements PostProcessingTaskFactory {
    private final boolean writeCsv;

    @Override
    public PostProcessingTask<?> create() {
        return new MinimumHeadwayAnalysis(writeCsv);
    }
}