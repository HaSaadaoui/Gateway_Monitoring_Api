package com.amaris.gatewaymonitoring.service;

import com.amaris.gatewaymonitoring.repository.DatabaseMonitoringDao;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class DatabaseMonitoringService {

    private final DatabaseMonitoringDao databaseMonitoringDao;

    @Autowired
    public DatabaseMonitoringService(DatabaseMonitoringDao databaseMonitoringDao) {
        this.databaseMonitoringDao = databaseMonitoringDao;
    }

    public String getGatewayLocationData(String gatewayID) {
        try {
            return databaseMonitoringDao.getLocationGateway(gatewayID);
        } catch (Exception e) {
            return "";
        }
    }

}
