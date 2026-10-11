package com.acme.orders.activity;

import com.acme.orders.workflow.OrderWorkflow;
import io.temporal.failure.ApplicationFailure;
import io.temporal.spring.boot.ActivityImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Simulated carrier. Demo rule: an address containing "INVALID" is rejected.
 */
@Component
@ActivityImpl(taskQueues = OrderWorkflow.TASK_QUEUE)
public class ShippingActivitiesImpl implements ShippingActivities {

    private static final Logger log = LoggerFactory.getLogger(ShippingActivitiesImpl.class);

    @Override
    public String createShipment(String orderId, String address) {
        if (address == null || address.toUpperCase().contains("INVALID")) {
            throw ApplicationFailure.newNonRetryableFailure(
                    "Carrier rejected address: " + address, "InvalidAddress");
        }
        String tracking = "TRK-" + Math.abs(orderId.hashCode());
        log.info("[shipping] shipment for order {} to '{}' -> {}", orderId, address, tracking);
        return tracking;
    }
}
