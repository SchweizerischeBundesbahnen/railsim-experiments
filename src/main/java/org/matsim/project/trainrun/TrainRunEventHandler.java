package org.matsim.project.trainrun;

import lombok.Getter;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.TransitDriverStartsEvent;
import org.matsim.api.core.v01.events.handler.TransitDriverStartsEventHandler;
import org.matsim.core.api.experimental.events.VehicleArrivesAtFacilityEvent;
import org.matsim.core.api.experimental.events.handler.VehicleArrivesAtFacilityEventHandler;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;
import org.matsim.vehicles.Vehicle;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Event handler to capture train travel times between stops.
 */
public class TrainRunEventHandler implements TransitDriverStartsEventHandler, VehicleArrivesAtFacilityEventHandler {

    @Getter
    private final Map<Id<Departure>, Map<Id<TransitStopFacility>, Double>> arrivalTimesPerDeparture = new HashMap<>();
    private final Map<Id<Vehicle>, Id<Departure>> vehicleToDeparture = new HashMap<>();

    @Override
    public void handleEvent(TransitDriverStartsEvent event) {
        vehicleToDeparture.put(event.getVehicleId(), event.getDepartureId());
    }

    @Override
    public void handleEvent(VehicleArrivesAtFacilityEvent event) {
        Id<Departure> departureId = vehicleToDeparture.get(event.getVehicleId());
        if (departureId == null) {
            return;
        }

        arrivalTimesPerDeparture.computeIfAbsent(departureId, _ -> new LinkedHashMap<>())
                .put(event.getFacilityId(), event.getTime());
    }

    @Override
    public void reset(int iteration) {
        arrivalTimesPerDeparture.clear();
        vehicleToDeparture.clear();
    }
}