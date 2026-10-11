package com.acme.orders.activity;

import com.acme.orders.gateway.CardDeclinedException;
import com.acme.orders.gateway.PaymentGatewayClient;
import com.acme.orders.workflow.OrderWorkflow;
import io.temporal.failure.ApplicationFailure;
import io.temporal.spring.boot.ActivityImpl;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
@ActivityImpl(taskQueues = OrderWorkflow.TASK_QUEUE)
public class PaymentActivitiesImpl implements PaymentActivities {

    private final PaymentGatewayClient gateway;

    public PaymentActivitiesImpl(PaymentGatewayClient gateway) {
        this.gateway = gateway;
    }

    @Override
    public String authorize(String orderId, BigDecimal amount) {
        try {
            // Idempotency key = retries never double-charge
            return gateway.authorize("auth-" + orderId, amount).paymentId();
        } catch (CardDeclinedException e) {
            // A business error: retrying will not help, so fail fast
            throw ApplicationFailure.newNonRetryableFailure(e.getMessage(), "PaymentDeclined");
        }
        // Timeouts and 5xx simply propagate and are retried with backoff
    }

    @Override
    public void capture(String paymentId) {
        gateway.capture(paymentId, "capture-" + paymentId);
    }

    @Override
    public void refund(String paymentId) {
        gateway.refund(paymentId, "refund-" + paymentId);
    }
}
