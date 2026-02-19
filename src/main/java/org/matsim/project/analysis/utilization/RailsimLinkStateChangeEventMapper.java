package org.matsim.project.analysis.utilization;

import ch.sbb.matsim.contrib.railsim.events.RailsimLinkStateChangeEvent;
import ch.sbb.matsim.contrib.railsim.qsimengine.resources.ResourceState;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.GenericEvent;
import org.matsim.api.core.v01.network.Link;
import org.matsim.core.events.MatsimEventsReader;
import org.matsim.vehicles.Vehicle;

import java.util.Map;

public class RailsimLinkStateChangeEventMapper implements MatsimEventsReader.CustomEventMapper {

    @Override
    public RailsimLinkStateChangeEvent apply(GenericEvent genericEvent) {
        Map<String, String> attributes = genericEvent.getAttributes();

        Id<Link> linkId = Id.createLinkId(attributes.get(RailsimLinkStateChangeEvent.ATTRIBUTE_LINK));
        Id<Vehicle> vehicleId = Id.create(attributes.get(RailsimLinkStateChangeEvent.ATTRIBUTE_VEHICLE), Vehicle.class);
        ResourceState state = ResourceState.valueOf(attributes.get(RailsimLinkStateChangeEvent.ATTRIBUTE_STATE));
        boolean dla = Boolean.parseBoolean(attributes.get(RailsimLinkStateChangeEvent.ATTRIBUTE_DLA));

        return new RailsimLinkStateChangeEvent(genericEvent.getTime(), linkId, vehicleId, state, dla);
    }
}