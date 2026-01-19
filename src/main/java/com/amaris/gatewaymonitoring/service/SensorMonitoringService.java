package com.amaris.gatewaymonitoring.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

@Service
public class SensorMonitoringService {

    private final WebClient webClient;

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);

    private final Map<String, ScheduledFuture<?>> tasks = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> retryCounters = new ConcurrentHashMap<>();

    private final String ttnBaseUrl;
    private final String ttnToken;
    private final long pollIntervalSec;

    private final long backoffBaseMs = 1_000;
    private final long backoffMaxMs  = 60_000;

    public SensorMonitoringService(
            WebClient.Builder webClientBuilder,
            @Value("${lorawan.baseurl}") String ttnBaseUrl,
            @Value("${lorawan.service.token}") String ttnToken,
            @Value("${sensor.poll-interval-sec:10}") long pollIntervalSec
    ) {
        this.webClient = webClientBuilder.build();
        this.ttnBaseUrl = ttnBaseUrl;
        this.ttnToken = ttnToken;
        this.pollIntervalSec = pollIntervalSec;
    }

    public void startTtnPolling(String appId, String deviceId, String threadId, Consumer<String> callback) {
        if (tasks.containsKey(threadId)) return;

        retryCounters.putIfAbsent(threadId, new AtomicInteger(0));

        Runnable pollingTask = () -> {
            if (!tasks.containsKey(threadId)) return;

            try {
                String url = UriComponentsBuilder
                        .fromHttpUrl(ttnBaseUrl)
                        .pathSegment("as", "applications", appId, "devices", deviceId, "packages", "storage", "uplink_message")
                        .queryParam("limit", 1)
                        .queryParam("order", "-received_at")
                        .build()
                        .toUriString();

                String body = webClient.get()
                        .uri(url)
                        .accept(MediaType.APPLICATION_NDJSON)
                        .header("Authorization", "Bearer " + ttnToken)
                        .retrieve()
                        .bodyToMono(String.class)
                        .block(Duration.ofSeconds(15));

                retryCounters.get(threadId).set(0);

                if (body != null && !body.isBlank()) {
                    for (String line : body.trim().split("\\r?\\n")) {
                        if (!line.isBlank()) callback.accept(line);
                    }
                }

            } catch (WebClientResponseException e) {
                if (e.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                    int n = retryCounters.get(threadId).incrementAndGet();
                    long sleepMs = computeBackoffMs(n);
                    System.err.printf("TTN 429 [%s/%s] thread=%s. Backoff %dms%n", appId, deviceId, threadId, sleepMs);
                    sleepQuietly(sleepMs);
                    return;
                }

                if (e.getStatusCode() == HttpStatus.UNAUTHORIZED || e.getStatusCode() == HttpStatus.FORBIDDEN) {
                    System.err.printf("TTN auth error [%s/%s] thread=%s: %s. Stopping.%n", appId, deviceId, threadId, e.getMessage());
                    stopTtnPolling(threadId);
                    return;
                }

                int n = retryCounters.get(threadId).incrementAndGet();
                long sleepMs = Math.min(10_000, computeBackoffMs(n));
                System.err.printf("TTN HTTP error [%s/%s] thread=%s: %s. Backoff %dms%n", appId, deviceId, threadId, e.getStatusCode(), sleepMs);
                sleepQuietly(sleepMs);

            } catch (Exception e) {
                int n = retryCounters.get(threadId).incrementAndGet();
                long sleepMs = Math.min(10_000, computeBackoffMs(n));
                System.err.printf("Polling error [%s/%s] thread=%s: %s. Backoff %dms%n", appId, deviceId, threadId, e.getMessage(), sleepMs);
                sleepQuietly(sleepMs);
            }
        };

        ScheduledFuture<?> future = scheduler.scheduleWithFixedDelay(
                pollingTask,
                0,
                pollIntervalSec,
                TimeUnit.SECONDS
        );

        tasks.put(threadId, future);
    }

    public void stopTtnPolling(String threadId) {
        ScheduledFuture<?> f = tasks.remove(threadId);
        retryCounters.remove(threadId);

        if (f != null) {
            f.cancel(true);
            System.out.println("Stopping monitoring for thread: " + threadId);
        }
    }

    public Flux<String> probeGatewayDevicesFlux(String appId, Optional<Instant> after, int limit) {
        UriComponentsBuilder urlBuilder = UriComponentsBuilder
                .fromHttpUrl(ttnBaseUrl)
                .pathSegment("as", "applications", appId, "packages", "storage", "uplink_message")
                .queryParam("order", "-received_at")
                .queryParam("limit", Math.max(1, Math.min(limit, 2000)));

        after.ifPresent(instant -> urlBuilder.queryParam("after", instant.toString()));

        return webClient.get()
                .uri(urlBuilder.build().toUriString())
                .accept(MediaType.APPLICATION_NDJSON)
                .header("Authorization", "Bearer " + ttnToken)
                .retrieve()
                .bodyToFlux(String.class)
                .filter(line -> line != null && !line.isBlank());
    }

    private long computeBackoffMs(int attempt) {
        long exp = backoffBaseMs * (1L << Math.min(10, Math.max(0, attempt - 1)));
        long capped = Math.min(backoffMaxMs, exp);

        double jitter = 0.5 + ThreadLocalRandom.current().nextDouble() * 0.5;
        return (long) (capped * jitter);
    }

    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
