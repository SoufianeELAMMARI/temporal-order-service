package com.acme.orders.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory stand-in for a real payment provider (Stripe, Adyen, CMI...).
 * Behaves like one: idempotency keys, declines, and occasional transient errors.
 *
 * Demo rules:
 *  - amount above 50,000            -> card declined (business error, not retried)
 *  - orderId containing "flaky"     -> first 2 authorize attempts throw a transient error (retried)
 */
@Component
public class PaymentGatewayClient {

    private static final Logger log = LoggerFactory.getLogger(PaymentGatewayClient.class);
    private static final BigDecimal DECLINE_ABOVE = new BigDecimal("50000");

    public record Authorization(String paymentId, BigDecimal amount) {
    }

    private final Map<String, Authorization> byIdempotencyKey = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> attempts = new ConcurrentHashMap<>();

    public Authorization authorize(String idempotencyKey, BigDecimal amount) {
        // Same key = same result: a retried call can never double-charge.
        Authorization existing = byIdempotencyKey.get(idempotencyKey);
        if (existing != null) {
            log.info("[payment] authorize {} replayed from idempotency key -> {}", idempotencyKey, existing.paymentId());
            return existing;
        }
        if (idempotencyKey.contains("flaky")
                && attempts.computeIfAbsent(idempotencyKey, k -> new AtomicInteger()).incrementAndGet() <= 2) {
            log.warn("[payment] authorize {} -> 503 Service Unavailable (simulated)", idempotencyKey);
            throw new IllegalStateException("Payment provider unavailable (HTTP 503)");
        }
        if (amount.compareTo(DECLINE_ABOVE) > 0) {
            log.warn("[payment] authorize {} -> declined, amount {}", idempotencyKey, amount);
            throw new CardDeclinedException("Card declined: amount " + amount + " exceeds card limit");
        }
        Authorization auth = new Authorization("pay-" + UUID.randomUUID().toString().substring(0, 8), amount);
        byIdempotencyKey.put(idempotencyKey, auth);
        log.info("[payment] authorize {} amount {} -> {}", idempotencyKey, amount, auth.paymentId());
        return auth;
    }

    public void capture(String paymentId, String idempotencyKey) {
        log.info("[payment] capture {} (key {})", paymentId, idempotencyKey);
    }

    public void refund(String paymentId, String idempotencyKey) {
        log.info("[payment] REFUND {} (key {})", paymentId, idempotencyKey);
    }
}
