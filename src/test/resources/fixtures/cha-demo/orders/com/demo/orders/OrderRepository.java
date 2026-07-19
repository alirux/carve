package com.demo.orders;

public interface OrderRepository {
    void save(String id);
    void delete(String id);
}
