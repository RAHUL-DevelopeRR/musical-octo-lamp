package com.luminaplayer.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Tracks AutoCloseable resources and releases them in LIFO order on shutdown.
 * Ensures native vlcj resources are properly freed.
 */
public final class ResourceCleaner {

    private static final Logger log = LoggerFactory.getLogger(ResourceCleaner.class);
    private static final ResourceCleaner INSTANCE = new ResourceCleaner();

    private final Deque<AutoCloseable> resources = new ArrayDeque<>();

    private ResourceCleaner() {
    }

    public static ResourceCleaner instance() {
        return INSTANCE;
    }

    public void register(AutoCloseable resource) {
        resources.push(resource);
        log.debug("Registered resource: {}", resource.getClass().getSimpleName());
    }

    public void releaseAll() {
        log.info("Releasing {} registered resources...", resources.size());
        while (!resources.isEmpty()) {
            AutoCloseable resource = resources.pop();
            try {
                resource.close();
                log.debug("Released: {}", resource.getClass().getSimpleName());
            } catch (Exception e) {
                log.warn("Failed to release resource: {}", resource.getClass().getSimpleName(), e);
            }
        }
        log.info("All resources released.");
    }
}
