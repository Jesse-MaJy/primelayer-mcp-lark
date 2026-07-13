package com.larkconnect.agent.admin;

import com.larkconnect.agent.ai.AiRuntimeConfigService;
import com.larkconnect.agent.audit.ChainTraceService;
import com.larkconnect.agent.feishu.AnswerFeedbackRepository;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminControllerFeedbackTest {
    @Test
    void returnsFeedbackDetailsForMessageRequest() {
        AnswerFeedbackRepository feedback = mock(AnswerFeedbackRepository.class);
        when(feedback.listDetails("req-1")).thenReturn(List.of(new AnswerFeedbackRepository.FeedbackDetail(
                "ou-1", "张三", "PROBLEM", "OTHER", "其他", "日期不对", Timestamp.valueOf("2026-07-13 12:00:00")
        )));
        AdminController controller = new AdminController(
                mock(AdminService.class), mock(AiRuntimeConfigService.class), mock(ChainTraceService.class), feedback);

        var response = controller.listMessageFeedback("req-1");

        assertEquals("张三", response.data().get(0).personName());
        assertEquals("其他", response.data().get(0).reasonLabel());
    }
}
