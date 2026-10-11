package com.acme.orders.domain;

public enum OrderStage {
    RECEIVED,
    PAYMENT_AUTHORIZED,
    INVENTORY_RESERVED,
    AWAITING_APPROVAL,
    SHIPPED,
    COMPLETED,
    CANCELLED,
    FAILED
}
