package org.matsim.project.analysis.utilization;

import ch.sbb.matsim.contrib.railsim.events.RailsimLinkStateChangeEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.matsim.api.core.v01.Scenario;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.MatsimEventsReader;
import org.matsim.core.network.io.MatsimNetworkReader;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.project.simulation.PostProcessingResult;
import org.matsim.project.simulation.PostProcessingTask;
import org.matsim.project.simulation.RailsimSimulationJob;
import org.matsim.project.simulation.RailsimSimulationResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Log4j2
@RequiredArgsConstructor
public class UtilizationAnalysis implements PostProcessingTask<UtilizationAnalysis.UtilizationReport> {

    private final Path analysisOutputPath;
    private final int analysisStartTime;
    private final int analysisEndTime;

    @Override
    public Class<UtilizationReport> getResultType() {
        return UtilizationReport.class;
    }

    @Override
    public UtilizationReport run(RailsimSimulationResult result) throws IOException {
        RailsimSimulationJob job = result.getJob();
        Config config = job.getConfig();

        Path networkPath = Path.of(config.network().getInputFile());
        Path eventsFile = result.getOutputDirectory()
                .resolve("ITERS")
                .resolve("it.0")
                .resolve(config.controller().getRunId() + ".0.events.xml.gz");

        if (!Files.exists(eventsFile)) {
            throw new IOException("Events file not found: " + eventsFile);
        }

        Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        new MatsimNetworkReader(scenario.getNetwork()).readFile(networkPath.toString());

        log.debug("Processing utilization events for run {} window [{}-{}]", config.controller().getRunId(),
                analysisStartTime, analysisEndTime);
        UtilizationEventHandler handler =
                new UtilizationEventHandler(scenario.getNetwork(), analysisStartTime, analysisEndTime);

        EventsManager eventsManager = EventsUtils.createEventsManager();
        eventsManager.addHandler(handler);
        MatsimEventsReader reader = new MatsimEventsReader(eventsManager);
        reader.addCustomEventMapper(RailsimLinkStateChangeEvent.EVENT_TYPE, new RailsimLinkStateChangeEventMapper());
        reader.readFile(eventsFile.toString());

        UtilizationReport report = new UtilizationReport(handler.getResults());
        Path outputPath = job.getOutputMirrorPath(analysisOutputPath);
        Files.createDirectories(outputPath);
        new UtilizationWriter(job, report).write(outputPath);

        return report;
    }

    public record UtilizationReport(List<UtilizationInfo> detailedData) implements PostProcessingResult {
    }
}