package com.example.service;

import com.example.repo.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

@Service
public class OrderService {

    private final OrderRepository repository;
    private final RestTemplate restTemplate = new RestTemplate();

    public OrderService(OrderRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void checkout(String orderId) {
        repository.save(orderId);
        // External HTTP call inside an open transaction — a transaction risk.
        restTemplate.getForObject("http://payments/charge", String.class);
    }
}
