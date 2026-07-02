package com.larkconnect.agent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AgentServiceDtosTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldDeserializeOldToolCallWithoutNewFields() throws Exception {
        String oldJson = """
            {
                "toolName": "query_form_data_list",
                "arguments": {"formId": "test"},
                "projectIds": ["roche"],
                "reason": "test reason"
            }
            """;

        AgentServiceDtos.ToolCall tc = objectMapper.readValue(oldJson, AgentServiceDtos.ToolCall.class);

        assertEquals("query_form_data_list", tc.toolName());
        assertEquals("test", tc.arguments().get("formId"));
        assertEquals(1, tc.projectIds().size());
        assertEquals("roche", tc.projectIds().get(0));
        assertEquals("test reason", tc.reason());
        // New fields should be null when not present
        assertNull(tc.pagination());
        assertNull(tc.purpose());
    }

    @Test
    void shouldDeserializeNewToolCallWithPaginationAndPurpose() throws Exception {
        String newJson = """
            {
                "toolName": "query_form_data_list",
                "arguments": {"formId": "test", "page": 1, "pageSize": 100},
                "projectIds": ["roche"],
                "reason": "质量域核心表单查询",
                "pagination": {
                    "mode": "auto",
                    "pageSize": 100,
                    "maxPages": 50,
                    "maxItems": 5000
                },
                "purpose": "查询质量缺陷清单在上个月的数据"
            }
            """;

        AgentServiceDtos.ToolCall tc = objectMapper.readValue(newJson, AgentServiceDtos.ToolCall.class);

        assertEquals("query_form_data_list", tc.toolName());
        assertNotNull(tc.pagination());
        assertEquals("auto", tc.pagination().get("mode"));
        assertEquals(100, tc.pagination().get("pageSize"));
        assertNotNull(tc.purpose());
        assertEquals("查询质量缺陷清单在上个月的数据", tc.purpose());
    }

    @Test
    void shouldDeserializeToolCallWithOnlyPagination() throws Exception {
        String json = """
            {
                "toolName": "query_form_data_list",
                "arguments": {},
                "projectIds": [],
                "reason": null,
                "pagination": {"mode": "auto"}
            }
            """;

        AgentServiceDtos.ToolCall tc = objectMapper.readValue(json, AgentServiceDtos.ToolCall.class);

        assertNotNull(tc.pagination());
        assertEquals("auto", tc.pagination().get("mode"));
        assertNull(tc.purpose());
    }

    @Test
    void shouldDeserializeToolCallWithOnlyPurpose() throws Exception {
        String json = """
            {
                "toolName": "match_form_resource",
                "arguments": {},
                "projectIds": [],
                "reason": null,
                "purpose": "匹配质量相关表单"
            }
            """;

        AgentServiceDtos.ToolCall tc = objectMapper.readValue(json, AgentServiceDtos.ToolCall.class);

        assertNull(tc.pagination());
        assertEquals("匹配质量相关表单", tc.purpose());
    }
}
