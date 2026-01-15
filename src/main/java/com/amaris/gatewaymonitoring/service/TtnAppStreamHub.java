package com.amaris.gatewaymonitoring.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class TtnAppStreamHub {

    private final WebClient webClient;
    private final String ttnBaseUrl;
    private final String ttnToken;

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(8);
    private final ConcurrentHashMap<String, AppStream> streams = new ConcurrentHashMap<>();

    private final long backoffBaseMs = 1_000;
    private final long backoffMaxMs  = 60_000;

    private final long pollIntervalMs;
    private final int pollLimit;
    private final long initialLookbackSeconds;

    // ✅ Limite de concurrence snapshot TTN (évite 429)
    private final int snapshotConcurrency;

    private final ObjectMapper mapper = new ObjectMapper();

    public TtnAppStreamHub(
            WebClient.Builder webClientBuilder,
            @Value("${lorawan.baseurl}") String ttnBaseUrl,
            @Value("${lorawan.service.token}") String ttnToken,
            @Value("${sensor.multi.poll-interval-ms:3000}") long pollIntervalMs,
            @Value("${sensor.multi.poll-limit:200}") int pollLimit,
            @Value("${sensor.multi.initial-lookback-seconds:90}") long initialLookbackSeconds,
            @Value("${sensor.multi.snapshot-concurrency:5}") int snapshotConcurrency
    ) {
        this.webClient = webClientBuilder.build();
        this.ttnBaseUrl = ttnBaseUrl;
        this.ttnToken = ttnToken;

        this.pollIntervalMs = Math.max(1000, pollIntervalMs);
        this.pollLimit = Math.max(1, Math.min(pollLimit, 2000));
        this.initialLookbackSeconds = Math.max(0, initialLookbackSeconds);
        this.snapshotConcurrency = Math.max(1, Math.min(snapshotConcurrency, 20));
    }

    /**
     * SSE stream:
     *  - event:snapshot => fetch TTN latest per device (limit=1) et renvoie direct
     *  - event:uplink    => ensuite live updates (app-level poll)
     *  - :keepalive      => comment
     */
    public Flux<ServerSentEvent<String>> stream(String appId, String clientId, List<String> deviceIds) {
        AppStream appStream = streams.computeIfAbsent(appId, this::startStream);

        // ✅ SNAPSHOT: appeler TTN device-by-device et renvoyer le dernier
        Flux<ServerSentEvent<String>> snapshot = fetchLatestForDevices(appId, deviceIds)
                .map(line -> ServerSentEvent.builder(line).event("snapshot").build());

        // LIVE: filtrage local sur les deviceIds
        Flux<ServerSentEvent<String>> live = appStream.liveFor(deviceIds)
                .map(line -> ServerSentEvent.builder(line).event("uplink").build())
                .doOnSubscribe(sub -> appStream.subscribers.incrementAndGet())
                .doFinally(sig -> {
                    int n = appStream.subscribers.decrementAndGet();
                    if (n <= 0) stop(appId);
                });

        Flux<ServerSentEvent<String>> keepAlive = Flux.interval(Duration.ofSeconds(60))
                .map(i -> ServerSentEvent.<String>builder().comment("keepalive").build());

        return snapshot.concatWith(live).mergeWith(keepAlive);
    }

    public void stop(String appId) {
        AppStream s = streams.remove(appId);
        if (s == null) return;

        if (s.future != null) s.future.cancel(true);
        s.sink.tryEmitComplete();
    }

    private AppStream startStream(String appId) {
        AppStream s = new AppStream(appId);

        s.afterCursor.set(Instant.now().minusSeconds(initialLookbackSeconds));

        s.future = scheduler.scheduleWithFixedDelay(
                () -> pollOnce(s),
                0,
                pollIntervalMs,
                TimeUnit.MILLISECONDS
        );
        return s;
    }

    /**
     * Poll app-level TTN storage (delta) et push dans sink.
     * (Tu peux garder ton dédup ici si tu veux, je le laisse minimal)
     */
    private void pollOnce(AppStream s) {
        try {
            Instant after = s.afterCursor.get();

            String url = UriComponentsBuilder
                    .fromHttpUrl(ttnBaseUrl)
                    .pathSegment("as", "applications", s.appId, "packages", "storage", "uplink_message")
                    .queryParam("order", "received_at") // ASC
                    .queryParam("after", after.toString())
                    .queryParam("limit", pollLimit)
                    .build()
                    .toUriString();

            String body = webClient.get()
                    .uri(url)
                    .accept(MediaType.APPLICATION_NDJSON)
                    .header("Authorization", "Bearer " + ttnToken)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(15));

            if (body == null || body.isBlank()) return;

            Instant maxSeen = after;

            for (String line : body.split("\\r?\\n")) {
                if (line == null || line.isBlank()) continue;

                Instant ra = extractReceivedAt(line);
                if (ra != null && ra.isAfter(maxSeen)) maxSeen = ra;

                s.sink.tryEmitNext(line);
            }

            s.afterCursor.set(maxSeen.plusNanos(1));
            s.retry.set(0);

        } catch (WebClientResponseException e) {
            if (e.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                int n = s.retry.incrementAndGet();
                sleepQuietly(computeBackoffMs(n));
                return;
            }
            if (e.getStatusCode() == HttpStatus.UNAUTHORIZED || e.getStatusCode() == HttpStatus.FORBIDDEN) {
                stop(s.appId);
                return;
            }
            int n = s.retry.incrementAndGet();
            sleepQuietly(Math.min(10_000, computeBackoffMs(n)));

        } catch (Exception e) {
            int n = s.retry.incrementAndGet();
            sleepQuietly(Math.min(10_000, computeBackoffMs(n)));
        }
    }

    /**
     * ✅ Snapshot TTN direct:
     * pour chaque deviceId => GET device-specific uplink_message?limit=1&order=-received_at
     * Concurrence limitée pour éviter 429.
     */
    private Flux<String> fetchLatestForDevices(String appId, List<String> deviceIds) {
        if (deviceIds == null || deviceIds.isEmpty()) {
            return Flux.empty();
        }

        // dédoublonne au cas où
        Set<String> wanted = new HashSet<>(deviceIds);

        return Flux.fromIterable(wanted)
                .flatMapSequential(
                        devId -> fetchLatestForDevice(appId, devId)
                                .onErrorResume(err -> Mono.empty()),
                        snapshotConcurrency,
                        1
                );
    }

    private Mono<String> fetchLatestForDevice(String appId, String deviceId) {
        String url = UriComponentsBuilder
                .fromHttpUrl(ttnBaseUrl)
                .pathSegment("as", "applications", appId, "devices", deviceId, "packages", "storage", "uplink_message")
                .queryParam("limit", 1)
                .queryParam("order", "-received_at") // DESC
                .build()
                .toUriString();

        return webClient.get()
                .uri(url)
                .accept(MediaType.APPLICATION_NDJSON)
                .header("Authorization", "Bearer " + ttnToken)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(10))
                .flatMap(body -> {
                    if (body == null || body.isBlank()) return Mono.empty();
                    // NDJSON -> première ligne = dernier uplink
                    String first = body.trim().split("\\r?\\n")[0];
                    return (first == null || first.isBlank()) ? Mono.empty() : Mono.just(first);
                });
    }

    private Instant extractReceivedAt(String line) {
        try {
            JsonNode root = mapper.readTree(line);
            JsonNode result = root.path("result");
            String ra = result.path("received_at").asText(null);
            return (ra == null) ? null : Instant.parse(ra);
        } catch (Exception e) {
            return null;
        }
    }

    private long computeBackoffMs(int attempt) {
        long exp = backoffBaseMs * (1L << Math.min(10, Math.max(0, attempt - 1)));
        long capped = Math.min(backoffMaxMs, exp);
        double jitter = 0.5 + ThreadLocalRandom.current().nextDouble() * 0.5;
        return (long) (capped * jitter);
    }

    private void sleepQuietly(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }

    // =====================
    // Inner class AppStream
    // =====================
    private static class AppStream {
        final String appId;

        final Sinks.Many<String> sink = Sinks.many().multicast().onBackpressureBuffer(10_000);
        final AtomicInteger subscribers = new AtomicInteger(0);
        final AtomicInteger retry = new AtomicInteger(0);
        final AtomicReference<Instant> afterCursor = new AtomicReference<>();

        ScheduledFuture<?> future;

        private final ObjectMapper mapper = new ObjectMapper();

        AppStream(String appId) { this.appId = appId; }

        Flux<String> liveFor(List<String> deviceIds) {
            final Set<String> wanted = (deviceIds == null) ? Set.of() : new HashSet<>(deviceIds);

            return sink.asFlux().filter(line -> {
                if (wanted.isEmpty()) return true;
                try {
                    JsonNode root = mapper.readTree(line);
                    JsonNode result = root.path("result");
                    String devId = result.path("end_device_ids").path("device_id").asText(null);
                    return devId != null && wanted.contains(devId);
                } catch (Exception ex) {
                    return false;
                }
            });
        }
    }
}
