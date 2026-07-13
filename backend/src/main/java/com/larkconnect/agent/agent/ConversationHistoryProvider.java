package com.larkconnect.agent.agent;

import java.util.List;

public interface ConversationHistoryProvider {
    List<ConversationHistoryService.HistoryTurn> load(String chatType, String openId, String chatId, String currentRequestId);
}
