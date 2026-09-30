package com.oms.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * Stock is split in two, not tracked as a single number.
 *
 * <p>{@code availableQty} is what can still be promised; {@code reservedQty} is what is spoken for
 * by an in-flight saga. Keeping them apart is what lets a reservation be undone exactly, and makes
 * the invariant checkable: the pair must always sum to the physical count.
 */
@Entity
@Table(name = "inventory_item")
public class InventoryItem {

    @Id
    @Column(name = "product_id", nullable = false, length = 64)
    private String productId;

    @Column(name = "available_qty", nullable = false)
    private int availableQty;

    @Column(name = "reserved_qty", nullable = false)
    private int reservedQty;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected InventoryItem() {
    }

    public InventoryItem(String productId, int availableQty) {
        this.productId = productId;
        this.availableQty = availableQty;
        this.reservedQty = 0;
    }

    public void reserve(int quantity) {
        if (quantity > availableQty) {
            // Callers check availability first, so reaching this means the check and the write
            // disagreed - a bug, not a business outcome.
            throw new IllegalStateException(
                    "Cannot reserve " + quantity + " of " + productId + "; only " + availableQty + " available");
        }
        availableQty -= quantity;
        reservedQty += quantity;
    }

    /** Clamped rather than validated: a compensation must never fail because it ran twice. */
    public void release(int quantity) {
        int releasable = Math.min(quantity, reservedQty);
        reservedQty -= releasable;
        availableQty += releasable;
    }

    public void restock(int newAvailableQty) {
        this.availableQty = newAvailableQty;
    }

    public int totalQty() {
        return availableQty + reservedQty;
    }

    public String getProductId() {
        return productId;
    }

    public int getAvailableQty() {
        return availableQty;
    }

    public int getReservedQty() {
        return reservedQty;
    }

    public Long getVersion() {
        return version;
    }
}