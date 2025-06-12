//package com.amaris.gatewaymonitoring.repository;
//
//import com.amaris.gatewaymonitoring.config.MqttConfig;
//import org.eclipse.paho.client.mqttv3.MqttClient;
//import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
//import org.eclipse.paho.client.mqttv3.MqttException;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.stereotype.Repository;
//
//import java.util.function.Consumer;
//
//@Repository
//public class MonitoringDao {
//
//    private MqttClient client;
//
//    private final MqttConfig.MyGateway myGateway;
//
//    @Autowired
//    public MonitoringDao(MqttConfig.MyGateway myGateway) {
//        this.myGateway = myGateway;
//    }
//
//    /**
//     * Démarre l'écoute d'un topic MQTT et traite les messages JSON reçus en temps réel.
//     *
//     * @param brokerUrl          L'URL du broker MQTT (ex. "tcp://localhost:1883").
//     * @param username           Le nom d'utilisateur pour la connexion au broker.
//     * @param password           Le mot de passe associé à l'utilisateur.
//     * @param topic                  Le nom du topic à écouter (ex. "zigbee2mqtt/maGateway").
//     * @param onMessageReceived  Fonction de rappel (callback) exécutée à chaque fois qu’un nouveau message arrive sur le topic.
//     *
//     */
//    public void startListening(String brokerUrl, String username, String password, String topic, Consumer<String> onMessageReceived) {
//        try {
//            client = new MqttClient(brokerUrl, MqttClient.generateClientId());
//            MqttConnectOptions options = new MqttConnectOptions();
//            options.setUserName(username);
//            options.setPassword(password.toCharArray());
//            client.connect(options);
//
//            client.subscribe(topic, (t, msg) -> {
//                String json = new String(msg.getPayload());
//                System.out.println("Message reçu : " + json);
//                onMessageReceived.accept(json);
//            });
//
//        } catch (MqttException e) {
//            e.printStackTrace();
//        }
//    }
//
//    /**
//     * Déconnecte proprement le client MQTT si celui-ci est encore connecté,
//     * puis ferme la connexion afin de libérer les ressources.
//     *
//     */
//    public void stopListening() {
//        try {
//            if (client != null && client.isConnected()) {
//                client.disconnect();
//                client.close();
//            }
//        } catch (MqttException e) {
//            e.printStackTrace();
//        }
//    }
//
//}
