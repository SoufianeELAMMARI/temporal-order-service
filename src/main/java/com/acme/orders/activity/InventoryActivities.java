package com.acme.orders.activity;

import com.acme.orders.domain.OrderLine;
import io.temporal.activity.ActivityInterface;

import java.util.List;

@ActivityInterface
public interface InventoryActivities {

    /** @return the reservation id */
    String reserve(String orderId, List<OrderLine> lines);

    /** Compensation for reserve. */
    void release(String reservationId);
}
