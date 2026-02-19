package org.matsim.project.analysis.headway;

import lombok.RequiredArgsConstructor;
import org.matsim.project.simulation.PostProcessingTask;
import org.matsim.project.simulation.PostProcessingTaskFactory;

@RequiredArgsConstructor
public class MinimumHeadwayAnalysisFactory implements PostProcessingTaskFactory {

    @Override
    public PostProcessingTask<?> create() {
        return new MinimumHeadwayAnalysis();
    }
}