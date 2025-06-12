package com.amaris.gatewaymonitoring.service;

import com.amaris.gatewaymonitoring.repository.SshMonitoringDao;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.function.Consumer;

@Service
public class SshMonitoringService {

    private final SshMonitoringDao sshMonitoringDao;

    @Autowired
    public SshMonitoringService(SshMonitoringDao sshMonitoringDao) {
        this.sshMonitoringDao = sshMonitoringDao;
    }

    public void startSshMonitoring(String gatewayID, String gatewayIP, Consumer<String> onJsonReceived) {
        sshMonitoringDao.startSshListening(gatewayID, gatewayIP, json -> {
            onJsonReceived.accept(json);
        });
    }

    public void stopSshMonitoring(String id) {
        sshMonitoringDao.stopSshListening(id);
    }

}
