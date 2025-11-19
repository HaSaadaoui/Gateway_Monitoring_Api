package com.amaris.gatewaymonitoring.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@Service
public class SensorMonitoringService {

    private final WebClient webClient;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final Map<String, ScheduledFuture<?>> tasks = new ConcurrentHashMap<>();

    private final String ttnBaseUrl;       // ex: https://eu1.cloud.thethings.network/api/v3
    private final String ttnToken;
    private final long pollIntervalSec;

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

    /**
     * Démarre le polling pour une application et un device donnés.
     *
     * @param appId    ID de l'application TTN (ex: lorawan-network-mantu)
     * @param deviceId ID du device TTN (ex: occup-vs70-03-04)
     * @param threadId ID unique pour cette tâche de polling (permet l'arrêt)
     * @param callback Callback appelé pour chaque message (ligne NDJSON)
     */
    public void startTtnPolling(String appId, String deviceId, String threadId, Consumer<String> callback) {
        if (tasks.containsKey(threadId)) return;

        Runnable pollingTask = () -> {
            try {
                String url = UriComponentsBuilder
                        .fromHttpUrl(ttnBaseUrl)
                        .pathSegment("as", "applications", appId, "devices", deviceId, "packages", "storage", "uplink_message")
                        .queryParam("limit", 1)
                        .queryParam("order", "-received_at")  // Trier par date décroissante (plus récent en premier)
                        .build()
                        .toUriString();

                String body = webClient.get()
                        .uri(url)
                        .accept(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + ttnToken)
                        .retrieve()
                        .bodyToMono(String.class)
                        .block();

                if (body != null && !body.isBlank()) {
                    for (String line : body.trim().split("\\r?\\n")) {
                        if (!line.isBlank()) callback.accept(line);
                    }
                }
            } catch (Exception e) {
                System.err.println("Polling error [" + appId + "/" + deviceId + "]: " + e.getMessage());
            }
        };

        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(pollingTask, 0, pollIntervalSec, TimeUnit.SECONDS);
        tasks.put(threadId, future);
    }

    /** Arrête le polling pour une tâche donnée. */
    public void stopTtnPolling(String threadId) {
        ScheduledFuture<?> f = tasks.remove(threadId);
        if (f != null) f.cancel(true);
    }
}
