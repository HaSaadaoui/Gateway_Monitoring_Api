package com.amaris.gatewaymonitoring.service;

import com.amaris.gatewaymonitoring.repository.MonitoringDao;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class MonitoringService {

    private final MonitoringDao monitoringDao;

    @Autowired
    public MonitoringService(MonitoringDao monitoringDao) {
        this.monitoringDao = monitoringDao;
    }

    public String getMonitoringData(String id) {
        return monitoringDao.getMonitoringData(id);
    }

}
