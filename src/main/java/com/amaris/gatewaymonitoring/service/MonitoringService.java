package com.amaris.gatewaymonitoring.service;

import com.amaris.gatewaymonitoring.repository.MonitoringDao;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.function.Consumer;

@Service
public class MonitoringService {

    @Value("${mqtt.port}")
    private int mqttPort;

    @Value("${mqtt.username}")
    private String username;

    @Value("${mqtt.password}")
    private String password;

    private final MonitoringDao monitoringDao;

    @Autowired
    public MonitoringService(MonitoringDao monitoringDao) {
        this.monitoringDao = monitoringDao;
    }

    public void startMonitoring(String ip, Consumer<String> onJsonReceived) {
        String brokerUrl = "tcp://" + "10.243.129.10" + ":" + mqttPort;
        monitoringDao.startListening(brokerUrl, username, password, "topic", json -> {
            onJsonReceived.accept(json);
        });
    }

    public void stopMonitoring() {
        monitoringDao.stopListening();
    }

}
