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
        String devicesJson = httpMonitoringDao.fetchDevices(gatewayId);
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
        devicesJson = devicesJson.trim();
        gatewayJson = gatewayJson.trim();

        if (devicesJson.startsWith("{") && devicesJson.endsWith("}")) {
            devicesJson = devicesJson.substring(1, devicesJson.length() - 1);
            if (devicesJson.endsWith("\n")) {
                devicesJson = devicesJson.substring(0, devicesJson.length() - 1);
            }
            devicesJson += ",";
        }
        if (gatewayJson.startsWith("{") && gatewayJson.endsWith("}")) {
            gatewayJson = gatewayJson.substring(1, gatewayJson.length() - 1);
            if (gatewayJson.endsWith("\n")) {
                gatewayJson = gatewayJson.substring(0, gatewayJson.length() - 1);
            }
        }

        return "{\n" + devicesJson + "\n\t" + gatewayJson + "\n}";
    }

}
