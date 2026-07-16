package com.larkconnect.agent.agent;

public class QueryCancelledException extends RuntimeException {
    public QueryCancelledException(String requestId) {
        super("任务已终止：" + requestId);
    }
}
