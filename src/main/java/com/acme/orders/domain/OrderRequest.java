package com.acme.orders.domain;

import java.math.BigDecimal;
import java.util.List;

public record OrderRequest(
        String orderId,
        String customerId,
        List<OrderLine> lines,
        BigDecimal total,
        String shippingAddress) {
}
