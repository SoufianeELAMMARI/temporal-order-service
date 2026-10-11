package com.acme.orders.activity;

import com.acme.orders.workflow.OrderWorkflow;
import io.temporal.spring.boot.ActivityImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Simulated email/SMS sender: logs the message.
 */
@Component
@ActivityImpl(taskQueues = OrderWorkflow.TASK_QUEUE)
public class NotificationActivitiesImpl implements NotificationActivities {

    private static final Logger log = LoggerFactory.getLogger(NotificationActivitiesImpl.class);

    @Override
    public void notifyCustomer(String customerId, String message) {
        log.info("[notify] to {}: {}", customerId, message);
    }
}
