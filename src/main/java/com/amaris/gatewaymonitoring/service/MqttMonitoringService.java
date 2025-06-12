//package com.amaris.gatewaymonitoring.service;
//
//import com.amaris.gatewaymonitoring.repository.MqttMonitoringDao;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.beans.factory.annotation.Value;
//import org.springframework.stereotype.Service;
//
//import java.util.function.Consumer;
//
//@Service
//public class MqttMonitoringService {
//
//    @Value("${mqtt.port}")
//    private int mqttPort;
//
//    @Value("${mqtt.username}")
//    private String username;
//
//    @Value("${mqtt.password}")
//    private String password;
//
//    private final MqttMonitoringDao mqttMonitoringDao;
//
//    @Autowired
//    public MqttMonitoringService(MqttMonitoringDao mqttMonitoringDao) {
//        this.mqttMonitoringDao = mqttMonitoringDao;
//    }
//
//    public void startMqttMonitoring(String ip, Consumer<String> onJsonReceived) {
//        String brokerUrl = "tcp://" + "10.243.129.10" + ":" + mqttPort;
//        String topicForTest = "v3/reseau-lorawan@ttn/devices/pir-light/up";
//        mqttMonitoringDao.startMqttListening(brokerUrl, username, password, topicForTest, json -> {
//            onJsonReceived.accept(json);
//        });
//    }
//
//    public void stopMqttMonitoring() {
//        mqttMonitoringDao.stopMqttListening();
//    }
//
//}
