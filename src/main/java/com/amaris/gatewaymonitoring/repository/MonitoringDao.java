package com.amaris.gatewaymonitoring.repository;

import com.amaris.gatewaymonitoring.config.MqttConfig;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.util.concurrent.atomic.AtomicReference;

@Repository
public class MonitoringDao {

    @Value("${mqtt.port}")
    private int mqttPort;

    @Value("${mqtt.username}")
    private String username;

    @Value("${mqtt.password}")
    private String password;

    private final MqttConfig.MyGateway myGateway;

    @Autowired
    public MonitoringDao(MqttConfig.MyGateway myGateway) {
        this.myGateway = myGateway;
    }

    public String getMonitoringData(String id) {
        AtomicReference<String> result = new AtomicReference<>("");
        try {
            MqttClient client = new MqttClient("tcp://10.243.129.10:1883", MqttClient.generateClientId());
            MqttConnectOptions options = new MqttConnectOptions();
            options.setUserName(username);
            options.setPassword(password.toCharArray());
            client.connect(options);

            String responseTopic = "nom du topic qui est dans le broker lié aux données";
            client.subscribe(responseTopic, 1, (t, msg) -> {
                result.set(new String(msg.getPayload()));
            });

            client.disconnect();
        } catch (MqttException e) {
            e.printStackTrace();
        }

        return result.get();
    }



}
