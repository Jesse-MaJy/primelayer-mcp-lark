package com.larkconnect.agent.feishu;

public record FeishuIncomingMessage(
        String messageId,
        String openId,
        String chatId,
        String chatType,
        String text
) {}
