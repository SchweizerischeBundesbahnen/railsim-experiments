package org.matsim.project.analysis.delay;

import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.TransitDriverStartsEvent;
import org.matsim.api.core.v01.events.handler.TransitDriverStartsEventHandler;
import org.matsim.core.api.experimental.events.VehicleArrivesAtFacilityEvent;
import org.matsim.core.api.experimental.events.VehicleDepartsAtFacilityEvent;
import org.matsim.core.api.experimental.events.handler.VehicleArrivesAtFacilityEventHandler;
import org.matsim.core.api.experimental.events.handler.VehicleDepartsAtFacilityEventHandler;
import org.matsim.core.events.handler.EventHandler;
import org.matsim.pt.transitSchedule.api.*;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.Vehicles;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Event handler to capture train arrival and departure events at transit stop facilities.
 */
@Log4j2
@Getter
public class TrainDelayEventHandler implements VehicleArrivesAtFacilityEventHandler, VehicleDepartsAtFacilityEventHandler, TransitDriverStartsEventHandler, EventHandler {

    private final Map<Id<Vehicle>, VehicleState> vehicleStates = new HashMap<>();
    private final Map<Id<Departure>, Map<Id<TransitStopFacility>, StopEventData>> departureStopEvents =
            new LinkedHashMap<>();
    private final Set<Id<Vehicle>> departedTrains = new HashSet<>();
    private final Set<Id<Vehicle>> arrivedTrains = new HashSet<>();

    private final Vehicles vehicles;
    private final Map<Id<TransitRoute>, TransitRoute> routes;
    private final Map<Id<TransitRoute>, Map<Id<TransitStopFacility>, RouteStopInfo>> routeStopLookup;

    public TrainDelayEventHandler(TransitSchedule plannedSchedule, Vehicles vehicles) {
        this.vehicles = vehicles;
        this.routes = new HashMap<>();
        this.routeStopLookup = new HashMap<>();

        // pre-process the schedule for efficient lookups during event handling
        for (TransitLine line : plannedSchedule.getTransitLines().values()) {
            this.routes.putAll(line.getRoutes());
            for (TransitRoute route : line.getRoutes().values()) {

                Map<Id<TransitStopFacility>, RouteStopInfo> stopMap = new HashMap<>();
                List<TransitRouteStop> stops = route.getStops();

                for (int i = 0; i < stops.size(); i++) {
                    TransitRouteStop stop = stops.get(i);
                    stopMap.put(stop.getStopFacility().getId(), new RouteStopInfo(stop, i));
                }

                this.routeStopLookup.put(route.getId(), stopMap);
            }
        }
    }

    @Override
    public void handleEvent(TransitDriverStartsEvent event) {
        Id<Vehicle> vehicleId = event.getVehicleId();
        Vehicle vehicle = vehicles.getVehicles().get(vehicleId);
        if (vehicle == null) {
            throw new IllegalStateException("Vehicle with id " + vehicleId + " not found in vehicles file.");
        }

        TransitRoute route = this.routes.get(event.getTransitRouteId());
        if (route == null) {
            throw new IllegalStateException("Route with id " + event.getTransitRouteId() + " not found in schedule.");
        }

        Departure departure = route.getDepartures().get(event.getDepartureId());
        if (departure == null) {
            throw new IllegalStateException(
                    "Departure " + event.getDepartureId() + " not found in route " + route.getId());
        }

        vehicleStates.put(vehicleId,
                new VehicleState(vehicleId, event.getDepartureId(), event.getTransitLineId(), event.getTransitRouteId(),
                        vehicle.getType().getId(), departure.getDepartureTime()));
        departedTrains.add(vehicleId);
    }

    @Override
    public void handleEvent(VehicleArrivesAtFacilityEvent event) {
        VehicleState state = vehicleStates.get(event.getVehicleId());
        if (state == null) {
            // not a tracked transit vehicle, ignore
            return;
        }

        // get stop data using efficient lookup
        getStopData(state, event.getFacilityId()).ifPresent(data -> {
            data.actualArrival = event.getTime();

            // if at last stop, the train has arrived
            if (data.isLastStop()) {
                arrivedTrains.add(event.getVehicleId());
            }
        });
    }

    @Override
    public void handleEvent(VehicleDepartsAtFacilityEvent event) {
        VehicleState state = vehicleStates.get(event.getVehicleId());

        if (state == null) {
            return;
        }

        getStopData(state, event.getFacilityId()).ifPresent(data -> data.actualDeparture = event.getTime());
    }

    /**
     * Returns the collected data as a sorted list of immutable records.
     */
    public List<TrainDelayAnalysis.DetailedStopInfo> getStopEvents() {
        // create an efficient departureId to state lookup map for faster processing
        Map<Id<Departure>, VehicleState> departureToStateMap = this.vehicleStates.values()
                .stream()
                .collect(Collectors.toMap(VehicleState::departureId, Function.identity()));

        return this.departureStopEvents.entrySet().stream().flatMap(entry -> {
            Id<Departure> departureId = entry.getKey();
            VehicleState state = departureToStateMap.get(departureId);
            // sort stops by their sequence number and map to the final, immutable record
            return entry.getValue()
                    .values()
                    .stream()
                    .sorted(Comparator.comparingInt(StopEventData::getStopSequence))
                    .map(data -> data.toDetailedStopInfo(state));
        }).collect(Collectors.toList());
    }

    /**
     * Retrieves or creates a data container for a specific stop event.
     */
    private Optional<StopEventData> getStopData(VehicleState state, Id<TransitStopFacility> facilityId) {
        Map<Id<TransitStopFacility>, RouteStopInfo> stopInfoMap = this.routeStopLookup.get(state.routeId());
        if (stopInfoMap == null) {
            // route not found in pre-processed map
            return Optional.empty();
        }

        RouteStopInfo stopInfo = stopInfoMap.get(facilityId);
        if (stopInfo == null) {
            // stop not part of this vehicle's planned route
            return Optional.empty();
        }

        // get or create the map of stop events for this departure and the data object for the stop
        int totalStops = this.routes.get(state.routeId()).getStops().size();
        return Optional.of(departureStopEvents.computeIfAbsent(state.departureId(), _ -> new LinkedHashMap<>())
                .computeIfAbsent(facilityId,
                        _ -> new StopEventData(stopInfo.index(), totalStops, stopInfo.stop(), state.departureTime())));
    }

    @Override
    public void reset(int iteration) {
        vehicleStates.clear();
        departureStopEvents.clear();
        departedTrains.clear();
        arrivedTrains.clear();
    }

    /**
     * Store pre-calculated stop information for lookups.
     */
    private record RouteStopInfo(TransitRouteStop stop, int index) {
    }

    private record VehicleState(Id<Vehicle> vehicleId, Id<Departure> departureId, Id<TransitLine> lineId,
                                Id<TransitRoute> routeId, Id<VehicleType> vehicleTypeId, double departureTime) {
    }

    private static class StopEventData {
        @Getter
        private final int stopSequence;
        private final int totalStopsInRoute;
        private final Id<TransitStopFacility> stopId;
        private final double plannedArrival;
        private final double plannedDeparture;
        private double actualArrival = Double.NaN;
        private double actualDeparture = Double.NaN;

        StopEventData(int sequence, int totalStops, TransitRouteStop stop, double departureTime) {
            this.stopSequence = sequence;
            this.totalStopsInRoute = totalStops;
            this.stopId = stop.getStopFacility().getId();

            // calculate absolute planned times by adding the departure time to the offset
            this.plannedArrival = departureTime + stop.getArrivalOffset().seconds();
            this.plannedDeparture = departureTime + stop.getDepartureOffset().seconds();
        }

        boolean isLastStop() {
            return this.stopSequence == this.totalStopsInRoute - 1;
        }

        TrainDelayAnalysis.DetailedStopInfo toDetailedStopInfo(VehicleState state) {
            double pArr = plannedArrival;
            double aArr = actualArrival;
            double pDep = plannedDeparture;
            double aDep = actualDeparture;

            // first stop has no planned or actual arrival event, but its departure time is its planned arrival
            if (stopSequence == 0) {
                pArr = aArr = state.departureTime();
            }
            if (isLastStop()) {
                pDep = aDep = Double.NaN;
            }

            double arrivalDelay = (!Double.isNaN(aArr) && !Double.isNaN(pArr)) ? Math.max(0, aArr - pArr) : 0.0;
            double departureDelay = (!Double.isNaN(aDep) && !Double.isNaN(pDep)) ? Math.max(0, aDep - pDep) : 0.0;

            return new TrainDelayAnalysis.DetailedStopInfo(state.vehicleId(), state.departureId(), state.routeId(),
                    state.vehicleTypeId(), stopSequence, stopId, pArr, aArr, arrivalDelay, pDep, aDep, departureDelay);
        }
    }
}