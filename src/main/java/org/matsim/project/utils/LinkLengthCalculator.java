package org.matsim.project.utils;

import lombok.extern.log4j.Log4j2;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.NetworkWriter;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.io.MatsimNetworkReader;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.geometry.CoordUtils;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Utility to ensure network link lengths match the Euclidean distance of their nodes.
 */
@Log4j2
public class LinkLengthCalculator {

    static void main(String[] args) {
        Path input = Paths.get(args[0]);
        Path output = Paths.get(args[1]);
        double precision = Double.parseDouble(args[2]);

        new LinkLengthCalculator().run(input, output, precision);
    }

    public void run(Path inputPath, Path outputPath, double precision) {
        log.info("Recalculating link lengths. Input: {}, Precision: {}m", inputPath.getFileName(), precision);

        Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        Network network = scenario.getNetwork();
        new MatsimNetworkReader(network).readFile(inputPath.toString());

        int count = 0;
        for (Link link : network.getLinks().values()) {
            double rawDistance =
                    CoordUtils.calcEuclideanDistance(link.getFromNode().getCoord(), link.getToNode().getCoord());

            double roundedLength = Math.round(rawDistance / precision) * precision;

            // avoid 0-meter links
            if (roundedLength < precision) {
                roundedLength = precision;
            }

            link.setLength(roundedLength);
            count++;
        }

        log.info("Successfully updated {} links.", count);
        new NetworkWriter(network).write(outputPath.toString());
        log.info("New network saved to: {}", outputPath);
    }
}