package com.claude.web.service;

import java.time.Duration;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.claude.web.dto.NotificationEvent;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

@Service
public class SseEventService {

    private static final Logger logger = LoggerFactory.getLogger(SseEventService.class);

    private final AppServerProcess appServerProcess;

    public SseEventService(AppServerProcess appServerProcess) {
        this.appServerProcess = appServerProcess;
    }

    public Flux<NotificationEvent> createEventStream() {
        Sinks.Many<NotificationEvent> sink = Sinks.many().multicast().directBestEffort();

        Consumer<NotificationEvent> listener = event -> {
            Sinks.EmitResult result = sink.tryEmitNext(event);
            if (result.isFailure()) {
                logger.warn("Failed to emit event: {}", result);
            }
        };

        appServerProcess.addNotificationListener(listener);

        // Send initial ready event
        sink.tryEmitNext(new NotificationEvent("ready", java.util.Map.of("ok", true)));

        // Keep-alive ping every 15 seconds
        Flux<NotificationEvent> keepAlive = Flux.interval(Duration.ofSeconds(15))
            .map(i -> new NotificationEvent("ping", null));

        return Flux.merge(
                sink.asFlux(),
                keepAlive
            )
            .doOnCancel(() -> {
                appServerProcess.removeNotificationListener(listener);
                logger.debug("SSE connection closed");
            })
            .doFinally(signal -> {
                appServerProcess.removeNotificationListener(listener);
                logger.debug("SSE connection closed: {}", signal);
            });
    }
}
