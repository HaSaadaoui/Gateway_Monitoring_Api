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
import reactor.util.retry.Retry;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
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

    // ✅ verrou TTN par appId (évite snapshot+poll en parallèle => 429)
    private final ConcurrentHashMap<String, Semaphore> appLocks = new ConcurrentHashMap<>();

    private final long backoffBaseMs = 1_000;
    private final long backoffMaxMs  = 60_000;

    private final long pollIntervalMs;
    private final int pollLimit;                 // live app-level limit
    private final int snapshotAppLimit;          // snapshot app-level window size
    private final long initialLookbackSeconds;

    private final ObjectMapper mapper = new ObjectMapper();

    public TtnAppStreamHub(
            WebClient.Builder webClientBuilder,
            @Value("${lorawan.baseurl}") String ttnBaseUrl,
            @Value("${lorawan.service.token}") String ttnToken,
            @Value("${sensor.multi.poll-interval-ms:12000}") long pollIntervalMs,
            @Value("${sensor.multi.poll-limit:150}") int pollLimit,
            @Value("${sensor.multi.snapshot-app-limit:1500}") int snapshotAppLimit,
            @Value("${sensor.multi.initial-lookback-seconds:600}") long initialLookbackSeconds
    ) {
        this.webClient = webClientBuilder.build();
        this.ttnBaseUrl = ttnBaseUrl;
        this.ttnToken = ttnToken;

        this.pollIntervalMs = Math.max(1000, pollIntervalMs);
        this.pollLimit = Math.max(1, Math.min(pollLimit, 2000));
        this.snapshotAppLimit = Math.max(50, Math.min(snapshotAppLimit, 2000));
        this.initialLookbackSeconds = Math.max(0, initialLookbackSeconds);
    }

    private Semaphore lockFor(String appId) {
        return appLocks.computeIfAbsent(appId, k -> new Semaphore(1));
    }

    /**
     * SSE stream:
     *  - snapshot: 1 event par device demandé (latest même si vieux) OU tous si deviceIds vide
     *  - live: uniquement si nouveau
     *  - keepalive
     */
    public Flux<ServerSentEvent<String>> stream(String appId, List<String> deviceIds) {
        AppStream appStream = streams.computeIfAbsent(appId, this::startStream);

        Flux<ServerSentEvent<String>> snapshot = fetchSnapshot(appId, deviceIds)
                .doOnNext(appStream::seedLastSeen)
                .map(line -> ServerSentEvent.builder(line).event("snapshot").build());

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
     * Live poll (app-level delta). sérialisé avec snapshot via lock.
     */
    private void pollOnce(AppStream s) {
        Semaphore lock = lockFor(s.appId);
        boolean acquired = false;

        try {
            acquired = lock.tryAcquire(5, TimeUnit.SECONDS);
            if (!acquired) return;

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
                    .block(Duration.ofSeconds(20));

            if (body == null || body.isBlank()) return;

            Instant maxSeen = after;

            for (String line : body.split("\\r?\\n")) {
                if (line == null || line.isBlank()) continue;

                Instant ra = extractReceivedAt(line);
                if (ra != null && ra.isAfter(maxSeen)) maxSeen = ra;

                s.sink.tryEmitNext(line);
            }

            // avancer le curseur (nanos +1 pour éviter de relire le même event)
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
        } finally {
            if (acquired) lock.release();
        }
    }

    // =========================================================
    // SNAPSHOT (1 SEUL CALL APP-LEVEL, PUIS FILTRAGE)
    // =========================================================
    private Flux<String> fetchSnapshot(String appId, List<String> deviceIds) {
        final Set<String> wanted = (deviceIds == null) ? Set.of() : new LinkedHashSet<>(deviceIds);
        final boolean filterWanted = !wanted.isEmpty();

        Semaphore lock = lockFor(appId);

        Mono<Boolean> acquire = Mono.fromCallable(() -> {
            lock.acquire();
            return true;
        });

        Mono<Void> release = Mono.fromRunnable(lock::release);

        return Flux.usingWhen(
                acquire,
                ok -> fetchSnapshotAppLevelFiltered(appId, wanted, filterWanted),
                ok -> release,
                (ok, err) -> release,
                ok -> release
        );
    }

    private Flux<String> fetchSnapshotAppLevelFiltered(String appId, Set<String> wanted, boolean filterWanted) {
        String url = UriComponentsBuilder
                .fromHttpUrl(ttnBaseUrl)
                .pathSegment("as", "applications", appId, "packages", "storage", "uplink_message")
                .queryParam("order", "-received_at")
                .queryParam("limit", snapshotAppLimit)
                .build()
                .toUriString();

        return webClient.get()
                .uri(url)
                .accept(MediaType.APPLICATION_NDJSON)
                .header("Authorization", "Bearer " + ttnToken)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(25))
                .flatMapMany(body -> {
                    if (body == null || body.isBlank()) {
                        if (filterWanted) {
                            return Flux.fromIterable(wanted)
                                    .map(devId -> noDataJson(devId, "NO_UPLINK_IN_WINDOW"));
                        }
                        return Flux.empty();
                    }

                    // DESC => première occurrence = latest
                    LinkedHashMap<String, String> latestByDevice = new LinkedHashMap<>();

                    for (String line : body.split("\\r?\\n")) {
                        if (line == null || line.isBlank()) continue;

                        String devId = extractDeviceId(line);
                        if (devId == null) continue;

                        if (filterWanted && !wanted.contains(devId)) continue;

                        latestByDevice.putIfAbsent(devId, line);

                        if (filterWanted && latestByDevice.size() >= wanted.size()) break;
                    }

                    if (!filterWanted) {
                        return Flux.fromIterable(latestByDevice.values());
                    }

                    List<String> out = new ArrayList<>(wanted.size());
                    for (String devId : wanted) {
                        String line = latestByDevice.get(devId);
                        out.add(line != null ? line : noDataJson(devId, "NO_UPLINK_IN_WINDOW"));
                    }
                    return Flux.fromIterable(out);
                })
                .retryWhen(Retry.backoff(5, Duration.ofSeconds(2))
                        .maxBackoff(Duration.ofSeconds(30))
                        .filter(ex -> ex instanceof WebClientResponseException w
                                && w.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS)
                )
                .onErrorResume(err -> {
                    if (filterWanted) {
                        return Flux.fromIterable(wanted)
                                .map(devId -> errorJson(devId, err));
                    }
                    return Flux.empty();
                });
    }

    // =========================================================
    // JSON status helpers
    // =========================================================
    private String noDataJson(String deviceId, String reason) {
        try {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("device_id", deviceId);
            m.put("status", "NO_DATA");
            m.put("reason", reason);
            return mapper.writeValueAsString(m);
        } catch (Exception e) {
            return "{\"device_id\":\"" + deviceId + "\",\"status\":\"NO_DATA\",\"reason\":\"" + reason + "\"}";
        }
    }

    private String errorJson(String deviceId, Throwable err) {
        String msg = rootMessage(err);
        try {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("device_id", deviceId);
            m.put("status", "ERROR");
            m.put("message", msg);
            return mapper.writeValueAsString(m);
        } catch (Exception e) {
            return "{\"device_id\":\"" + deviceId + "\",\"status\":\"ERROR\",\"message\":\"" + msg.replace("\"", "'") + "\"}";
        }
    }

    private String rootMessage(Throwable err) {
        if (err == null) return "unknown";
        Throwable cur = err;
        while (cur.getCause() != null) cur = cur.getCause();
        String msg = cur.getMessage();
        if (msg == null || msg.isBlank()) msg = err.getMessage();
        return msg != null ? msg : "unknown";
    }

    // =========================================================
    // JSON extract helpers
    // =========================================================
    private String extractDeviceId(String line) {
        try {
            JsonNode root = mapper.readTree(line);
            JsonNode result = root.path("result");
            return result.path("end_device_ids").path("device_id").asText(null);
        } catch (Exception e) {
            return null;
        }
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

        final ConcurrentHashMap<String, Integer> lastFCntByDevice = new ConcurrentHashMap<>();
        final ConcurrentHashMap<String, Instant> lastReceivedAtByDevice = new ConcurrentHashMap<>();

        final Sinks.Many<String> sink = Sinks.many().multicast().onBackpressureBuffer(10_000);
        final AtomicInteger subscribers = new AtomicInteger(0);
        final AtomicInteger retry = new AtomicInteger(0);
        final AtomicReference<Instant> afterCursor = new AtomicReference<>();

        ScheduledFuture<?> future;

        private final ObjectMapper mapper = new ObjectMapper();

        AppStream(String appId) { this.appId = appId; }

        void seedLastSeen(String line) {
            try {
                JsonNode root = mapper.readTree(line);
                JsonNode result = root.path("result");
                String devId = result.path("end_device_ids").path("device_id").asText(null);
                if (devId == null) return;

                JsonNode f = result.path("uplink_message").path("f_cnt");
                if (f.isNumber()) lastFCntByDevice.put(devId, f.asInt());

                String ra = result.path("received_at").asText(null);
                if (ra != null) lastReceivedAtByDevice.put(devId, Instant.parse(ra));
            } catch (Exception ignored) {}
        }

        Flux<String> liveFor(List<String> deviceIds) {
            final Set<String> wanted = (deviceIds == null || deviceIds.isEmpty())
                    ? Set.of()
                    : new HashSet<>(deviceIds);

            return sink.asFlux()
                    .filter(line -> {
                        try {
                            JsonNode root = mapper.readTree(line);
                            JsonNode result = root.path("result");
                            String devId = result.path("end_device_ids").path("device_id").asText(null);
                            if (devId == null) return false;

                            if (!wanted.isEmpty() && !wanted.contains(devId)) return false;

                            JsonNode f = result.path("uplink_message").path("f_cnt");
                            if (f.isNumber()) {
                                int fcnt = f.asInt();
                                Integer prev = lastFCntByDevice.put(devId, fcnt);
                                return prev == null || fcnt > prev;
                            }

                            String ra = result.path("received_at").asText(null);
                            if (ra != null) {
                                Instant ts = Instant.parse(ra);
                                Instant prevTs = lastReceivedAtByDevice.put(devId, ts);
                                return prevTs == null || ts.isAfter(prevTs);
                            }

                            return true;
                        } catch (Exception ex) {
                            return false;
                        }
                    });
        }
    }
}
