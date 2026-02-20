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
import reactor.core.scheduler.Schedulers;

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

    private final ConcurrentHashMap<String, Semaphore> appLocks = new ConcurrentHashMap<>();

    private final long backoffBaseMs = 1_000;
    private final long backoffMaxMs  = 60_000;

    private final long pollIntervalMs;
    private final int pollLimit;
    private final int snapshotAppLimit;
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
                .map(line -> ServerSentEvent.builder(line).event("uplink").build());

        Flux<ServerSentEvent<String>> keepAlive = Flux.interval(Duration.ofSeconds(60))
                .map(i -> ServerSentEvent.<String>builder().comment("keepalive").build());

        return Flux.merge(snapshot, live, keepAlive)
                .doOnSubscribe(sub -> appStream.subscribers.incrementAndGet())
                .doFinally(sig -> {
                    int n = appStream.subscribers.decrementAndGet();
                    if (n <= 0) stop(appId);
                });
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

        if (!lock.tryAcquire()) return;

        final Instant after = s.afterCursor.get();
        final AtomicReference<Instant> maxSeen = new AtomicReference<>(after);

        String url = UriComponentsBuilder
                .fromHttpUrl(ttnBaseUrl)
                .pathSegment("as", "applications", s.appId, "packages", "storage", "uplink_message")
                .queryParam("order", "received_at") // ASC
                .queryParam("after", after.toString())
                .queryParam("limit", pollLimit)
                .build()
                .toUriString();

        webClient.get()
                .uri(url)
                .accept(MediaType.APPLICATION_NDJSON)
                .header("Authorization", "Bearer " + ttnToken)
                .retrieve()
                .bodyToFlux(String.class) // NDJSON ligne par ligne
                .timeout(Duration.ofSeconds(20))
                .doOnNext(line -> {
                    if (line == null || line.isBlank()) return;

                    Instant ra = extractReceivedAt(line);
                    if (ra != null && ra.isAfter(maxSeen.get())) {
                        maxSeen.set(ra);
                    }

                    s.sink.tryEmitNext(line);
                })
                .doOnComplete(() -> {
                    Instant ms = maxSeen.get();
                    if (ms.isAfter(after)) {
                        s.afterCursor.set(ms.plusNanos(1)); // évite doublon
                    }
                    s.retry.set(0);
                })
                .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofMillis(backoffBaseMs))
                        .maxBackoff(Duration.ofMillis(backoffMaxMs))
                        .jitter(0.5)
                        .filter(ex -> ex instanceof WebClientResponseException w
                                && w.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS)
                        .doAfterRetry(rs -> s.retry.incrementAndGet())
                )
                .doOnError(err -> {
                    if (err instanceof WebClientResponseException wex) {
                        if (wex.getStatusCode() == HttpStatus.UNAUTHORIZED
                                || wex.getStatusCode() == HttpStatus.FORBIDDEN) {
                            stop(s.appId);
                        }
                    }
                })
                .doFinally(sig -> lock.release())
                .subscribe();
    }

    private Flux<String> fetchSnapshot(String appId, List<String> deviceIds) {
        final Set<String> wanted = (deviceIds == null) ? Set.of() : new LinkedHashSet<>(deviceIds);
        final boolean filterWanted = !wanted.isEmpty();

        Semaphore lock = lockFor(appId);

        Mono<Boolean> acquire = Mono.fromCallable(() -> lock.tryAcquire(30, TimeUnit.SECONDS))
                .subscribeOn(Schedulers.boundedElastic());

        return Flux.usingWhen(
                acquire,
                ok -> ok
                        ? fetchSnapshotAppLevelFiltered(appId, wanted, filterWanted)
                        : Flux.empty(),
                ok -> {
                    if (ok) lock.release();
                    return Mono.empty();
                },
                (ok, err) -> {
                    if (ok) lock.release();
                    return Mono.empty();
                },
                ok -> {
                    if (ok) lock.release();
                    return Mono.empty();
                }
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

        LinkedHashMap<String, String> latestByDevice = new LinkedHashMap<>();

        return webClient.get()
                .uri(url)
                .accept(MediaType.APPLICATION_NDJSON)
                .header("Authorization", "Bearer " + ttnToken)
                .retrieve()
                .bodyToFlux(String.class)
                .timeout(Duration.ofSeconds(25))
                .handle((String line, reactor.core.publisher.SynchronousSink<String> sink) -> {
                    if (line == null || line.isBlank()) return;

                    String devId = extractDeviceId(line);
                    if (devId == null) return;

                    if (filterWanted && !wanted.contains(devId)) return;

                    if (latestByDevice.putIfAbsent(devId, line) == null) {
                        sink.next(line);
                    }

                    if (filterWanted && latestByDevice.size() >= wanted.size()) {
                        sink.complete();
                    }
                })
                .concatWith(Flux.defer(() -> {
                    if (!filterWanted) return Flux.<String>empty();

                    List<String> missing = wanted.stream()
                            .filter(id -> !latestByDevice.containsKey(id))
                            .map(id -> noDataJson(id, "NO_UPLINK_IN_WINDOW"))
                            .toList();

                    return Flux.fromIterable(missing);
                }))
                .retryWhen(Retry.backoff(5, Duration.ofSeconds(2))
                        .maxBackoff(Duration.ofSeconds(30))
                        .filter(ex -> ex instanceof WebClientResponseException w
                                && w.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS)
                )
                .onErrorResume(err -> {
                    if (filterWanted) {
                        return Flux.fromIterable(wanted)
                                .map(devId -> errorJson(devId, err))
                                .cast(String.class);
                    }
                    return Flux.<String>empty();
                });
    }

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

    private static class AppStream {
        final String appId;

        final ConcurrentHashMap<String, Integer> lastFCntByDevice = new ConcurrentHashMap<>();
        final ConcurrentHashMap<String, Instant> lastReceivedAtByDevice = new ConcurrentHashMap<>();

        final Sinks.Many<String> sink = Sinks.many().multicast().directBestEffort();
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
