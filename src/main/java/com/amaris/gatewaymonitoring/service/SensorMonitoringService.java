package com.amaris.gatewaymonitoring.service;

import com.amaris.gatewaymonitoring.config.LorawanProperties;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Flux;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@Service
public class SensorMonitoringService {

    private final WebClient webClient;
    private final String ttnBaseUrl;
    private final LorawanProperties lorawanProperties;

    public SensorMonitoringService(
            WebClient.Builder webClientBuilder,
            LorawanProperties lorawanProperties
    ) {
        this.webClient = webClientBuilder.build();
        this.ttnBaseUrl = lorawanProperties.getBaseurl();
        this.lorawanProperties = lorawanProperties;
    }

    public Flux<String> probeGatewayDevicesFlux(String appId, Optional<Instant> after, int limit) {
        String token = lorawanProperties.getToken().getOrDefault(appId,
                lorawanProperties.getToken().values().stream().findFirst().orElse(""));

        UriComponentsBuilder urlBuilder = UriComponentsBuilder
                .fromHttpUrl(ttnBaseUrl)
                .pathSegment("as", "applications", appId, "packages", "storage", "uplink_message")
                .queryParam("order", "-received_at")
                .queryParam("limit", Math.max(1, Math.min(limit, 200)));

        after.ifPresent(instant -> urlBuilder.queryParam("after", instant.toString()));

        return webClient.get()
                .uri(urlBuilder.build().toUriString())
                .accept(MediaType.APPLICATION_NDJSON)
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .bodyToFlux(String.class)
                .filter(line -> line != null && !line.isBlank())
                .retryWhen(
                        Retry.backoff(10, Duration.ofSeconds(2))
                                .maxBackoff(Duration.ofSeconds(60))
                                .jitter(0.3)
                                .filter(ex -> ex instanceof WebClientResponseException.TooManyRequests)
                )
                .onErrorResume(WebClientResponseException.TooManyRequests.class, e -> Flux.empty());
    }
}