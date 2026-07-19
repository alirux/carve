package com.demo.orders;

import org.springframework.web.bind.annotation.RestController;

@RestController
public class OrderController {
    private OrderService orderService;

    public void place(String id) {
        orderService.placeOrder(id);   // DIRETTO: controller -> service (classe concreta)
    }

    public void cancel(String id) {
        orderService.cancelOrder(id);  // DIRETTO
    }
}
