package com.acme.orders.activity;

import io.temporal.activity.ActivityInterface;

@ActivityInterface
public interface NotificationActivities {

    void notifyCustomer(String customerId, String message);
}
