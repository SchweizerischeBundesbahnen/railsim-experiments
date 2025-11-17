package org.matsim.project.utils;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.NetworkWriter;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigWriter;
import org.matsim.core.config.groups.ControllerConfigGroup;
import org.matsim.core.config.groups.RoutingConfigGroup;
import org.matsim.core.config.groups.VspExperimentalConfigGroup;
import org.matsim.core.controler.Controller;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.pt.transitSchedule.api.TransitScheduleWriter;
import org.matsim.vehicles.MatsimVehicleWriter;

import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class RailsimConfigHelper {

    public static void configure(Config config) {
        config.global().setNumberOfThreads(1);

        config.controller().setLastIteration(0);
        config.controller()
                .setOverwriteFileSetting(OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);
        config.controller().setEventsFileFormats(EnumSet.of(ControllerConfigGroup.EventsFileFormat.xml));
        config.controller().setCreateGraphsInterval(0);
        config.controller().setDumpDataAtEnd(false);
        config.controller().setWriteEventsInterval(1);
        config.controller().setLegDurationsInterval(0);
        config.controller().setLegHistogramInterval(0);
        config.controller().setWritePlansInterval(0);
        config.controller().setWriteTripsInterval(0);
        config.controller().setWriteSnapshotsInterval(0);

        config.linkStats().setWriteLinkStatsInterval(0);
        config.linkStats().setAverageLinkStatsOverIterations(0);

        config.routing().setRoutingRandomness(0);
        config.routing().setNetworkModes(List.of());
        config.routing().setNetworkRouteConsistencyCheck(RoutingConfigGroup.NetworkRouteConsistencyCheck.disable);

        config.vspExperimental().setWritingOutputEvents(false);
        config.vspExperimental()
                .setVspDefaultsCheckingLevel(VspExperimentalConfigGroup.VspDefaultsCheckingLevel.ignore);
    }

    public static void writeStaticOutputFiles(Controller controller) {
        Config config = controller.getConfig();
        Scenario scenario = controller.getScenario();
        Path outputPath = Path.of(config.controller().getOutputDirectory());

        new ConfigWriter(config, ConfigWriter.Verbosity.minimal).write(
                outputPath.resolve(config.controller().getRunId() + ".output_config_reduced.xml").toString());
        new NetworkWriter(scenario.getNetwork()).write(
                outputPath.resolve(config.controller().getRunId() + ".output_network.xml.gz").toString());
        new TransitScheduleWriter(scenario.getTransitSchedule()).writeFile(
                outputPath.resolve(config.controller().getRunId() + ".output_transitSchedule.xml.gz").toString());
        new MatsimVehicleWriter(scenario.getTransitVehicles()).writeFile(
                outputPath.resolve(config.controller().getRunId() + ".output_transitVehicles.xml.gz").toString());
    }

}
