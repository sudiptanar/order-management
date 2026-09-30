package com.oms.order.ops;

public record ReplayResult(String fromTopic, String toTopic, int replayed, String note) {
}