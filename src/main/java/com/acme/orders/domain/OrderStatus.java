package com.acme.orders.domain;

public record OrderStatus(
        String orderId,
        OrderStage stage,
        String trackingNumber,
        String failureReason) {
}
