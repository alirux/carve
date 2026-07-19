package com.demo.notify;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
public class NotificationGateway {
    private RestTemplate restTemplate;

    public void send(String payload) {
        // Chiamata HTTP raggiunta da OrderService.@Transactional -> rischio transazionale
        restTemplate.postForObject("http://notify.internal/api", payload, String.class);
    }
}
