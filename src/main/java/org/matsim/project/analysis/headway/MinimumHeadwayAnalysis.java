package org.matsim.project.analysis.headway;

import ch.sbb.matsim.contrib.railsim.events.RailsimTrainLeavesLinkEvent;
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
import org.matsim.project.scenario.plan.OperationalPlan;
import org.matsim.project.scenario.plan.OperationalPlanReader;
import org.matsim.project.simulation.PostProcessingResult;
import org.matsim.project.simulation.PostProcessingTask;
import org.matsim.project.simulation.RailsimSimulationJob;
import org.matsim.project.simulation.RailsimSimulationResult;
import org.matsim.project.utils.ResourceLoader;
import org.matsim.pt.transitSchedule.api.TransitScheduleReader;
import org.matsim.vehicles.MatsimVehicleReader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Log4j2
@RequiredArgsConstructor
public class MinimumHeadwayAnalysis implements PostProcessingTask<MinimumHeadwayAnalysis.HeadwayReport> {

    @Override
    public Class<HeadwayReport> getResultType() {
        return HeadwayReport.class;
    }

    @Override
    public HeadwayReport run(RailsimSimulationResult result) throws IOException {
        RailsimSimulationJob job = result.getJob();
        Config config = job.getConfig();

        // setup paths
        Path configDir = job.getConfigFilePath().getParent();
        Path schedulePath = configDir.resolve(config.transit().getTransitScheduleFile()).normalize();
        Path vehiclesPath = configDir.resolve(config.transit().getVehiclesFile()).normalize();
        Path networkPath = Path.of(config.network().getInputFile());
        Path eventsFile = job.getRunOutputFolderPath()
                .resolve("ITERS")
                .resolve("it.0")
                .resolve(config.controller().getRunId() + ".0.events.xml.gz");

        if (!Files.exists(eventsFile)) {
            throw new IOException("Events file not found: " + eventsFile);
        }

        // load operational plan for headway definitions
        Path operationalPlanPath = ResourceLoader.getPath(job.getBuildingBlock().getUseCase().getOperationalPlanPath());
        OperationalPlan operationalPlan = new OperationalPlanReader().read(operationalPlanPath);

        // load scenario components
        Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        new MatsimNetworkReader(scenario.getNetwork()).readFile(networkPath.toString());
        new MatsimVehicleReader(scenario.getTransitVehicles()).readFile(vehiclesPath.toString());
        new TransitScheduleReader(scenario).readFile(schedulePath.toString());

        // run event analysis
        log.debug("Processing headway events for run {}.", config.controller().getRunId());
        MinimumHeadwayEventHandler handler = new MinimumHeadwayEventHandler(scenario, operationalPlan);
        EventsManager eventsManager = EventsUtils.createEventsManager();
        eventsManager.addHandler(handler);
        MatsimEventsReader reader = new MatsimEventsReader(eventsManager);
        reader.addCustomEventMapper(RailsimTrainLeavesLinkEvent.EVENT_TYPE, new RailsimTrainLeavesLinkEventMapper());
        reader.readFile(eventsFile.toString());
        log.debug("Finished processing headway events for run {}.", config.controller().getRunId());

        // write analysis results to file
        HeadwayReport report = new HeadwayReport(handler.getHeadwayEvents());
        new MinimumHeadwayWriter(job, report).write(job.getAnalysisOutputFolderPath());

        return report;
    }

    public record HeadwayReport(List<HeadwayInfo> detailedData) implements PostProcessingResult {
    }
}