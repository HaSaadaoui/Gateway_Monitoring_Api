package com.amaris.gatewaymonitoring.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
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

    @Value("${lorawan.application.baseurl}")
    private String lorawanApplicationBaseUrl;

    @Value("${lorawan.service.token}")
    private String lorawanServiceToken;

    /**
     * Métadonnées spécifique car l'id de l'Application ne respect pas
     * la norme <gatewayId>-application
     */
    private static final String LEVA_RPI_MANTU = "leva-rpi-mantu";
    private static final String LORAWAN_NETWORK_MANTU = "lorawan-network-mantu";
    private static final String NUMBER_OF_DEVICES = "?limit=200";

    private final WebClient.Builder webClientBuilder;

    @Autowired
    public HttpMonitoringDao(WebClient.Builder webClientBuilder) {
        this.webClientBuilder = webClientBuilder;
    }

    public String fetchGatewayData(String gatewayID) {

        String gatewayInfos = "";
        HttpClient httpClient = HttpClient.create()
                .responseTimeout(Duration.ofSeconds(5));

        try {
            WebClient client = webClientBuilder
                    .clientConnector(new ReactorClientHttpConnector(httpClient))
                    .baseUrl(lorawanBaseUrl)
                    .defaultHeader("Authorization", "Bearer " + lorawanServiceToken)
                    .build();

            gatewayInfos = client.get()
//                    .uri(gatewayID + "?field_mask=antennas")
                    .uri(gatewayID + "?field_mask=ids,name,antennas")
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            if (gatewayInfos != null && !gatewayInfos.equals("")) {
                return extractCreatedAtAndLocation(gatewayInfos);
            } else return "{}";
        } catch (Exception e) { return "{}"; }
    }

    public String fetchDevices(String gatewayID) {
        String devices = "";
        HttpClient httpClient = HttpClient.create()
                .responseTimeout(Duration.ofSeconds(5));

        try {
            WebClient client = webClientBuilder
                    .clientConnector(new ReactorClientHttpConnector(httpClient))
                    .baseUrl(lorawanApplicationBaseUrl)
                    .defaultHeader("Authorization", "Bearer " + lorawanServiceToken)
                    .build();

            if (gatewayID.equals(LEVA_RPI_MANTU)) {
                devices = client.get()
                        .uri(LORAWAN_NETWORK_MANTU + "/devices" + NUMBER_OF_DEVICES)
                        .retrieve()
                        .bodyToMono(String.class)
                        .block();
            } else {
                devices = client.get()
                    .uri(gatewayID + "-appli" + "/devices" + NUMBER_OF_DEVICES)
                        .retrieve()
                        .bodyToMono(String.class)
                        .block();
            }

            return cleanDevicesJson(devices);
        } catch (Exception e) {
            return "{}";
        }
    }

    /**
     * Extrait la date de création et la localisation (latitude, longitude, altitude, source)
     * depuis une chaîne JSON décrivant un gateway, et retourne un sous-JSON formaté.
     *
     * @param gatewayInfos JSON brut contenant les informations du gateway
     * @return JSON contenant uniquement "created_at" et "location", ou "{}" en cas d'erreur
     */
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
            String source = loc.path("source").asText("");
            String safeSource = source.replace("\\", "\\\\").replace("\"", "\\\"");

            return "{" +
                    "\"gateway_info\": {\n" +
                    "\t\"name\": \"" + name + "\",\n" +
                    "\t\t\"created_at\": \"" + createdAt + "\",\n" +
                    "\t\t\"location\": {\n" +
                    "\t\t\t\"latitude\": " + lat + ",\n" +
                    "\t\t\t\"longitude\": " + lon + ",\n" +
                    "\t\t\t\"altitude\": " + alt + ",\n" +
                    "\t\t\t\"source\": \"" + safeSource + "\"\n" +
                    "\t\t}\n" +
                    "\t}\n" +
                    "}";

        } catch (Exception e) {
            return "{}";
        }
    }

    /**
     * Extrait uniquement les "device_id" et "application_id" d'un JSON brut
     * de devices TTN et retourne un JSON simplifié avec la liste des capteurs.
     *
     * @param devicesjson JSON brut contenant les devices
     * @return JSON propre avec seulement "device_id" et "application_id"
     */
    public String cleanDevicesJson(String devicesjson) {
        if (devicesjson == null || devicesjson.isEmpty()) return "{}";

        StringBuilder result = new StringBuilder();
        result.append("{\n\t\"devices\":[\n");

        int index = 0;
        boolean first = true;

        while ((index = devicesjson.indexOf("\"device_id\":\"", index)) != -1) {
            int startDevice = index + "\"device_id\":\"".length();
            int endDevice = devicesjson.indexOf("\"", startDevice);
            String deviceId = devicesjson.substring(startDevice, endDevice);

            int appIndex = devicesjson.indexOf("\"application_id\":\"", endDevice);
            int startApp = appIndex + "\"application_id\":\"".length();
            int endApp = devicesjson.indexOf("\"", startApp);
            String appId = devicesjson.substring(startApp, endApp);

            if (!first) result.append(",\n");
            result.append("\t\t{\"device_id\":\"").append(deviceId)
                    .append("\",\"application_id\":\"").append(appId).append("\"}");
            first = false;

            index = endApp;
        }

        result.append("\n\t]\n}");
        return result.toString();
    }

}
