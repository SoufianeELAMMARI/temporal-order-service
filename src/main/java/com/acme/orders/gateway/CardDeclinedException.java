package com.acme.orders.gateway;

public class CardDeclinedException extends RuntimeException {
    public CardDeclinedException(String message) {
        super(message);
    }
}
