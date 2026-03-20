package com.amaris.gatewaymonitoring.service;

import com.amaris.gatewaymonitoring.config.LorawanProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class TtnAppStreamHub {

    private static final Logger log = LoggerFactory.getLogger(TtnAppStreamHub.class);

    private final WebClient webClient;
    private final String ttnBaseUrl;
    private final int snapshotAppLimit;
    private final ObjectMapper mapper = new ObjectMapper();

    private final LorawanProperties lorawanProperties;
    private final String mqttHost;
    private final int mqttPort;

    // Un MqttClient par appId
    private final ConcurrentHashMap<String, MqttClient> mqttClients = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AppStream> streams = new ConcurrentHashMap<>();

    public TtnAppStreamHub(
            WebClient.Builder webClientBuilder,
            @Value("${lorawan.baseurl}") String ttnBaseUrl,
            @Value("${lorawan.mqtt.host:eu1.cloud.thethings.network}") String mqttHost,
            @Value("${lorawan.mqtt.port:1883}") int mqttPort,
            @Value("${sensor.multi.snapshot-app-limit:1500}") int snapshotAppLimit,
            LorawanProperties lorawanProperties
    ) {
        this.webClient = webClientBuilder.build();
        this.ttnBaseUrl = ttnBaseUrl;
        this.mqttHost = mqttHost;
        this.mqttPort = mqttPort;
        this.snapshotAppLimit = Math.max(50, Math.min(snapshotAppLimit, 2000));
        this.lorawanProperties = lorawanProperties;
    }

    @PostConstruct
    public void initAllApps() {
        List<String> apps = lorawanProperties.getApps();
        if (apps.isEmpty()) {
            log.warn("⚠️  Aucune app configurée dans lorawan.apps");
            return;
        }
        for (String appId : apps) {
            String token = lorawanProperties.getToken().get(appId);
            if (token == null || token.isBlank()) {
                log.warn("⚠️  Pas de token configuré pour appId={} → ignoré", appId);
                continue;
            }
            connectMqttForApp(appId, token);
        }
    }

    private void connectMqttForApp(String appId, String token) {
        log.info("🔌 Connexion MQTT → {}:{} | appId={}", mqttHost, mqttPort, appId);
        try {
            MqttClient client = new MqttClient(
                    "tcp://" + mqttHost + ":" + mqttPort,
                    "hub-" + appId + "-" + UUID.randomUUID(),
                    new MemoryPersistence()
            );

            MqttConnectOptions opts = new MqttConnectOptions();
            opts.setUserName(appId + "@ttn");
            opts.setPassword(token.toCharArray());
            opts.setAutomaticReconnect(true);
            opts.setCleanSession(false);
            opts.setKeepAliveInterval(60);
            client.connect(opts);

            // Topic spécifique à cette app uniquement
            String topic = "v3/" + appId + "@ttn/devices/+/up";
            client.subscribe(topic, (t, message) -> {
                String payload = new String(message.getPayload());
                log.debug("📡 MQTT reçu → appId={} | topic={}", appId, t);

                AppStream s = streams.get(appId);
                if (s != null) {
                    s.sink.tryEmitNext(wrapAsResult(payload));
                } else {
                    log.debug("⚠️  Aucun stream actif pour appId={}", appId);
                }
            });

            mqttClients.put(appId, client);
            log.info("✅ MQTT connecté → appId={} | topic={}", appId, topic);

        } catch (Exception e) {
            log.error("❌ Échec connexion MQTT pour appId={} : {}", appId, e.getMessage());
        }
    }

    public boolean isMqttConnected(String appId) {
        MqttClient c = mqttClients.get(appId);
        return c != null && c.isConnected();
    }

    @PreDestroy
    public void destroy() {
        log.info("🛑 Fermeture de tous les clients MQTT...");
        mqttClients.forEach((appId, client) -> {
            try {
                if (client.isConnected()) client.disconnect();
                log.info("✅ MQTT déconnecté → appId={}", appId);
            } catch (Exception e) {
                log.warn("⚠️  Erreur déconnexion MQTT pour appId={} : {}", appId, e.getMessage());
            }
        });
    }

    public Flux<ServerSentEvent<String>> stream(String appId, List<String> deviceIds) {
        AppStream appStream = streams.computeIfAbsent(appId, id -> {
            log.info("🆕 Nouveau AppStream créé pour appId={}", id);
            return new AppStream(id);
        });

        Flux<ServerSentEvent<String>> snapshot = fetchSnapshot(appId, deviceIds)
                .doOnNext(appStream::seedLastSeen)
                .doOnNext(line -> log.debug("📸 Snapshot envoyé → appId={} device={}", appId, extractDeviceIdSafe(line)))
                .map(line -> ServerSentEvent.builder(line).event("snapshot").build());

        Flux<ServerSentEvent<String>> live = appStream.liveFor(deviceIds)
                .doOnNext(line -> log.debug("🔴 Live uplink → appId={} device={}", appId, extractDeviceIdSafe(line)))
                .map(line -> ServerSentEvent.builder(line).event("uplink").build());

        Flux<ServerSentEvent<String>> keepAlive = Flux.interval(Duration.ofSeconds(60))
                .map(i -> ServerSentEvent.<String>builder().comment("keepalive").build());

        return Flux.merge(snapshot, live, keepAlive)
                .doOnSubscribe(sub -> {
                    int n = appStream.subscribers.incrementAndGet();
                    log.info("👤 Client SSE connecté → appId={} | abonnés={}", appId, n);
                })
                .doFinally(sig -> {
                    int n = appStream.subscribers.decrementAndGet();
                    log.info("👤 Client SSE déconnecté → appId={} | restants={}", appId, n);
                    if (n <= 0) {
                        streams.remove(appId);
                        log.info("🗑️  AppStream supprimé → appId={}", appId);
                    }
                });
    }

    public void stop(String appId) {
        AppStream s = streams.remove(appId);
        if (s == null) {
            log.warn("⚠️  stop() appelé mais aucun stream actif pour appId={}", appId);
            return;
        }
        s.sink.tryEmitComplete();
        log.info("🛑 Stream stoppé → appId={}", appId);
    }

    private String wrapAsResult(String payload) {
        try {
            JsonNode node = mapper.readTree(payload);
            return mapper.writeValueAsString(Map.of("result", node));
        } catch (Exception e) {
            log.warn("⚠️  Erreur wrapAsResult : {}", e.getMessage());
            return payload;
        }
    }

    private Flux<String> fetchSnapshot(String appId, List<String> deviceIds) {
        final Set<String> wanted = (deviceIds == null) ? Set.of() : new LinkedHashSet<>(deviceIds);
        final boolean filterWanted = !wanted.isEmpty();
        log.info("📸 Snapshot → appId={} | devices={}", appId, filterWanted ? wanted : "ALL");
        return fetchSnapshotAppLevelFiltered(appId, wanted, filterWanted);
    }

    private Flux<String> fetchSnapshotAppLevelFiltered(String appId, Set<String> wanted, boolean filterWanted) {
        // Token HTTP = token de l'app (utilise le premier token dispo ou celui de l'app)
        String token = lorawanProperties.getToken().getOrDefault(appId,
                lorawanProperties.getToken().values().stream().findFirst().orElse(""));

        String url = UriComponentsBuilder
                .fromHttpUrl(ttnBaseUrl)
                .pathSegment("as", "applications", appId, "packages", "storage", "uplink_message")
                .queryParam("order", "-received_at")
                .queryParam("limit", snapshotAppLimit)
                .build()
                .toUriString();

        log.debug("🌐 Snapshot HTTP → GET {}", url);
        LinkedHashMap<String, String> latestByDevice = new LinkedHashMap<>();

        return webClient.get()
                .uri(url)
                .accept(MediaType.APPLICATION_NDJSON)
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .bodyToFlux(String.class)
                .timeout(Duration.ofSeconds(25))
                .handle((String line, reactor.core.publisher.SynchronousSink<String> sink) -> {
                    if (line == null || line.isBlank()) return;
                    String devId = extractDeviceId(line);
                    if (devId == null) return;
                    if (filterWanted && !wanted.contains(devId)) return;
                    if (latestByDevice.putIfAbsent(devId, line) == null) sink.next(line);
                    if (filterWanted && latestByDevice.size() >= wanted.size()) sink.complete();
                })
                .doOnComplete(() -> log.info("✅ Snapshot terminé → appId={} | {} devices", appId, latestByDevice.size()))
                .concatWith(Flux.defer(() -> {
                    if (!filterWanted) return Flux.<String>empty();
                    List<String> missing = wanted.stream()
                            .filter(id -> !latestByDevice.containsKey(id))
                            .map(id -> noDataJson(id, "NO_UPLINK_IN_WINDOW"))
                            .toList();
                    if (!missing.isEmpty())
                        log.warn("⚠️  {} device(s) sans données → appId={}", missing.size(), appId);
                    return Flux.fromIterable(missing);
                }))
                .retryWhen(Retry.backoff(5, Duration.ofSeconds(2))
                        .maxBackoff(Duration.ofSeconds(30))
                        .filter(ex -> ex instanceof WebClientResponseException w
                                && w.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS)
                        .doAfterRetry(rs -> log.warn("🔁 Retry snapshot #{} → appId={}", rs.totalRetries(), appId))
                )
                .onErrorResume(err -> {
                    log.error("❌ Erreur snapshot → appId={} | {}", appId, err.getMessage());
                    if (filterWanted)
                        return Flux.fromIterable(wanted).map(d -> errorJson(d, err)).cast(String.class);
                    return Flux.<String>empty();
                });
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private String extractDeviceIdSafe(String line) {
        try {
            return mapper.readTree(line).path("result")
                    .path("end_device_ids").path("device_id").asText("unknown");
        } catch (Exception e) { return "unknown"; }
    }

    private String extractDeviceId(String line) {
        try {
            return mapper.readTree(line).path("result")
                    .path("end_device_ids").path("device_id").asText(null);
        } catch (Exception e) { return null; }
    }

    private String noDataJson(String deviceId, String reason) {
        try {
            return mapper.writeValueAsString(Map.of("device_id", deviceId, "status", "NO_DATA", "reason", reason));
        } catch (Exception e) {
            return "{\"device_id\":\"" + deviceId + "\",\"status\":\"NO_DATA\"}";
        }
    }

    private String errorJson(String deviceId, Throwable err) {
        try {
            return mapper.writeValueAsString(Map.of("device_id", deviceId, "status", "ERROR", "message", rootMessage(err)));
        } catch (Exception e) {
            return "{\"device_id\":\"" + deviceId + "\",\"status\":\"ERROR\"}";
        }
    }

    private String rootMessage(Throwable err) {
        if (err == null) return "unknown";
        Throwable cur = err;
        while (cur.getCause() != null) cur = cur.getCause();
        String msg = cur.getMessage();
        return (msg != null && !msg.isBlank()) ? msg : "unknown";
    }

    // ─── AppStream ────────────────────────────────────────────────────────────

    private static class AppStream {
        final String appId;
        final ConcurrentHashMap<String, Integer> lastFCntByDevice = new ConcurrentHashMap<>();
        final ConcurrentHashMap<String, Instant> lastReceivedAtByDevice = new ConcurrentHashMap<>();
        final Sinks.Many<String> sink = Sinks.many().multicast().directBestEffort();
        final AtomicInteger subscribers = new AtomicInteger(0);
        private final ObjectMapper mapper = new ObjectMapper();

        AppStream(String appId) { this.appId = appId; }

        void seedLastSeen(String line) {
            try {
                JsonNode result = mapper.readTree(line).path("result");
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
                    ? Set.of() : new HashSet<>(deviceIds);

            return sink.asFlux().filter(line -> {
                try {
                    JsonNode result = mapper.readTree(line).path("result");
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
                } catch (Exception ex) { return false; }
            });
        }
    }
}
