package com.oms.order.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;

public record CreateOrderRequest(

        @NotBlank(message = "customerId is required")
        String customerId,

        @NotEmpty(message = "at least one item is required")
        @Valid
        List<Line> items,

        /** Test hook: fail the saga's final local step so the compensation chain runs. */
        boolean simulateConfirmFailure) {

    public record Line(
            @NotBlank(message = "productId is required")
            String productId,

            @Min(value = 1, message = "quantity must be at least 1")
            int quantity,

            @NotNull(message = "unitPrice is required")
            @DecimalMin(value = "0.0", message = "unitPrice cannot be negative")
            BigDecimal unitPrice) {
    }
}