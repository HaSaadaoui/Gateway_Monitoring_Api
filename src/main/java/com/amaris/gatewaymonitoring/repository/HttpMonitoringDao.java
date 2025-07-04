package com.amaris.gatewaymonitoring.repository;

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

    @Value("${lorawan.token}")
    private String lorawanToken;

    private final WebClient.Builder webClientBuilder;

    @Autowired
    public HttpMonitoringDao(WebClient.Builder webClientBuilder) {
        this.webClientBuilder = webClientBuilder;
    }

    public String fetchGatewayData(String gatewayID) {

        String gatewayInfos = "";
        String createdAt = "";
        boolean statut = false;
        String timeOfStatut = "";
        HttpClient httpClient = HttpClient.create()
                .responseTimeout(Duration.ofSeconds(5));

        try {
            WebClient client = webClientBuilder
                    .clientConnector(new ReactorClientHttpConnector(httpClient))
                    .baseUrl(lorawanBaseUrl)
                    .defaultHeader("Authorization", "Bearer " + lorawanToken)
                    .build();

            gatewayInfos = client.get()
                    .uri(gatewayID  + "/events/up")
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            if (gatewayInfos != null && !gatewayInfos.equals("")) {
                createdAt = extractCreatedAtValue(gatewayInfos);
                statut = isGatewayOn(gatewayInfos);
                timeOfStatut = extractTimeValue(gatewayInfos);
            }

//            statusInfo = client.get()
//                    .uri(gatewayId + LORAWAN_GATEWAY_STATUS)
//                    .retrieve()
//                    .bodyToMono(String.class)
//                    .block();
//            if (statusInfo != null && !statusInfo.equals("")) {
//                status = extractStatus(statusInfo);
//            }

        } catch (Exception e) { return ""; }

        return buildLorawanJson(createdAt);
    }

    /**
     * Extrait dynamiquement la valeur du champ "created_at" à partir d'une chaîne de caractères
     * représentant une réponse HTTP brute au format JSON.
     *
     * @param json la réponse HTTP brute sous forme de chaîne
     * @return la valeur du champ "created_at" si trouvée, sinon une chaîne vide
     */
    public String extractCreatedAtValue(String json) {
        String startTag = "created_at\":\"";
        int startIndex = json.indexOf(startTag);
        if (startIndex == -1) return "";

        startIndex += startTag.length();
        int endIndex = json.indexOf("\"", startIndex);
        if (endIndex == -1) return "";

        return json.substring(startIndex, endIndex);
    }

    public boolean isGatewayOn(String json) {
        String startTag = "\"name\":\"";
        int startIndex = json.indexOf(startTag);
        if (startIndex == -1) return false;

        startIndex += startTag.length();
        int endIndex = json.indexOf("\"", startIndex);
        if (endIndex == -1) return false;

        String nameValue = json.substring(startIndex, endIndex);
        return nameValue.endsWith("receive");
    }

    public String extractTimeValue(String json) {
        String startTag = "\"time\":\"";
        int startIndex = json.indexOf(startTag);
        if (startIndex == -1) return "";

        startIndex += startTag.length();
        int endIndex = json.indexOf("\"", startIndex);
        if (endIndex == -1) return "";

        return json.substring(startIndex, endIndex);
    }


    /**
     * Construit une chaîne JSON partielle sans accolades externes,
     * contenant la date de création dans la structure TTN.
     *
     * @param createdAt la date de création au format ISO 8601
     * @return une chaîne JSON partielle avec la date dans "ttn": {"info": {"created_at": ...}}
     */
    public String buildLorawanJson(String createdAt) {
        return "\t\"ttn\": {\n" +
                "  \t\t\"info\": {\n" +
                "    \t\t\t\"created_at\": \"" + createdAt + "\"\n" +
                "  \t\t}\n" +
                "\t}";
    }

}
