package org.matsim.project.sampling;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.project.sampling.strategy.DepartureSamplingStrategy;
import org.matsim.project.scenario.plan.OperatingMode;
import org.matsim.project.scenario.plan.Product;
import org.matsim.pt.transitSchedule.api.*;
import org.matsim.vehicles.*;

import java.util.*;

@RequiredArgsConstructor
@Log4j2
public class StatefulScheduleSampler {

    private final Random random;
    private final Scenario scenario;
    private final OperatingMode operatingMode;
    private final int samplingPeriod;
    private final int trainsPerPeriod;
    private final int simulationTime;
    private final boolean isBidirectional;

    private final Map<Id<TransitRoute>, Id<VehicleType>> routeVehicleType = new HashMap<>();

    public StatefulScheduleSampler(long seed, Scenario scenario, OperatingMode operatingMode, int samplingPeriod,
                                   int trainsPerPeriod, int simulationTime, boolean isBidirectional) {
        this.random = new Random(seed);
        this.scenario = scenario;
        this.operatingMode = operatingMode;
        this.samplingPeriod = samplingPeriod;
        this.trainsPerPeriod = trainsPerPeriod;
        this.simulationTime = simulationTime;
        this.isBidirectional = isBidirectional;

        // initialize route-to-vehicle-type mapping from template
        cacheRouteVehicleTypes();
    }

    /**
     * Generates a new transit schedule with sampled departures.
     */
    public Sample sample(DepartureSamplingStrategy samplingStrategy) {
        final TransitSchedule templateSchedule = this.scenario.getTransitSchedule();

        // no need to deep copy, since no changes are made on stop facilities
        TransitSchedule newSchedule = scenario.getTransitSchedule().getFactory().createTransitSchedule();
        templateSchedule.getFacilities().values().forEach(newSchedule::addStopFacility);

        // no need to deep copy, since transit vehicles types stay the same
        Vehicles newVehicles = VehicleUtils.createVehiclesContainer();
        this.scenario.getTransitVehicles().getVehicleTypes().values().forEach(newVehicles::addVehicleType);

        // distribute total volume across products and flows
        TrainVolumeDiscretizer distributor = new TrainVolumeDiscretizer(this.random);
        List<TrainVolumeDiscretizer.TrainVolume> distributedVolumes =
                distributor.discretize(this.trainsPerPeriod, this.operatingMode);

        // convert volumes into transit routes
        for (TrainVolumeDiscretizer.TrainVolume volume : distributedVolumes) {
            String fwdId = volume.routeMapping().getForwardRouteId();
            String revId = volume.routeMapping().getReverseRouteId();
            boolean hasReverse = revId != null && !revId.isBlank();

            // unidirectional case: sample forward route with amount
            if (!isBidirectional) {
                if (hasReverse) {
                    throw new IllegalArgumentException(String.format(
                            "Product %s: Global mode is unidirectional, but a reverse route '%s' was provided.",
                            volume.product().getId(), revId));
                }

                Match forwardMatch = findMatchingRoute(volume.product(), fwdId);
                addTransitRoute(newSchedule, newVehicles, forwardMatch, samplingStrategy, volume.amount());
            }

            // bidirectional case: sample both forward and reverse routes with amount each
            else {
                if (!hasReverse) {
                    throw new IllegalStateException(String.format(
                            "Product %s: Bidirectional mode enabled but no reverse route defined for forward route '%s'.",
                            volume.product().getId(), fwdId));
                }

                // one-way route (special case): sample forward with double amount
                if (fwdId.equals(revId)) {
                    Match match = findMatchingRoute(volume.product(), fwdId);
                    addTransitRoute(newSchedule, newVehicles, match, samplingStrategy, volume.amount() * 2, "one-way");

                } else {  // bidirectional route (standard): sample both directions
                    Match forwardMatch = findMatchingRoute(volume.product(), fwdId);
                    addTransitRoute(newSchedule, newVehicles, forwardMatch, samplingStrategy, volume.amount(),
                            "forward");

                    Match reverseMatch = findMatchingRoute(volume.product(), revId);
                    addTransitRoute(newSchedule, newVehicles, reverseMatch, samplingStrategy, volume.amount(),
                            "reverse");
                }
            }
        }

        return new Sample(newSchedule, newVehicles);
    }

    private void addTransitRoute(TransitSchedule schedule, Vehicles vehicles, Match match,
                                 DepartureSamplingStrategy samplingStrategy, int amount) {
        addTransitRoute(schedule, vehicles, match, samplingStrategy, amount, "");
    }

    private void addTransitRoute(TransitSchedule schedule, Vehicles vehicles, Match match,
                                 DepartureSamplingStrategy samplingStrategy, int amount, String directionSuffix) {

        TransitScheduleFactory sf = schedule.getFactory();
        VehiclesFactory vf = vehicles.getFactory();

        // get or create new transit line
        TransitLine newLine = schedule.getTransitLines().get(match.transitLine.getId());
        if (newLine == null) {
            newLine = sf.createTransitLine(match.transitLine.getId());
            schedule.addTransitLine(newLine);
        }

        // ensure a route is never added twice
        if (newLine.getRoutes().containsKey(match.transitRoute.getId())) {
            throw new RuntimeException("Duplicate route id " + match.transitRoute.getId());
        }

        // create route and sample departures
        String suffix = (directionSuffix == null || directionSuffix.isBlank()) ? "" : "_" + directionSuffix;
        Id<TransitRoute> scopedRouteId = Id.create(match.transitRoute.getId().toString() + suffix, TransitRoute.class);
        TransitRoute newRoute =
                sf.createTransitRoute(scopedRouteId, match.transitRoute.getRoute(), match.transitRoute.getStops(),
                        match.transitRoute.getTransportMode());

        // sample departure times
        List<Double> departureTimes =
                samplingStrategy.sampleDepartures(amount, this.samplingPeriod, this.simulationTime, random);

        for (int i = 0; i < departureTimes.size(); i++) {
            double time = departureTimes.get(i);

            // create unique vehicle
            Id<Vehicle> vehicleId = Id.create("veh_" + scopedRouteId + "_" + i, Vehicle.class);
            Vehicle vehicle = vf.createVehicle(vehicleId, match.vehicleType);
            vehicles.addVehicle(vehicle);

            // create departure
            Id<Departure> depId = Id.create("dep_" + scopedRouteId + "_" + i, Departure.class);
            Departure departure = sf.createDeparture(depId, time);
            departure.setVehicleId(vehicleId);
            newRoute.addDeparture(departure);
        }

        newLine.addRoute(newRoute);
    }

    private Match findMatchingRoute(Product product, String routeIdStr) {
        Id<TransitLine> lineId = Id.create(product.getId(), TransitLine.class);
        TransitLine line = scenario.getTransitSchedule().getTransitLines().get(lineId);

        if (line == null) {
            throw new IllegalStateException("Template schedule missing TransitLine: " + lineId);
        }

        Id<TransitRoute> routeId = Id.create(routeIdStr, TransitRoute.class);
        TransitRoute route = line.getRoutes().get(routeId);

        if (route == null) {
            throw new IllegalStateException(String.format("Template line '%s' missing route '%s'", lineId, routeId));
        }

        Id<VehicleType> vtId = routeVehicleType.get(route.getId());
        VehicleType vType = scenario.getTransitVehicles().getVehicleTypes().get(vtId);

        return new Match(line, route, vType);
    }

    private void cacheRouteVehicleTypes() {
        for (TransitLine line : scenario.getTransitSchedule().getTransitLines().values()) {
            for (TransitRoute route : line.getRoutes().values()) {
                Set<Id<VehicleType>> vTypes = new HashSet<>();
                route.getDepartures().values().forEach(dep -> {
                    Vehicle v = scenario.getTransitVehicles().getVehicles().get(dep.getVehicleId());
                    vTypes.add(v.getType().getId());
                });

                if (vTypes.size() != 1) {
                    throw new IllegalStateException("Route " + route.getId() + " must have exactly one vehicle type.");
                }
                routeVehicleType.put(route.getId(), vTypes.iterator().next());
            }
        }
    }

    private record Match(TransitLine transitLine, TransitRoute transitRoute, VehicleType vehicleType) {
    }

    public record Sample(TransitSchedule schedule, Vehicles vehicles) {
    }
}