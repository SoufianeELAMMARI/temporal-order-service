package com.acme.orders.api;

import com.acme.orders.domain.OrderRequest;
import com.acme.orders.domain.OrderStatus;
import com.acme.orders.workflow.OrderWorkflow;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowOptions;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/orders")
public class OrderController {

    private final WorkflowClient client;

    public OrderController(WorkflowClient client) {
        this.client = client;
    }

    @PostMapping
    public ResponseEntity<Map<String, String>> place(@RequestBody OrderRequest req) {
        String workflowId = workflowId(req.orderId());
        OrderWorkflow wf = client.newWorkflowStub(OrderWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setTaskQueue(OrderWorkflow.TASK_QUEUE)
                        .setWorkflowId(workflowId) // business key = natural dedup
                        .build());
        try {
            WorkflowClient.start(wf::processOrder, req); // async, returns immediately
            return ResponseEntity.accepted().body(Map.of("workflowId", workflowId));
        } catch (WorkflowExecutionAlreadyStarted e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "Order already submitted", "workflowId", workflowId));
        }
    }

    @GetMapping("/{id}")
    public OrderStatus status(@PathVariable String id) {
        return stub(id).getStatus();       // query
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<Void> approve(@PathVariable String id, @RequestParam String by) {
        stub(id).approve(by);              // signal
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<Void> cancel(@PathVariable String id, @RequestParam String reason) {
        stub(id).cancel(reason);           // signal
        return ResponseEntity.accepted().build();
    }

    @ExceptionHandler(WorkflowNotFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(WorkflowNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Order not found"));
    }

    private OrderWorkflow stub(String id) {
        return client.newWorkflowStub(OrderWorkflow.class, workflowId(id));
    }

    private static String workflowId(String orderId) {
        return "order-" + orderId;
    }
}
