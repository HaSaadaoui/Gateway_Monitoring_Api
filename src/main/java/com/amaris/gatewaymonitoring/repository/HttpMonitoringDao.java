package com.amaris.gatewaymonitoring.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Repository;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

@Repository
public class HttpMonitoringDao {

    @Value("${lorawan.baseurl}")
    private String lorawanBaseUrl;

    @Value("${lorawan.service.token}")
    private String lorawanServiceToken;

    private static final String NUMBER_OF_DEVICES = "?limit=200";

    private final WebClient.Builder webClientBuilder;

    public HttpMonitoringDao(WebClient.Builder webClientBuilder) {
        this.webClientBuilder = webClientBuilder;
    }

    private WebClient buildClient() {
        HttpClient httpClient = HttpClient.create()
                .responseTimeout(Duration.ofSeconds(5));

        return webClientBuilder
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .baseUrl(lorawanBaseUrl)
                .defaultHeader("Authorization", "Bearer " + lorawanServiceToken)
                .build();
    }

    /**
     * Récupère les infos d'un gateway
     */
    public String fetchGatewayData(String gatewayId) {
        try {
            String response = buildClient().get()
                    .uri("/gateways/" +gatewayId + "?field_mask=ids,name,antennas")
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            return (response != null && !response.isEmpty()) ? extractCreatedAtAndLocation(response) : "{}";
        } catch (Exception e) {
            return "{}";
        }
    }

    /**
     * Récupère les devices d'une application / gateway
     */
    public String fetchDevices(String applicationId) {
        try {
            String response = buildClient().get()
                    .uri("/applications/" + applicationId + "/devices" + NUMBER_OF_DEVICES)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            return (response != null) ? cleanDevicesJson(response) : "{}";
        } catch (Exception e) {
            return "{}";
        }
    }

    private String extractCreatedAtAndLocation(String gatewayInfos) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(gatewayInfos);

            String createdAt = root.path("created_at").asText("");
            String name = root.path("name").asText("");

            JsonNode loc = root.path("antennas").isArray() && !root.path("antennas").isEmpty()
                    ? root.path("antennas").get(0).path("location")
                    : mapper.createObjectNode();

            double lat = loc.path("latitude").asDouble();
            double lon = loc.path("longitude").asDouble();
            int alt = loc.path("altitude").asInt();
            String source = loc.path("source").asText("").replace("\\", "\\\\").replace("\"", "\\\"");

            return "{" +
                    "\"gateway_info\": {" +
                    "\"name\":\"" + name + "\"," +
                    "\"created_at\":\"" + createdAt + "\"," +
                    "\"location\":{" +
                    "\"latitude\":" + lat + "," +
                    "\"longitude\":" + lon + "," +
                    "\"altitude\":" + alt + "," +
                    "\"source\":\"" + source + "\"" +
                    "}" +
                    "}" +
                    "}";
        } catch (Exception e) {
            return "{}";
        }
    }

    public String cleanDevicesJson(String devicesJson) {
        if (devicesJson == null || devicesJson.isEmpty()) return "{}";

        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(devicesJson);
            JsonNode endDevices = root.path("end_devices");

            if (!endDevices.isArray() || endDevices.isEmpty()) {
                return "{}";
            }

            StringBuilder result = new StringBuilder();
            result.append("{\"devices\":[");

            boolean first = true;
            for (JsonNode device : endDevices) {
                String deviceId = device.path("ids").path("device_id").asText("");
                String appId = device.path("ids").path("application_ids").path("application_id").asText("");

                if (!deviceId.isEmpty() && !appId.isEmpty()) {
                    if (!first) result.append(",");
                    result.append("{\"device_id\":\"").append(deviceId)
                          .append("\",\"application_id\":\"").append(appId).append("\"}");
                    first = false;
                }
            }

            result.append("]}");
            return result.toString();
        } catch (Exception e) {
            return "{}";
        }
    }
}
