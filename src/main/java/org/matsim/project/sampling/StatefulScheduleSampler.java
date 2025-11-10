package org.matsim.project.sampling;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.project.sampling.strategy.DepartureSamplingStrategy;
import org.matsim.project.scenario.plan.SubVariant;
import org.matsim.project.scenario.plan.TrainVolume;
import org.matsim.pt.transitSchedule.api.*;
import org.matsim.vehicles.*;

import java.util.*;

@RequiredArgsConstructor
@Log4j2
public class StatefulScheduleSampler {

    private final Random random;
    private final Scenario scenario;
    private final SubVariant subVariant;
    private final int hours;

    private final Map<Id<TransitRoute>, Id<VehicleType>> routeVehicleType = new HashMap<>();

    public StatefulScheduleSampler(long seed, Scenario scenario, SubVariant subVariant, int hours) {
        this.random = new Random(seed);
        this.scenario = scenario;
        this.subVariant = subVariant;
        this.hours = hours;

        // assign unique vehicle type to route
        for (TransitLine transitLine : scenario.getTransitSchedule().getTransitLines().values()) {
            for (TransitRoute transitRoute : transitLine.getRoutes().values()) {
                // set of all vehicle types of all departures
                Set<Id<VehicleType>> vehicleTypeIds = new HashSet<>();
                transitRoute.getDepartures()
                        .values()
                        .forEach(departure -> vehicleTypeIds.add(scenario.getTransitVehicles()
                                .getVehicles()
                                .get(departure.getVehicleId())
                                .getType()
                                .getId()));

                if (vehicleTypeIds.size() != 1) {
                    throw new IllegalStateException(
                            String.format("Route %s has not unique or no vehicle types %s on departures ",
                                    transitRoute.getId().toString(), vehicleTypeIds));
                }

                routeVehicleType.put(transitRoute.getId(), vehicleTypeIds.iterator().next());
            }
        }
    }

    /**
     * Generates a new transit schedule with sampled departures.
     *
     * @param samplingStrategy The strategy to use for generating departure times (e.g., random, even interval).
     * @return A new, completely separate {@link TransitSchedule} instance.
     */
    public Sample sample(DepartureSamplingStrategy samplingStrategy) {
        final TransitSchedule templateSchedule = this.scenario.getTransitSchedule();

        // no need to deep copy, since no changes are made on stop facilities
        TransitSchedule newSchedule = scenario.getTransitSchedule().getFactory().createTransitSchedule();
        templateSchedule.getFacilities().values().forEach(newSchedule::addStopFacility);

        // no need to deep copy since transit vehicles types stay the same
        Vehicles newVehicles = VehicleUtils.createVehiclesContainer();
        this.scenario.getTransitVehicles().getVehicleTypes().values().forEach(newVehicles::addVehicleType);

        for (TrainVolume trainVolume : subVariant.getTrainVolumes()) {
            Match match = findMatchingRoute(trainVolume);
            addTransitRoute(newSchedule, newVehicles, match, samplingStrategy);
        }

        return new Sample(newSchedule, newVehicles);
    }

    private void addTransitRoute(TransitSchedule schedule, Vehicles vehicles, Match match,
                                 DepartureSamplingStrategy samplingStrategy) {

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
        TransitRoute newRoute = sf.createTransitRoute(match.transitRoute.getId(), match.transitRoute.getRoute(),
                match.transitRoute.getStops(), match.transitRoute.getTransportMode());

        // sample departure times and add departures
        List<Double> departureTimes = samplingStrategy.sampleDepartures(match.trainVolume.getAmount(), hours, random);
        int i = 1;
        for (double departureTime : departureTimes) {
            Id<Vehicle> vehicleId = Id.create("train_" + match.vehicleType.getId() + "_" + i, Vehicle.class);
            Vehicle vehicle = vf.createVehicle(vehicleId, match.vehicleType);
            vehicles.addVehicle(vehicle);

            Id<Departure> departureId = Id.create(match.transitRoute.getId() + "_dep_" + i, Departure.class);
            Departure departure = sf.createDeparture(departureId, departureTime);
            departure.setVehicleId(vehicleId);

            newRoute.addDeparture(departure);
            i++;
        }

        newLine.addRoute(newRoute);
    }

    /**
     * Finds a transit route in the template schedule that matches the product type and route ID.
     */
    private Match findMatchingRoute(TrainVolume trainVolume) {
        Id<TransitLine> lineId = Id.create(trainVolume.getProduct().name(), TransitLine.class);
        TransitLine transitLine = scenario.getTransitSchedule().getTransitLines().get(lineId);

        if (transitLine == null) {
            throw new IllegalStateException(String.format(
                    "Configuration error in sub-variant '%s': No matching transit line found for product '%s' defined in train volume: %s",
                    subVariant.getId(), trainVolume.getProduct(), trainVolume));
        }

        Id<TransitRoute> routeId = Id.create(trainVolume.getRoute(), TransitRoute.class);
        TransitRoute transitRoute = transitLine.getRoutes().get(routeId);
        if (transitRoute == null) {
            throw new IllegalStateException(String.format(
                    "Configuration error in sub-variant '%s': No matching transit route '%s' found in transit line '%s' for train volume: %s",
                    subVariant.getId(), trainVolume.getRoute(), lineId, trainVolume));
        }

        Id<VehicleType> vehicleTypeId = routeVehicleType.get(transitRoute.getId());
        log.debug("Found match for sub-variant {}: TrainVolume({} -> {}) uses route {} with vehicle type {}",
                subVariant.getId(), trainVolume.getProduct(), trainVolume.getRoute(), transitRoute.getId(),
                vehicleTypeId);

        VehicleType vehicleType = scenario.getTransitVehicles().getVehicleTypes().get(vehicleTypeId);
        return new Match(transitLine, transitRoute, vehicleType, trainVolume);
    }

    private record Match(TransitLine transitLine, TransitRoute transitRoute, VehicleType vehicleType,
                         TrainVolume trainVolume) {
    }

    public record Sample(TransitSchedule schedule, Vehicles vehicles) {
    }

}