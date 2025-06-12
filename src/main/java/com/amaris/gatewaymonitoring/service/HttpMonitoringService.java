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
        return httpMonitoringDao.fetchGatewayData(gatewayId);
    }

}
