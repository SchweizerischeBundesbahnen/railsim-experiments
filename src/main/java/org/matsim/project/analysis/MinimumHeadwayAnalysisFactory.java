package org.matsim.project.analysis;

import lombok.RequiredArgsConstructor;
import org.matsim.project.simulation.PostProcessingTask;
import org.matsim.project.simulation.PostProcessingTaskFactory;

import java.nio.file.Path;

@RequiredArgsConstructor
public class MinimumHeadwayAnalysisFactory implements PostProcessingTaskFactory {

    private final Path analysisOutputPath;

    @Override
    public PostProcessingTask<?> create() {
        return new MinimumHeadwayAnalysis(analysisOutputPath);
    }
}