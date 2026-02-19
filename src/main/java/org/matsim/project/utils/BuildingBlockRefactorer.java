package org.matsim.project.utils;

import lombok.extern.log4j.Log4j2;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.NetworkWriter;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.io.MatsimNetworkReader;
import org.matsim.core.population.routes.NetworkRoute;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.geometry.CoordUtils;
import org.matsim.project.scenario.BuildingBlock;
import org.matsim.pt.transitSchedule.api.*;
import org.matsim.vehicles.MatsimVehicleReader;
import org.matsim.vehicles.MatsimVehicleWriter;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Standardizes building block XMLs
 * <p>
 * Structure:
 * - Prunes network to only infrastructure used by the transit schedule
 * - Re-indexes nodes to n[col] or n[col]_[row] (indices start at 1, zero-padded)
 * - Re-indexes links to semantic f[i], r[i], v[i], loop[i] (zero-padded)
 * - Snaps transit stop facilities to the to-node of their respective links
 * - Enforces Euclidean geometry and unique node coordinates
 */
@Log4j2
public class BuildingBlockRefactorer {

    private static final double EPSILON = 1e-6;

    static void main(String[] args) {
        BuildingBlockRefactorer refactorer = new BuildingBlockRefactorer();
        if (args.length == 0) {
            // locate the source directory to update files in the repository
            Path resourcePath = Paths.get("src/main/resources");
            log.info("no arguments provided; refactoring all building blocks in {} with precision 1.0",
                    resourcePath.toAbsolutePath());

            for (BuildingBlock bb : BuildingBlock.values()) {
                // remove leading slash from input path to resolve against resource folder
                Path path = resourcePath.resolve(bb.getInputPath().substring(1));
                refactorer.run(path, 1.0);
            }
        } else if (args.length == 2) {
            refactorer.run(Paths.get(args[0]), Double.parseDouble(args[1]));
        } else {
            log.error("usage: BuildingBlockRefactorer [<folder_path> <precision_meters>]");
            System.exit(1);
        }
    }

    public void run(Path root, double precision) {
        log.info("refactoring building block at: {} [precision: {}m]", root, precision);

        Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        new MatsimNetworkReader(scenario.getNetwork()).readFile(root.resolve("network.xml").toString());
        new TransitScheduleReader(scenario).readFile(root.resolve("schedule.xml").toString());
        new MatsimVehicleReader(scenario.getTransitVehicles()).readFile(root.resolve("vehicles.xml").toString());

        // identify active infrastructure used by pt
        Set<Id<Link>> activeLinksIds = identifyActivePTInfrastructure(scenario.getTransitSchedule());

        // sort nodes spatially and fix matsim wildcard types with cast
        List<Node> activeNodes = scenario.getNetwork()
                .getNodes()
                .values()
                .stream()
                .filter(n -> activeLinksIds.stream().anyMatch(l -> isIncident(l, n, scenario.getNetwork())))
                .map(n -> (Node) n)
                .sorted(Comparator.comparingDouble((Node n) -> n.getCoord().getX())
                        .thenComparingDouble(n -> n.getCoord().getY()))
                .toList();

        // ensure no nodes share coordinates
        validateTopology(activeNodes);

        // compute grid-based indices with dynamic zero-padding
        Map<Id<Node>, String> nodeMapping = computeSpatialIndices(activeNodes);

        // build new network with semantic ids
        Network newNet = NetworkUtils.createNetwork();
        activeNodes.forEach(n -> newNet.addNode(
                NetworkUtils.createNode(Id.createNodeId(nodeMapping.get(n.getId())), n.getCoord())));

        // sort links by start node order for deterministic enumeration
        List<Link> sortedLinks = activeLinksIds.stream()
                .map(id -> (Link) scenario.getNetwork().getLinks().get(id))
                .sorted(Comparator.comparingInt(l -> activeNodes.indexOf(l.getFromNode())))
                .toList();

        // categorize and map links with dynamic padding (f, r, v, loop)
        Map<Id<Link>, String> linkMapping = generateLinkMapping(sortedLinks);

        // reconstruct links into new network
        for (Link old : sortedLinks) {
            String newIdStr = linkMapping.get(old.getId());
            String fromIdx = nodeMapping.get(old.getFromNode().getId());
            String toIdx = nodeMapping.get(old.getToNode().getId());

            // geometry normalization
            double dist = CoordUtils.calcEuclideanDistance(old.getFromNode().getCoord(), old.getToNode().getCoord());
            double length = Math.max(precision, Math.round(dist / precision) * precision);

            Link newLink =
                    NetworkUtils.createLink(Id.createLinkId(newIdStr), newNet.getNodes().get(Id.createNodeId(fromIdx)),
                            newNet.getNodes().get(Id.createNodeId(toIdx)), newNet, length, old.getFreespeed(),
                            old.getCapacity(), old.getNumberOfLanes());

            // preserve railsim attributes
            old.getAttributes().getAsMap().forEach((k, v) -> newLink.getAttributes().putAttribute(k, v));
            newLink.setAllowedModes(old.getAllowedModes());
            newNet.addLink(newLink);
        }

        // update pt schedule and snap stops exactly to network nodes
        syncScheduleAndAlignStops(scenario.getTransitSchedule(), newNet, linkMapping);

        // write cleaned files
        writeOutputs(root, newNet, scenario);

        log.info("success: nodes: {} | links: {}", newNet.getNodes().size(), newNet.getLinks().size());
    }

    private Map<Id<Node>, String> computeSpatialIndices(List<Node> sortedNodes) {
        Map<Id<Node>, String> mapping = new HashMap<>();
        List<Double> xLevels = sortedNodes.stream().map(n -> n.getCoord().getX()).distinct().sorted().toList();
        Map<Double, List<Node>> columnGroups =
                sortedNodes.stream().collect(Collectors.groupingBy(n -> n.getCoord().getX()));

        // determine padding width based on max column and max vertical stack
        int colWidth = String.valueOf(xLevels.size()).length();
        int maxRow = columnGroups.values().stream().mapToInt(List::size).max().orElse(1);
        int rowWidth = String.valueOf(maxRow).length();

        String colFormat = "%0" + colWidth + "d";
        String rowFormat = "%0" + rowWidth + "d";

        for (int i = 0; i < xLevels.size(); i++) {
            double x = xLevels.get(i);
            String colStr = String.format(colFormat, i + 1);
            List<Node> verticalNodes =
                    columnGroups.get(x).stream().sorted(Comparator.comparingDouble(n -> n.getCoord().getY())).toList();

            for (int rowIdx = 0; rowIdx < verticalNodes.size(); rowIdx++) {
                // hide row index if column has only one node
                String id = (verticalNodes.size() == 1) ?
                        "n" + colStr :
                        "n" + colStr + "_" + String.format(rowFormat, rowIdx + 1);
                mapping.put(verticalNodes.get(rowIdx).getId(), id);
            }
        }
        return mapping;
    }

    private Map<Id<Link>, String> generateLinkMapping(List<Link> sortedLinks) {
        Map<Id<Link>, String> mapping = new HashMap<>();
        List<Link> fwd = new ArrayList<>();
        List<Link> rev = new ArrayList<>();
        List<Link> vert = new ArrayList<>();
        List<Link> loop = new ArrayList<>();

        for (Link l : sortedLinks) {
            String type = classifyLink(l);
            switch (type) {
                case "f" -> fwd.add(l);
                case "r" -> rev.add(l);
                case "v" -> vert.add(l);
                default -> loop.add(l);
            }
        }

        // calculate individual padding widths for clean sorting
        int pad = String.valueOf(
                Stream.of(fwd.size(), rev.size(), vert.size(), loop.size()).max(Integer::compareTo).get()).length();
        assignLinkIds(fwd, "f", pad, mapping);
        assignLinkIds(rev.reversed(), "r", pad, mapping);
        assignLinkIds(vert, "v", pad, mapping);
        assignLinkIds(loop, "loop", pad, mapping);

        return mapping;
    }

    private void assignLinkIds(List<Link> links, String prefix, int width, Map<Id<Link>, String> mapping) {
        String format = prefix + "%0" + width + "d";
        for (int i = 0; i < links.size(); i++) {
            mapping.put(links.get(i).getId(), String.format(format, i + 1));
        }
    }

    private void validateTopology(List<Node> nodes) {
        for (int i = 0; i < nodes.size() - 1; i++) {
            if (nodes.get(i).getCoord().equals(nodes.get(i + 1).getCoord())) {
                throw new IllegalStateException("topology error: redundant nodes at " + nodes.get(i).getCoord());
            }
        }
    }

    private String classifyLink(Link link) {
        // identify loops by node topology
        if (link.getFromNode().getId().equals(link.getToNode().getId())) {
            return "loop";
        }

        Coord from = link.getFromNode().getCoord();
        Coord to = link.getToNode().getCoord();
        double dx = to.getX() - from.getX();

        // classify vertical if horizontal movement is negligible
        if (Math.abs(dx) < EPSILON) {
            return "v";
        }

        // horizontal directionality
        return (dx > 0) ? "f" : "r";
    }

    private boolean isIncident(Id<Link> lId, Node n, Network net) {
        Link l = net.getLinks().get(lId);
        return l != null && (l.getFromNode().equals(n) || l.getToNode().equals(n));
    }

    private Set<Id<Link>> identifyActivePTInfrastructure(TransitSchedule schedule) {
        Set<Id<Link>> active = new HashSet<>();
        for (TransitLine line : schedule.getTransitLines().values()) {
            for (TransitRoute route : line.getRoutes().values()) {
                NetworkRoute r = route.getRoute();
                active.add(r.getStartLinkId());
                active.addAll(r.getLinkIds());
                active.add(r.getEndLinkId());
            }
        }
        schedule.getFacilities().values().forEach(f -> active.add(f.getLinkId()));
        return active;
    }

    private void syncScheduleAndAlignStops(TransitSchedule schedule, Network newNet,
                                           Map<Id<Link>, String> linkMapping) {
        // update facilities and snap coordinates to the to-node of the new link
        for (TransitStopFacility facility : schedule.getFacilities().values()) {
            Id<Link> newLinkId = Id.createLinkId(linkMapping.get(facility.getLinkId()));
            facility.setLinkId(newLinkId);

            // align coordinate with link to-node
            Link newLink = newNet.getLinks().get(newLinkId);
            if (newLink != null) {
                facility.setCoord(newLink.getToNode().getCoord());
            }
        }

        // update routes
        for (TransitLine line : schedule.getTransitLines().values()) {
            for (TransitRoute route : line.getRoutes().values()) {
                NetworkRoute netRoute = route.getRoute();
                Id<Link> start = Id.createLinkId(linkMapping.get(netRoute.getStartLinkId()));
                Id<Link> end = Id.createLinkId(linkMapping.get(netRoute.getEndLinkId()));
                List<Id<Link>> mid = netRoute.getLinkIds()
                        .stream()
                        .map(old -> Id.createLinkId(linkMapping.get(old)))
                        .collect(Collectors.toList());
                netRoute.setStartLinkId(start);
                netRoute.setEndLinkId(end);
                netRoute.setLinkIds(start, mid, end);
            }
        }
    }

    private void writeOutputs(Path root, Network net, Scenario scenario) {
        new NetworkWriter(net).write(root.resolve("network.xml").toString());
        new TransitScheduleWriter(scenario.getTransitSchedule()).writeFile(root.resolve("schedule.xml").toString());
        new MatsimVehicleWriter(scenario.getTransitVehicles()).writeFile(root.resolve("vehicles.xml").toString());
    }
}