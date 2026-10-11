package com.acme.orders.workflow;

import com.acme.orders.domain.OrderRequest;
import com.acme.orders.domain.OrderStatus;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface OrderWorkflow {

    String TASK_QUEUE = "ORDER_TASK_QUEUE";

    @WorkflowMethod
    OrderStatus processOrder(OrderRequest request);

    @SignalMethod
    void approve(String approvedBy);

    @SignalMethod
    void cancel(String reason);

    @QueryMethod
    OrderStatus getStatus();
}
