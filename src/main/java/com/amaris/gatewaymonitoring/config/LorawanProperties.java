package com.amaris.gatewaymonitoring.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "lorawan")
public class LorawanProperties {

    private List<String> apps = new ArrayList<>();
    private Map<String, String> token = new HashMap<>();

    public List<String> getApps() { return apps; }
    public void setApps(List<String> apps) { this.apps = apps; }

    public Map<String, String> getToken() { return token; }
    public void setToken(Map<String, String> token) { this.token = token; }
}
