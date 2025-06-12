package com.amaris.gatewaymonitoring.repository;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ClientChannel;
import org.apache.sshd.client.session.ClientSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

@Repository
public class SshMonitoringDao {

    @Value("${mqtt.username}")
    private String username;

    @Value("${mqtt.password}")
    private String password;

    private final Map<String, Thread> monitoringThreads = new ConcurrentHashMap<>();

    private final String SCRIPT_SHELL = "while true; do " +
             "echo \"$(date -u +%Y-%m-%dT%H:%M:%SZ) " +
             "$(hostname) " +
             "$(hostname -I | awk '{print $1}') " +
             "$(curl -s https://api.ipify.org) " +
             "$(top -bn1 | grep \"Cpu(s)\" | awk '{print 100 - $8}') " +
             "$(awk '{print $1/1000}' /sys/class/thermal/thermal_zone0/temp) " +
             "$(free -g | awk '/Mem:/ {print $2, $3}')\" " +
             "$(uptime_seconds=$(cut -d. -f1 /proc/uptime); echo \"" +
             "$(( uptime_seconds / 86400 )) days $(( (uptime_seconds % 86400) / 3600 )) hours\")\n" +
             "sleep 10; " +
             "done";

    public void startSshListening(String gatewayID, String gatewayIP, Consumer<String> onJsonReceived) {
        Thread monitoringThread = new Thread(() -> {
            SshClient client = SshClient.setUpDefaultClient();
            client.start();
            String ipPublicTest = "10.243.129.10"; // A REMPLACER PAR gatewayIP

            try (ClientSession session = client.connect(username, ipPublicTest, 22).verify(10000).getSession()) {
                session.addPasswordIdentity(password);
                session.auth().verify(5000);

                try (ClientChannel channel = session.createExecChannel(
                        SCRIPT_SHELL
                )) {
                    channel.open().verify(5000);

                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(channel.getInvertedOut(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (Thread.currentThread().isInterrupted()) {
                                break;
                            }
                            String json = buildJson(line);
                            onJsonReceived.accept(json);
                            // System.out.println(json);
                        }
                    } catch (InterruptedIOException e) {}
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                client.stop();
            }
        });
        monitoringThreads.put(gatewayID, monitoringThread);
        monitoringThread.start();
    }

    /**
     * Interrompt et arrête le thread de surveillance SSH associé à l'adresse IP du Raspberry Pi donnée.
     *
     * @param gatewayID l'adresse IP du Raspberry Pi dont la surveillance doit être arrêtée
     */
    public void stopSshListening(String gatewayID) {
        Thread thread = monitoringThreads.get(gatewayID);
        if (thread != null && thread.isAlive()) {
            thread.interrupt();
            monitoringThreads.remove(gatewayID);
        }
    }

    /**
     * Transforme une ligne de sortie shell en une chaîne JSON formatée contenant les informations système.
     *
     * @param lineShell une ligne de texte issue de la sortie shell contenant les données système séparées par des espaces
     * @return une chaîne JSON représentant les informations système extraites de la ligne, ou "{}" si la ligne est invalide
     */
    public String buildJson(String lineShell) {
        String[] parts = lineShell.trim().split("\\s+");
        if (parts.length < 10) return "{}";

        String timestamp = parts[0];
        String hostname = parts[1];
        String ipLocal = parts[2];
        String ipPublic = parts[3];
        double cpuPercent = Double.parseDouble(parts[4]);
        double cpuTemp = Double.parseDouble(parts[5]);
        double ramTotal = Double.parseDouble(parts[6]);
        double ramUsed = Double.parseDouble(parts[7]);
        String uptimeDays = parts[8] + " " + parts[9] + " " + parts[10] + " " + parts[11];

        String json = "{\n" +
                "\t\"timestamp\": \"" + timestamp + "\",\n" +
                "\t\"system\": {\n" +
                "\t\t\"hostname\": \"" + hostname + "\",\n" +
                "\t\t\"ip_local\": \"" + ipLocal + "\",\n" +
                "\t\t\"ip_public\": \"" + ipPublic + "\",\n" +
                "\t\t\"cpu_percent (%)\": " + cpuPercent + ",\n" +
                "\t\t\"cpu_temp (C)\": " + cpuTemp + ",\n" +
                "\t\t\"ram_total_gb (GB)\": " + ramTotal + ",\n" +
                "\t\t\"ram_used_gb (GB)\": " + ramUsed + ",\n" +
                "\t\t\"uptime_days\": \"" + uptimeDays + "\"\n" +
                "\t}\n" +
                "}";
        return json;
    }

}
