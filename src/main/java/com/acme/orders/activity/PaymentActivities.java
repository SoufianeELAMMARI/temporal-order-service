package com.acme.orders.activity;

import io.temporal.activity.ActivityInterface;

import java.math.BigDecimal;

@ActivityInterface
public interface PaymentActivities {

    /** @return the payment id */
    String authorize(String orderId, BigDecimal amount);

    void capture(String paymentId);

    /** Compensation for authorize/capture. */
    void refund(String paymentId);
}
