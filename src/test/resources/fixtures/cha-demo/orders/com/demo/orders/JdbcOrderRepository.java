package com.demo.orders;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcOrderRepository implements OrderRepository {
    private JdbcTemplate jdbcTemplate;

    public void save(String id) {
        jdbcTemplate.update("insert into orders values (?)", id);
    }

    public void delete(String id) {
        jdbcTemplate.update("delete from orders where id = ?", id);
    }
}
