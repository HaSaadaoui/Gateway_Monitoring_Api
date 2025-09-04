package com.amaris.gatewaymonitoring.service;

import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.function.Consumer;

@Service
public class AggregatorMonitoringService {

    private final SshMonitoringService sshMonitoringService;
    private final HttpMonitoringService httpMonitoringService;
    private final MqttMonitoringService mqttMonitoringService;
    private final DatabaseMonitoringService databaseMonitoringService;

    @Autowired
    public AggregatorMonitoringService(
            SshMonitoringService sshMonitoringService,
            HttpMonitoringService httpMonitoringService,
            MqttMonitoringService mqttMonitoringService,
            DatabaseMonitoringService databaseMonitoringService) {
        this.sshMonitoringService = sshMonitoringService;
        this.httpMonitoringService = httpMonitoringService;
        this.mqttMonitoringService = mqttMonitoringService;
        this.databaseMonitoringService = databaseMonitoringService;
    }

    /**
     * Agrège les données système du Raspberry Pi et les informations du serveur Lorawan,
     * puis transmet le résultat au consommateur fourni.
     *
     * @param gatewayID l'id du Raspberry Pi cible
     * @param gatewayIP l'ip du Raspberry Pi cible
     * @param threadId est l'id du thread qui sera créé pour écouter le système
     * @param onJsonReceived fonction consommateur qui reçoit les données JSON agrégées
     */
    public void aggregateRaspberryLorawanMonitoring(String gatewayID, String gatewayIP, String threadId, Consumer<String> onJsonReceived) {
//        mqttMonitoringService.startMqttMonitoring("eu1.cloud.thethings.network", onJsonReceived);

//        String gateway_id = "rpi-mantu"; // "leva-rpi-mantu";
//        String lorawanJson = httpMonitoringService.getLorawanData(gateway_id); // remplacer ensuite par gatewayID
        String lorawanJson = httpMonitoringService.getLorawanData(gatewayID);

//        String databaseJson = databaseMonitoringService.getGatewayLocationData(gateway_id);
        String databaseJson = databaseMonitoringService.getGatewayLocationData(gatewayID);

        sshMonitoringService.startSshMonitoring(gatewayID, gatewayIP, threadId, raspberryJson -> {
            String aggregatedJson = mergeJson(raspberryJson, lorawanJson, databaseJson);
            onJsonReceived.accept(aggregatedJson);
        });
    }

    /**
     * Fusionne trois JSON en un seul : système, Lorawan et base.
     * Nettoie les accolades et retours à la ligne pour un JSON lisible.
     *
     * @param raspberryJson JSON système du Raspberry Pi
     * @param lorawanJson JSON des devices Lorawan
     * @param databaseJson Localisation depuis la base
     * @return JSON combiné propre et indenté
     */
    public String mergeJson(String raspberryJson, String lorawanJson, String databaseJson) {
        if (raspberryJson == null || raspberryJson.isBlank() || raspberryJson.equals("{}")) return "{}";
        if (lorawanJson == null || lorawanJson.isBlank() || lorawanJson.equals("{}")) return raspberryJson;

        raspberryJson = raspberryJson.trim();
        if (raspberryJson.endsWith("}")) {
            raspberryJson = raspberryJson.substring(0, raspberryJson.length() - 1).trim();
        }
        raspberryJson += ",";

        lorawanJson = lorawanJson.trim();
        if (lorawanJson.startsWith("{") && lorawanJson.endsWith("}")) {
            lorawanJson = lorawanJson.substring(1, lorawanJson.length() - 1).trim();
        }

        return raspberryJson + "\n\t" + lorawanJson
                + ",\n\t\"database\": {\n\t\t\"location\": \"" + databaseJson + "\"\n\t}\n}";
    }

}
