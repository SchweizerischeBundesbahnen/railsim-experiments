package org.matsim.project.analysis.delay;

import lombok.RequiredArgsConstructor;
import org.matsim.project.simulation.PostProcessingTask;
import org.matsim.project.simulation.PostProcessingTaskFactory;

/**
 * Factory for creating TrainDelayAnalysis tasks.
 */
@RequiredArgsConstructor
public class TrainDelayAnalysisFactory implements PostProcessingTaskFactory {

    @Override
    public PostProcessingTask<?> create() {
        return new TrainDelayAnalysis();
    }
}