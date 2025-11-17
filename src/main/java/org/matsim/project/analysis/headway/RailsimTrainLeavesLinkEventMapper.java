package org.matsim.project.analysis.headway;

import ch.sbb.matsim.contrib.railsim.events.RailsimTrainLeavesLinkEvent;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.GenericEvent;
import org.matsim.api.core.v01.network.Link;
import org.matsim.core.events.MatsimEventsReader;
import org.matsim.vehicles.Vehicle;

import java.util.Map;

public class RailsimTrainLeavesLinkEventMapper implements MatsimEventsReader.CustomEventMapper {

    private static <T> Id<T> asId(String value, Class<T> idClass) {
        if (value == null) {
            return null;
        }
        return Id.create(value, idClass);
    }

    @Override
    public RailsimTrainLeavesLinkEvent apply(GenericEvent genericEvent) {
        Map<String, String> attributes = genericEvent.getAttributes();
        return new RailsimTrainLeavesLinkEvent(genericEvent.getTime(),
                asId(attributes.get(RailsimTrainLeavesLinkEvent.ATTRIBUTE_VEHICLE), Vehicle.class),
                asId(attributes.get(RailsimTrainLeavesLinkEvent.ATTRIBUTE_LINK), Link.class));
    }
}