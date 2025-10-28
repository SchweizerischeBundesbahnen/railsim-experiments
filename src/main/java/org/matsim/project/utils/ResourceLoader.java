package org.matsim.project.utils;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class ResourceLoader {

    public static Path getPath(String resourcePath) throws IOException {
        URL resourceUrl = ResourceLoader.class.getResource(resourcePath);
        if (resourceUrl == null) {
            throw new IOException("Resource not found in classpath: " + resourcePath);
        }

        try {
            URI uri = resourceUrl.toURI();

            // if the resource is inside a JAR; create a temporary file and copy the content
            if ("jar".equals(uri.getScheme())) {
                return extractToTempFile(resourcePath, resourceUrl);
            }

            // regular file (e.g., running in an IDE); return the path
            return Paths.get(uri);

        } catch (URISyntaxException e) {
            throw new IOException("Failed to convert resource URL to URI: " + resourceUrl, e);
        }
    }

    private static Path extractToTempFile(String resourcePath, URL resourceUrl) throws IOException {
        String fileName = Paths.get(resourcePath).getFileName().toString();
        Path tempFile = Files.createTempFile("resource-", "-" + fileName);
        tempFile.toFile().deleteOnExit();

        try (InputStream in = resourceUrl.openStream()) {
            Files.copy(in, tempFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }

        return tempFile;
    }
}