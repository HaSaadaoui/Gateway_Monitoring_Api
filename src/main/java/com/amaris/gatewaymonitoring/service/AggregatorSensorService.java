package com.amaris.gatewaymonitoring.service;

import org.springframework.stereotype.Service;

import java.util.function.Consumer;

@Service
public class AggregatorSensorService {

    private final SensorMonitoringService sensorMonitoringService;

    public AggregatorSensorService(SensorMonitoringService sensorMonitoringService) {
        this.sensorMonitoringService = sensorMonitoringService;
    }

    public void aggregateSensorMonitoring(String appId, String deviceId, String threadId, Consumer<String> callback) {
        sensorMonitoringService.startTtnPolling(appId, deviceId, threadId, callback);
    }

    public void stopSensorMonitoring(String threadId) {
        sensorMonitoringService.stopTtnPolling(threadId);
    }

    public void aggregateGatewayDevices(String appId, Consumer<String> callback) {
        sensorMonitoringService.probeGatewayDevices(appId, callback);
    }
}
