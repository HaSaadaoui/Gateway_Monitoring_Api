package com.amaris.gatewaymonitoring.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.function.Consumer;

@Service
public class AggregatorMonitoringService {

    private final SshMonitoringService sshMonitoringService;
    private final HttpMonitoringService httpMonitoringService;

    @Autowired
    public AggregatorMonitoringService(SshMonitoringService sshMonitoringService, HttpMonitoringService httpMonitoringService) {
        this.sshMonitoringService = sshMonitoringService;
        this.httpMonitoringService = httpMonitoringService;
    }

    /**
     * Agrège les données système du Raspberry Pi et les informations du serveur Lorawan,
     * puis transmet le résultat au consommateur fourni.
     *
     * @param gatewayID l'id du Raspberry Pi cible
     * @param gatewayIP l'ip du Raspberry Pi cible
     * @param onJsonReceived fonction consommateur qui reçoit les données JSON agrégées
     */
    public void aggregateRaspberryLorawanMonitoring(String gatewayID, String gatewayIP, Consumer<String> onJsonReceived) {
        String gateway_id = "rpi-mantu";
        String lorawanJson = httpMonitoringService.getLorawanData(gateway_id); // remplacer ensuite par gatewayID

        sshMonitoringService.startSshMonitoring(gatewayID, gatewayIP, raspberryJson -> {
            String aggregatedJson = mergeJson(raspberryJson, lorawanJson);
            onJsonReceived.accept(aggregatedJson);
        });
    }

    /**
     * Injecte un bloc JSON Lorawan dans un JSON principal.
     *
     * @param raspberryJson Le JSON de base contenant les informations système.
     * @param lorawanJson   Le JSON Lorawan à injecter commençant typiquement par "ttn": { ... }.
     * @return Une chaîne JSON combinée contenant à la fois les données système et Lorawan.
     */
    public String mergeJson(String raspberryJson, String lorawanJson) {
        if (raspberryJson == null || raspberryJson.isBlank() || raspberryJson.equals("{}")) return "{}";
        if (lorawanJson == null || lorawanJson.isBlank() || lorawanJson.equals("{}")) return raspberryJson;

        raspberryJson = raspberryJson.replaceFirst("(?s)(.*)}\\n", "$1},\n");
        StringBuilder mergedJson = new StringBuilder(raspberryJson);
        mergedJson.deleteCharAt(mergedJson.lastIndexOf("}")); // Retire la dernière }
        mergedJson.append(lorawanJson);
        mergedJson.append("\n}");

        return mergedJson.toString();
    }

}
