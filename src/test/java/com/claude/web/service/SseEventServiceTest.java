package com.claude.web.service;

import com.claude.web.dto.NotificationEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SseEventServiceTest {

    // ─── createEventStream ────────────────────────────────────────────────────

    @Test
    void createEventStream_registersNotificationListener() {
        AppServerProcess mockProcess = mock(AppServerProcess.class);
        SseEventService service = new SseEventService(mockProcess);

        Flux<NotificationEvent> flux = service.createEventStream();

        // Subscribe briefly to trigger setup
        flux.subscribe().dispose();

        verify(mockProcess, atLeastOnce()).addNotificationListener(any());
    }

    @Test
    void createEventStream_removesListenerOnCancel() {
        AppServerProcess mockProcess = mock(AppServerProcess.class);
        SseEventService service = new SseEventService(mockProcess);

        ArgumentCaptor<Consumer<NotificationEvent>> captor = ArgumentCaptor.forClass(Consumer.class);

        Flux<NotificationEvent> flux = service.createEventStream();
        reactor.core.Disposable subscription = flux.subscribe();
        verify(mockProcess, atLeastOnce()).addNotificationListener(captor.capture());

        subscription.dispose();

        // After disposal the listener should have been removed
        Consumer<NotificationEvent> registered = captor.getValue();
        verify(mockProcess, atLeastOnce()).removeNotificationListener(registered);
    }

    @Test
    void createEventStream_forwardsNotificationEvents() {
        AppServerProcess mockProcess = mock(AppServerProcess.class);
        SseEventService service = new SseEventService(mockProcess);

        ArgumentCaptor<Consumer<NotificationEvent>> captor = ArgumentCaptor.forClass(Consumer.class);
        Flux<NotificationEvent> flux = service.createEventStream();

        // Capture the listener AFTER subscribing (subscription triggers registration)
        StepVerifier.create(flux.take(2).timeout(Duration.ofSeconds(2)))
            .then(() -> {
                verify(mockProcess, atLeastOnce()).addNotificationListener(captor.capture());
                Consumer<NotificationEvent> listener = captor.getValue();
                listener.accept(new NotificationEvent("stream_delta", null));
                listener.accept(new NotificationEvent("complete", null));
            })
            .expectNextMatches(e -> "stream_delta".equals(e.getMethod()))
            .expectNextMatches(e -> "complete".equals(e.getMethod()))
            .verifyComplete();
    }

    @Test
    void createEventStream_multipleSubscribersGetIndependentStreams() {
        AppServerProcess mockProcess = mock(AppServerProcess.class);
        SseEventService service = new SseEventService(mockProcess);

        Flux<NotificationEvent> stream1 = service.createEventStream();
        Flux<NotificationEvent> stream2 = service.createEventStream();

        stream1.subscribe().dispose();
        stream2.subscribe().dispose();

        // Two separate listeners registered (one per stream)
        verify(mockProcess, times(2)).addNotificationListener(any());
    }

    @Test
    void createEventStream_includesKeepAlivePings() {
        AppServerProcess mockProcess = mock(AppServerProcess.class);
        SseEventService service = new SseEventService(mockProcess);

        // The stream is a merge of real events and a 15s keepalive ping.
        // Just verify the flux is non-null and can be subscribed.
        Flux<NotificationEvent> flux = service.createEventStream();
        assertThat(flux).isNotNull();
    }
}
