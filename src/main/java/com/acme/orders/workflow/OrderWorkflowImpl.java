package com.acme.orders.workflow;

import com.acme.orders.activity.InventoryActivities;
import com.acme.orders.activity.NotificationActivities;
import com.acme.orders.activity.PaymentActivities;
import com.acme.orders.activity.ShippingActivities;
import com.acme.orders.domain.OrderRequest;
import com.acme.orders.domain.OrderStage;
import com.acme.orders.domain.OrderStatus;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.failure.TemporalFailure;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Saga;
import io.temporal.workflow.Workflow;
import org.slf4j.Logger;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * The order-fulfilment saga. Deterministic code only: every side effect lives in an activity.
 */
@WorkflowImpl(taskQueues = OrderWorkflow.TASK_QUEUE)
public class OrderWorkflowImpl implements OrderWorkflow {

    private static final Logger log = Workflow.getLogger(OrderWorkflowImpl.class);

    static final BigDecimal APPROVAL_THRESHOLD = new BigDecimal("5000");
    static final Duration APPROVAL_TIMEOUT = Duration.ofHours(24);

    private final ActivityOptions defaults = ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(30))
            .setRetryOptions(RetryOptions.newBuilder()
                    .setInitialInterval(Duration.ofSeconds(1))
                    .setBackoffCoefficient(2.0)
                    .setMaximumInterval(Duration.ofMinutes(1))
                    .setMaximumAttempts(10)
                    .build())
            .build();

    private final PaymentActivities payments =
            Workflow.newActivityStub(PaymentActivities.class, defaults);
    private final InventoryActivities inventory =
            Workflow.newActivityStub(InventoryActivities.class, defaults);
    private final ShippingActivities shipping =
            Workflow.newActivityStub(ShippingActivities.class, defaults);
    private final NotificationActivities notifications =
            Workflow.newActivityStub(NotificationActivities.class, defaults);

    // Workflow state: survives crashes because it is rebuilt by replay
    private String orderId;
    private OrderStage stage = OrderStage.RECEIVED;
    private String trackingNumber;
    private String failureReason;
    private String approvedBy;
    private String cancelReason;

    @Override
    public OrderStatus processOrder(OrderRequest req) {
        this.orderId = req.orderId();
        Saga saga = new Saga(new Saga.Options.Builder().build());

        try {
            String paymentId = payments.authorize(req.orderId(), req.total());
            saga.addCompensation(payments::refund, paymentId);
            stage = OrderStage.PAYMENT_AUTHORIZED;
            failIfCancelled();

            String reservationId = inventory.reserve(req.orderId(), req.lines());
            saga.addCompensation(inventory::release, reservationId);
            stage = OrderStage.INVENTORY_RESERVED;
            failIfCancelled();

            if (req.total().compareTo(APPROVAL_THRESHOLD) > 0) {
                stage = OrderStage.AWAITING_APPROVAL;
                // A durable timer: the worker can restart many times
                // during these 24 hours and nothing is lost.
                boolean decided = Workflow.await(APPROVAL_TIMEOUT,
                        () -> approvedBy != null || cancelReason != null);
                if (!decided) {
                    throw ApplicationFailure.newNonRetryableFailure(
                            "No approval within 24h", "ApprovalTimeout");
                }
                failIfCancelled();
                log.info("Order {} approved by {}", orderId, approvedBy);
            }

            payments.capture(paymentId);
            trackingNumber = shipping.createShipment(req.orderId(), req.shippingAddress());
            stage = OrderStage.SHIPPED;

            notifications.notifyCustomer(req.customerId(),
                    "Order " + orderId + " shipped. Tracking: " + trackingNumber);
            stage = OrderStage.COMPLETED;

        } catch (TemporalFailure e) {
            failureReason = rootMessage(e);
            stage = cancelReason != null ? OrderStage.CANCELLED : OrderStage.FAILED;
            log.warn("Order {} {} ({}): compensating", orderId, stage, failureReason);

            // Detached scope: compensation still runs even if the
            // workflow itself is being cancelled.
            Workflow.newDetachedCancellationScope(saga::compensate).run();
            notifications.notifyCustomer(req.customerId(),
                    "Order " + orderId + " was " + stage.name().toLowerCase()
                            + ". Any payment has been refunded.");
        }
        return getStatus();
    }

    private void failIfCancelled() {
        if (cancelReason != null) {
            throw ApplicationFailure.newNonRetryableFailure(
                    "Cancelled by customer: " + cancelReason, "OrderCancelled");
        }
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t instanceof ApplicationFailure af ? af.getOriginalMessage() : t.getMessage();
    }

    @Override
    public void approve(String approvedBy) {
        this.approvedBy = approvedBy;
    }

    @Override
    public void cancel(String reason) {
        if (stage == OrderStage.SHIPPED || stage == OrderStage.COMPLETED) {
            return; // too late, the parcel is on its way
        }
        this.cancelReason = reason;
    }

    @Override
    public OrderStatus getStatus() {
        return new OrderStatus(orderId, stage, trackingNumber, failureReason);
    }
}
