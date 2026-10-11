package com.acme.orders.workflow;

import com.acme.orders.activity.InventoryActivities;
import com.acme.orders.activity.NotificationActivities;
import com.acme.orders.activity.PaymentActivities;
import com.acme.orders.activity.ShippingActivities;
import com.acme.orders.domain.OrderLine;
import com.acme.orders.domain.OrderRequest;
import com.acme.orders.domain.OrderStage;
import com.acme.orders.domain.OrderStatus;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.TestWorkflowExtension;
import io.temporal.worker.Worker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * Pure workflow tests: in-memory Temporal test server with time skipping, mocked activities.
 * The 24-hour approval timer fires in milliseconds.
 */
class OrderWorkflowTest {

    @RegisterExtension
    static final TestWorkflowExtension testWorkflow = TestWorkflowExtension.newBuilder()
            .registerWorkflowImplementationTypes(OrderWorkflowImpl.class)
            .setDoNotStart(true) // register mocks first
            .build();

    PaymentActivities payments = mock(PaymentActivities.class, withSettings().withoutAnnotations());
    InventoryActivities inventory = mock(InventoryActivities.class, withSettings().withoutAnnotations());
    ShippingActivities shipping = mock(ShippingActivities.class, withSettings().withoutAnnotations());
    NotificationActivities notify = mock(NotificationActivities.class, withSettings().withoutAnnotations());

    OrderRequest bigOrder = new OrderRequest("42", "cust-7",
            List.of(new OrderLine("SKU-1", 3)), new BigDecimal("7500"), "1 Rue X, Casablanca");

    OrderRequest smallOrder = new OrderRequest("43", "cust-7",
            List.of(new OrderLine("SKU-2", 1)), new BigDecimal("120"), "1 Rue X, Casablanca");

    @BeforeEach
    void setUp(TestWorkflowEnvironment env, Worker worker) {
        when(payments.authorize(any(), any())).thenReturn("pay-1");
        when(inventory.reserve(any(), any())).thenReturn("res-1");
        when(shipping.createShipment(any(), any())).thenReturn("TRK-99");
        worker.registerActivitiesImplementations(payments, inventory, shipping, notify);
        env.start();
    }

    @Test
    void smallOrderShipsWithoutApproval(OrderWorkflow workflow) {
        OrderStatus result = workflow.processOrder(smallOrder);

        assertEquals(OrderStage.COMPLETED, result.stage());
        assertEquals("TRK-99", result.trackingNumber());
        assertNull(result.failureReason());
        verify(payments).capture("pay-1");
        verify(payments, never()).refund(anyString());
    }

    @Test
    void highValueOrderWaitsForApprovalThenShips(TestWorkflowEnvironment env, OrderWorkflow workflow) {
        WorkflowClient.start(workflow::processOrder, bigOrder);

        env.sleep(Duration.ofHours(2)); // instant
        assertEquals(OrderStage.AWAITING_APPROVAL, workflow.getStatus().stage());

        workflow.approve("alice");
        OrderStatus result = WorkflowStub.fromTyped(workflow).getResult(OrderStatus.class);

        assertEquals(OrderStage.COMPLETED, result.stage());
        assertEquals("TRK-99", result.trackingNumber());
        verify(payments).capture("pay-1");
    }

    @Test
    void missingApprovalCompensatesInReverseOrder(OrderWorkflow workflow) {
        // No signal: the 24h timer fires, time is skipped automatically
        OrderStatus result = workflow.processOrder(bigOrder);

        assertEquals(OrderStage.FAILED, result.stage());
        assertEquals("No approval within 24h", result.failureReason());
        InOrder order = inOrder(inventory, payments);
        order.verify(inventory).release("res-1");
        order.verify(payments).refund("pay-1");
        verify(shipping, never()).createShipment(any(), any());
    }

    @Test
    void customerCancelWhileAwaitingApproval(TestWorkflowEnvironment env, OrderWorkflow workflow) {
        WorkflowClient.start(workflow::processOrder, bigOrder);
        env.sleep(Duration.ofMinutes(10));

        workflow.cancel("changed my mind");
        OrderStatus result = WorkflowStub.fromTyped(workflow).getResult(OrderStatus.class);

        assertEquals(OrderStage.CANCELLED, result.stage());
        verify(inventory).release("res-1");
        verify(payments).refund("pay-1");
        verify(payments, never()).capture(anyString());
    }

    @Test
    void shippingFailureRefundsAndReleases(OrderWorkflow workflow) {
        when(shipping.createShipment(any(), any())).thenThrow(
                ApplicationFailure.newNonRetryableFailure("Carrier rejected address", "InvalidAddress"));

        OrderStatus result = workflow.processOrder(smallOrder);

        assertEquals(OrderStage.FAILED, result.stage());
        assertEquals("Carrier rejected address", result.failureReason());
        InOrder order = inOrder(inventory, payments);
        order.verify(inventory).release("res-1");
        order.verify(payments).refund("pay-1");
    }

    @Test
    void declinedPaymentHasNothingToCompensate(OrderWorkflow workflow) {
        when(payments.authorize(any(), any())).thenThrow(
                ApplicationFailure.newNonRetryableFailure("Card declined", "PaymentDeclined"));

        OrderStatus result = workflow.processOrder(smallOrder);

        assertEquals(OrderStage.FAILED, result.stage());
        verify(inventory, never()).reserve(any(), any());
        verify(payments, never()).refund(anyString());
    }
}
