package com.amaris.gatewaymonitoring.service;

import com.amaris.gatewaymonitoring.repository.HttpMonitoringDao;
import org.springframework.stereotype.Service;

@Service
public class HttpMonitoringService {

    private final HttpMonitoringDao httpMonitoringDao;

    public HttpMonitoringService(HttpMonitoringDao httpMonitoringDao) {
        this.httpMonitoringDao = httpMonitoringDao;
    }

    public String getLorawanData(String gatewayId) {
        // Mapper les gateways aux applications TTN
        String applicationId = getApplicationIdForGateway(gatewayId);
        
        String devicesJson = httpMonitoringDao.fetchDevices(applicationId);
        String dataGateway = httpMonitoringDao.fetchGatewayData(gatewayId);
        return mergeLorawanJson(devicesJson, dataGateway);
    }
    
    /**
     * Retourne l'Application ID TTN associée à une gateway.
     * Mapping explicite basé sur les applications TTN existantes.
     */
    private String getApplicationIdForGateway(String gatewayId) {
        switch (gatewayId) {
            case "rpi-mantu":
                // Châteaudun-Mantu-Building (3 devices)
                return "rpi-mantu-appli";
            case "leva-rpi-mantu":
                // Levallois-Mantu-Building (114 devices)
                return "lorawan-network-mantu";
            case "lil-rpi-mantu":
                // Lille-Mantu-Building (0 devices)
                return "lil-rpi-mantu-appli";
            default:
                // Par défaut, ajouter "-appli" au gateway ID
                return gatewayId + "-appli";
        }
    }

    /**
     * Fusionne le JSON des devices et le JSON des infos gateway en un seul JSON.
     *
     * @param devicesJson JSON des devices
     * @param gatewayJson JSON des infos gateway
     * @return JSON fusionné
     */
    public String mergeLorawanJson(String devicesJson, String gatewayJson) {
        devicesJson = (devicesJson != null) ? devicesJson.trim() : "{}";
        gatewayJson = (gatewayJson != null) ? gatewayJson.trim() : "{}";

        // Extraire le contenu entre accolades
        if (devicesJson.startsWith("{") && devicesJson.endsWith("}")) {
            devicesJson = devicesJson.substring(1, devicesJson.length() - 1).trim();
        }
        if (gatewayJson.startsWith("{") && gatewayJson.endsWith("}")) {
            gatewayJson = gatewayJson.substring(1, gatewayJson.length() - 1).trim();
        }

        // Construire le JSON final
        StringBuilder result = new StringBuilder("{\n");
        
        if (!devicesJson.isEmpty()) {
            result.append(devicesJson);
            if (!gatewayJson.isEmpty()) {
                result.append(",\n\t");
            }
        }
        
        if (!gatewayJson.isEmpty()) {
            result.append(gatewayJson);
        }
        
        result.append("\n}");
        return result.toString();
    }

}
