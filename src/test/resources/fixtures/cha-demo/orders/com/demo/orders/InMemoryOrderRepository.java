package com.demo.orders;

import org.springframework.stereotype.Repository;

// Seconda implementazione: CHA collega il service a ENTRAMBE, anche se a runtime
// ne viene iniettata una sola. Fan-out tipico dell'over-approssimazione.
@Repository
public class InMemoryOrderRepository implements OrderRepository {
    public void save(String id) {}
    public void delete(String id) {}
}
