package com.acme.orders.activity;

import io.temporal.activity.ActivityInterface;

@ActivityInterface
public interface ShippingActivities {

    /** @return the tracking number */
    String createShipment(String orderId, String address);
}
