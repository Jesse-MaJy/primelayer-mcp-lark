package com.larkconnect.agent.agent;

import java.time.Instant;

public record DeliveryOutboxEntry(long id, String requestId, DeliveryType deliveryType,
                                  String payloadJson, String status, int attempts,
                                  Instant nextAttemptAt, Instant leaseUntil,
                                  String feishuMessageId, String lastError) {}
