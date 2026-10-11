package com.acme.orders.activity;

import com.acme.orders.domain.OrderLine;
import com.acme.orders.workflow.OrderWorkflow;
import io.temporal.failure.ApplicationFailure;
import io.temporal.spring.boot.ActivityImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simulated warehouse. Demo rule: any SKU starting with "OUT-" is out of stock.
 */
@Component
@ActivityImpl(taskQueues = OrderWorkflow.TASK_QUEUE)
public class InventoryActivitiesImpl implements InventoryActivities {

    private static final Logger log = LoggerFactory.getLogger(InventoryActivitiesImpl.class);

    private final Map<String, List<OrderLine>> reservations = new ConcurrentHashMap<>();

    @Override
    public String reserve(String orderId, List<OrderLine> lines) {
        for (OrderLine line : lines) {
            if (line.sku().startsWith("OUT-")) {
                throw ApplicationFailure.newNonRetryableFailure(
                        "SKU " + line.sku() + " is out of stock", "OutOfStock");
            }
        }
        String reservationId = "res-" + orderId; // deterministic id = idempotent upsert
        reservations.put(reservationId, lines);
        log.info("[inventory] reserved {} for order {}: {}", reservationId, orderId, lines);
        return reservationId;
    }

    @Override
    public void release(String reservationId) {
        reservations.remove(reservationId);
        log.info("[inventory] RELEASED {}", reservationId);
    }
}
