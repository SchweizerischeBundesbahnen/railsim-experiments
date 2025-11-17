package org.matsim.project.analysis.delay;

import lombok.RequiredArgsConstructor;
import org.matsim.project.simulation.PostProcessingTask;
import org.matsim.project.simulation.PostProcessingTaskFactory;

import java.nio.file.Path;

/**
 * Factory for creating TrainDelayAnalysis tasks.
 * It holds references to the output path and a shared aggregator, which it injects
 * into each new task instance it creates.
 */
@RequiredArgsConstructor
public class TrainDelayAnalysisFactory implements PostProcessingTaskFactory {

    private final Path analysisOutputPath;

    @Override
    public PostProcessingTask<?> create() {
        return new TrainDelayAnalysis(analysisOutputPath);
    }
}