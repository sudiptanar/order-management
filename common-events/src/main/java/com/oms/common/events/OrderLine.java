package com.oms.common.events;

import java.math.BigDecimal;

public record OrderLine(String productId, int quantity, BigDecimal unitPrice) {

    public BigDecimal lineTotal() {
        return unitPrice.multiply(BigDecimal.valueOf(quantity));
    }
}