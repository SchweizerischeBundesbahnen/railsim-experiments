package org.matsim.project.scenario.plan;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

public class OperationalPlanReader {

    private final ObjectMapper mapper = new ObjectMapper();

    public OperationalPlan read(InputStream inputStream) throws IOException {
        if (inputStream == null) {
            throw new IOException("Input stream cannot be null.");
        }
        return mapper.readValue(inputStream, OperationalPlan.class);
    }

    public OperationalPlan read(Path filePath) throws IOException {
        if (filePath == null) {
            throw new IOException("Path cannot be null.");
        }

        return mapper.readValue(filePath.toFile(), OperationalPlan.class);
    }
}