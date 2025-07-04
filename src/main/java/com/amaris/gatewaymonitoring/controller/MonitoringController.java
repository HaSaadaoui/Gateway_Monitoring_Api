package com.amaris.gatewaymonitoring.controller;

import com.amaris.gatewaymonitoring.service.AggregatorMonitoringService;
//import com.amaris.gatewaymonitoring.service.MqttMonitoringService;
import com.amaris.gatewaymonitoring.service.SshMonitoringService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api")
public class MonitoringController {

//    private final MqttMonitoringService mqttMonitoringService;
    private final SshMonitoringService sshMonitoringService;
    private final AggregatorMonitoringService aggregatorMonitoringService;

    @Autowired
    public MonitoringController(
//            MqttMonitoringService mqttMonitoringService,
            SshMonitoringService sshMonitoringService,
            AggregatorMonitoringService aggregatorMonitoringService
            ) {
//        this.mqttMonitoringService = mqttMonitoringService;
        this.sshMonitoringService = sshMonitoringService;
        this.aggregatorMonitoringService = aggregatorMonitoringService;
    }

//    /**
//     * Stream en temps réel les informations du broker MQTT
//     * sur le Raspberry Pi
//     *
//     * @param id adresse IP de la passerelle cible
//     * @return un flux SSE contenant les données système au format JSON
//     */
//    @GetMapping(value="/monitoring/sensor/{id}", produces=MediaType.TEXT_EVENT_STREAM_VALUE)
//    public Flux<String> streamSensorData(@PathVariable String id) {
//        return Flux.create(sink -> {
//            mqttMonitoringService.startMqttMonitoring(id, json -> {
//                sink.next(json);
//            });
//        });
//    }

    /**
     * Diffuse en temps réel les informations système (CPU, RAM, etc.) d'une passerelle (Raspberry Pi)
     * via une connexion SSH, tout en agrégeant les données provenant du serveur Lorawan.
     *
     * @param id est l'id de la passerelle cible
     * @param ip est l'ip de la passerelle cible
     * @return un flux SSE (Server-Sent Events) émettant les données système au format JSON
     */
    @GetMapping(value="/monitoring/gateway/{id}", produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> streamGatewayMonitoring(@PathVariable String id, @RequestParam String ip) {
        return Flux.create(sink ->
            aggregatorMonitoringService.aggregateRaspberryLorawanMonitoring(id, ip, json -> {
                System.out.println(json);
                sink.next(json);
            })
        );
    }

    /**
     * Arrête la surveillance SSH pour la gateway identifiée par son ID.
     * Tester avec l'url http://localhost:8081/api/monitoring/gateway/stop/<gatewayId>
     *
     * @param id l'identifiant de la gateway dont la surveillance doit être arrêtée
     * @return une réponse HTTP 200 OK avec un message confirmant l'arrêt de la surveillance
     */
    @GetMapping("/monitoring/gateway/stop/{id}")
    public ResponseEntity<String> stopMonitoring(@PathVariable String id) {
        sshMonitoringService.stopSshMonitoring(id);
        return ResponseEntity.ok("Monitoring stopped for gateway with ID: " + id);
    }

}
