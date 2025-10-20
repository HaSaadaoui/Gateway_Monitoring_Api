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
        // Convertir gateway ID en application ID (ex: "rpi-mantu" -> "rpi-mantu-appli")
        String applicationId = gatewayId + "-appli";
        
        String devicesJson = httpMonitoringDao.fetchDevices(applicationId);
        String dataGateway = httpMonitoringDao.fetchGatewayData(gatewayId);
        return mergeLorawanJson(devicesJson, dataGateway);
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
