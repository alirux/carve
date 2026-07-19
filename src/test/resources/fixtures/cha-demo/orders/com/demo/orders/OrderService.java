package com.demo.orders;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class OrderService {
    private OrderRepository repository;          // interfaccia -> CHA
    private com.demo.notify.NotificationGateway gateway;  // dipendenza reale cross-progetto

    public void placeOrder(String id) {
        repository.save(id);      // CHA: -> JdbcOrderRepository e InMemoryOrderRepository
        gateway.send(id);         // DIRETTO cross-progetto (orders -> notify)
    }

    public void cancelOrder(String id) {
        repository.delete(id);    // CHA
    }
}
