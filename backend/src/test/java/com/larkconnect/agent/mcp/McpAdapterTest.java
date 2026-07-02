package com.larkconnect.agent.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.larkconnect.agent.config.AppProperties;
import com.larkconnect.agent.mcp.McpAdapter.PageData;
import com.larkconnect.agent.mcp.McpAdapter.PaginationResult;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

class McpAdapterTest {

    private static AppProperties testProperties() {
        return new AppProperties(
                new AppProperties.Admin(3600, "admin", "admin"),
                new AppProperties.Agent(5, 30000, 30000),
                new AppProperties.AgentService(true, "http://localhost:8090"),
                new AppProperties.Feishu("", "", "", "", false),
                new AppProperties.DeepSeek("https://api.deepseek.com", "key", "deepseek-chat"),
                new AppProperties.Mcp("http://localhost/mcp", "X-API-Key")
        );
    }

    private final McpAdapter adapter = new McpAdapter(testProperties(), RestClient.builder(), new ObjectMapper());

    // ============================================================
    // extractTotalCount tests
    // ============================================================

    @Test
    void shouldExtractTotalCountFromTotalCountField() {
        Map<String, Object> response = Map.of("result", Map.of("totalCount", 353, "items", List.of()));
        assertEquals(353, adapter.extractTotalCount(response));
    }

    @Test
    void shouldExtractTotalCountFromTotalField() {
        Map<String, Object> response = Map.of("result", Map.of("total", 200, "data", List.of()));
        assertEquals(200, adapter.extractTotalCount(response));
    }

    @Test
    void shouldExtractTotalCountFromCountField() {
        Map<String, Object> response = Map.of("result", Map.of("count", 50));
        assertEquals(50, adapter.extractTotalCount(response));
    }

    @Test
    void shouldEstimateTotalCountFromTotalPages() {
        Map<String, Object> response = Map.of("result", Map.of("totalPages", 5, "pageSize", 100, "items", List.of(Map.of("id", 1))));
        assertEquals(500, adapter.extractTotalCount(response));
    }

    @Test
    void shouldEstimateTotalCountFromTotalPagesWithDefaultPageSize() {
        Map<String, Object> response = Map.of("result", Map.of("totalPages", 3, "items", List.of(Map.of("id", 1))));
        assertEquals(300, adapter.extractTotalCount(response));
    }

    @Test
    void shouldFallbackToItemsSize() {
        List<Map<String, String>> items = List.of(Map.of("id", "1"), Map.of("id", "2"), Map.of("id", "3"));
        Map<String, Object> response = Map.of("result", Map.of("items", items));
        assertEquals(3, adapter.extractTotalCount(response));
    }

    @Test
    void shouldFallbackToDataSize() {
        List<Map<String, String>> data = List.of(Map.of("id", "1"), Map.of("id", "2"));
        Map<String, Object> response = Map.of("result", Map.of("data", data));
        assertEquals(2, adapter.extractTotalCount(response));
    }

    @Test
    void shouldReturnZeroWhenNoTotalFieldFound() {
        Map<String, Object> response = Map.of("result", Map.of("message", "ok"));
        assertEquals(0, adapter.extractTotalCount(response));
    }

    @Test
    void shouldHandleResponseWithoutResultWrapper() {
        Map<String, Object> response = Map.of("totalCount", 100);
        assertEquals(100, adapter.extractTotalCount(response));
    }

    @Test
    void shouldHandleTotalCountAsString() {
        Map<String, Object> response = Map.of("result", Map.of("totalCount", "353"));
        assertEquals(353, adapter.extractTotalCount(response));
    }

    // ============================================================
    // extractItems tests
    // ============================================================

    @Test
    void shouldExtractItemsFromItemsArray() {
        List<Map<String, String>> items = List.of(Map.of("id", "1"), Map.of("id", "2"));
        Map<String, Object> response = Map.of("result", Map.of("items", items));
        List<?> extracted = adapter.extractItems(response);
        assertThat(extracted).hasSize(2);
    }

    @Test
    void shouldExtractItemsFromDataArray() {
        List<Map<String, String>> data = List.of(Map.of("id", "1"), Map.of("id", "2"), Map.of("id", "3"));
        Map<String, Object> response = Map.of("result", Map.of("data", data));
        List<?> extracted = adapter.extractItems(response);
        assertThat(extracted).hasSize(3);
    }

    @Test
    void shouldExtractItemsFromNestedDataItems() {
        List<Map<String, String>> items = List.of(Map.of("id", "1"));
        Map<String, Object> response = Map.of("result", Map.of("data", Map.of("items", items)));
        List<?> extracted = adapter.extractItems(response);
        assertThat(extracted).hasSize(1);
    }

    @Test
    void shouldExtractItemsFromRecordsArray() {
        List<Map<String, String>> records = List.of(Map.of("id", "1"), Map.of("id", "2"));
        Map<String, Object> response = Map.of("result", Map.of("records", records));
        List<?> extracted = adapter.extractItems(response);
        assertThat(extracted).hasSize(2);
    }

    @Test
    void shouldExtractItemsFromListArray() {
        List<Map<String, String>> list = List.of(Map.of("id", "1"), Map.of("id", "2"), Map.of("id", "3"), Map.of("id", "4"));
        Map<String, Object> response = Map.of("result", Map.of("list", list));
        List<?> extracted = adapter.extractItems(response);
        assertThat(extracted).hasSize(4);
    }

    @Test
    void shouldReturnNullWhenNoItemsFound() {
        Map<String, Object> response = Map.of("result", Map.of("message", "ok"));
        List<?> extracted = adapter.extractItems(response);
        assertThat(extracted).isNull();
    }

    @Test
    void shouldHandleResponseWithoutResultWrapperForItems() {
        List<Map<String, String>> items = List.of(Map.of("id", "1"));
        Map<String, Object> response = Map.of("items", items);
        List<?> extracted = adapter.extractItems(response);
        assertThat(extracted).hasSize(1);
    }

    // ============================================================
    // callToolWithPagination tests
    // ============================================================

    @Test
    void shouldUsePagePageSizeStyleWhenArgumentsHavePage() {
        PaginationTestAdapter testAdapter = new PaginationTestAdapter(List.of(
                singlePageResponse(1, 10)
        ));

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("page", 1);
        args.put("pageSize", 10);
        args.put("formId", "test-form");

        testAdapter.callToolWithPagination("token", "query_data", args, 10);

        Map<String, Object> receivedArgs = testAdapter.receivedArgs.get(0);
        assertThat(receivedArgs).containsEntry("page", 1);
        assertThat(receivedArgs).containsEntry("pageSize", 10);
        assertThat(receivedArgs).containsEntry("formId", "test-form");
    }

    @Test
    void shouldUseOffsetLimitStyleWhenArgumentsHaveOffset() {
        PaginationTestAdapter testAdapter = new PaginationTestAdapter(List.of(
                singlePageResponse(1, 10)
        ));

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("offset", 0);
        args.put("limit", 10);
        args.put("formId", "test-form");

        testAdapter.callToolWithPagination("token", "query_data", args, 10);

        Map<String, Object> receivedArgs = testAdapter.receivedArgs.get(0);
        assertThat(receivedArgs).containsEntry("offset", 0);
        assertThat(receivedArgs).containsEntry("limit", 10);
        assertThat(receivedArgs).containsEntry("formId", "test-form");
    }

    @Test
    void shouldDefaultToPagePageSizeStyle() {
        PaginationTestAdapter testAdapter = new PaginationTestAdapter(List.of(
                singlePageResponse(1, 10)
        ));

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("formId", "test-form");

        testAdapter.callToolWithPagination("token", "query_data", args, 10);

        Map<String, Object> receivedArgs = testAdapter.receivedArgs.get(0);
        assertThat(receivedArgs).containsEntry("page", 1);
        assertThat(receivedArgs).containsEntry("pageSize", 10);
    }

    @Test
    void shouldIterateMultiplePages() {
        PaginationTestAdapter testAdapter = new PaginationTestAdapter(List.of(
                paginatedResponse(List.of(item(1), item(2), item(3)), 10, 3),
                paginatedResponse(List.of(item(4), item(5), item(6)), 10, 3),
                paginatedResponse(List.of(), 10, 3)
        ));

        PaginationResult result = testAdapter.callToolWithPagination("token", "query_data", Map.of(), 3);

        assertThat(result.pages()).hasSize(3);
        assertThat(result.successPages()).isEqualTo(3);
        assertThat(result.failedPages()).isEqualTo(0);
    }

    @Test
    void shouldStopWhenItemsListIsEmpty() {
        PaginationTestAdapter testAdapter = new PaginationTestAdapter(List.of(
                fullPageResponse(List.of(item(1), item(2), item(3)), 100),
                paginatedResponse(List.of(), 100, 0)
        ));

        PaginationResult result = testAdapter.callToolWithPagination("token", "query_data", Map.of(), 3);

        assertThat(result.pages()).hasSize(2);
        assertThat(result.successPages()).isEqualTo(2);
    }

    @Test
    void shouldStopWhenItemsSizeLessThanPageSize() {
        PaginationTestAdapter testAdapter = new PaginationTestAdapter(List.of(
                fullPageResponse(List.of(item(1), item(2), item(3), item(4), item(5)), 100),
                paginatedResponse(List.of(item(6)), 100, 0)
        ));

        PaginationResult result = testAdapter.callToolWithPagination("token", "query_data", Map.of(), 5);

        assertThat(result.pages()).hasSize(2);
        assertThat(result.successPages()).isEqualTo(2);
    }

    @Test
    void shouldStopAtMaxPages() {
        // Return responses for 50+ pages (up to MAX_PAGES)
        List<Map<String, Object>> responses = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            responses.add(paginatedResponse(List.of(item(i * 2), item(i * 2 + 1)), 100, 2));
        }
        PaginationTestAdapter testAdapter = new PaginationTestAdapter(responses);

        PaginationResult result = testAdapter.callToolWithPagination("token", "query_data", Map.of(), 2);

        // MAX_PAGES is 50
        assertThat(result.pages()).hasSize(50);
    }

    @Test
    void shouldReturnPartialResultsWhenFirstPageFails() {
        PaginationTestAdapter testAdapter = new PaginationTestAdapter(
                List.of(),
                new IllegalStateException("MCP connection error")
        );

        PaginationResult result = testAdapter.callToolWithPagination("token", "query_data", Map.of(), 100);

        assertThat(result.pages()).hasSize(1);
        assertThat(result.failedPages()).isEqualTo(1);
        assertThat(result.successPages()).isEqualTo(0);
        assertThat(result.totalCount()).isZero();
    }

    @Test
    void shouldReturnIncrementedPageNumbersInPagePageSizeStyle() {
        PaginationTestAdapter testAdapter = new PaginationTestAdapter(List.of(
                paginatedResponse(List.of(item(1), item(2)), 100, 2),
                paginatedResponse(List.of(item(3), item(4)), 100, 2),
                paginatedResponse(List.of(), 100, 2)
        ));

        testAdapter.callToolWithPagination("token", "query_data", Map.of(), 2);

        assertThat(testAdapter.receivedArgs.get(0)).containsEntry("page", 1);
        assertThat(testAdapter.receivedArgs.get(1)).containsEntry("page", 2);
        assertThat(testAdapter.receivedArgs.get(2)).containsEntry("page", 3);
    }

    @Test
    void shouldReturnIncrementedOffsetsInOffsetLimitStyle() {
        PaginationTestAdapter testAdapter = new PaginationTestAdapter(List.of(
                paginatedResponse(List.of(item(1), item(2)), 100, 2),
                paginatedResponse(List.of(item(3), item(4)), 100, 2),
                paginatedResponse(List.of(), 100, 2)
        ));

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("offset", 0);
        args.put("limit", 2);

        testAdapter.callToolWithPagination("token", "query_data", args, 2);

        assertThat(testAdapter.receivedArgs.get(0)).containsEntry("offset", 0);
        assertThat(testAdapter.receivedArgs.get(1)).containsEntry("offset", 2);
        assertThat(testAdapter.receivedArgs.get(2)).containsEntry("offset", 4);
    }

    @Test
    void shouldReturnCorrectPaginatedResultMetadata() {
        PaginationTestAdapter testAdapter = new PaginationTestAdapter(List.of(
                paginatedResponse(List.of(item(1), item(2), item(3)), 10, 3),
                paginatedResponse(List.of(item(4), item(5), item(6)), 10, 3),
                paginatedResponse(List.of(item(7)), 10, 3)
        ));

        PaginationResult result = testAdapter.callToolWithPagination("token", "query_data", Map.of(), 3);

        assertThat(result.totalCount()).isEqualTo(10);
        assertThat(result.pages()).hasSize(3);
        assertThat(result.successPages()).isEqualTo(3);
        assertThat(result.failedPages()).isEqualTo(0);
        // totalPages reflects actual pages fetched
        assertThat(result.totalPages()).isEqualTo(3);
    }

    @Test
    void shouldHandleResponseWithDataItemsNestedFormat() {
        PaginationTestAdapter testAdapter = new PaginationTestAdapter(List.of(
                nestedDataItemsResponse(List.of(item(1), item(2)), 5),
                nestedDataItemsResponse(List.of(item(3), item(4)), 5),
                nestedDataItemsResponse(List.of(item(5)), 5)
        ));

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("page", 1);
        args.put("pageSize", 2);

        PaginationResult result = testAdapter.callToolWithPagination("token", "query_data", args, 2);

        assertThat(result.pages()).hasSize(3);
        assertThat(result.successPages()).isEqualTo(3);
        assertThat(result.totalCount()).isEqualTo(5);
    }

    @Test
    void shouldHandleResponseWithRecordsFormat() {
        PaginationTestAdapter testAdapter = new PaginationTestAdapter(List.of(
                recordsResponse(List.of(item(1), item(2)), 2)
        ));

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("page", 1);
        args.put("pageSize", 100);

        PaginationResult result = testAdapter.callToolWithPagination("token", "query_data", args, 100);

        assertThat(result.pages()).hasSize(1);
        assertThat(result.successPages()).isEqualTo(1);
    }

    @Test
    void shouldHandleResponseWithListFormat() {
        PaginationTestAdapter testAdapter = new PaginationTestAdapter(List.of(
                listResponse(List.of(item(1), item(2)), 2)
        ));

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("page", 1);
        args.put("pageSize", 100);

        PaginationResult result = testAdapter.callToolWithPagination("token", "query_data", args, 100);

        assertThat(result.pages()).hasSize(1);
        assertThat(result.successPages()).isEqualTo(1);
    }

    @Test
    void shouldDetectPagePageSizeStyleWhenBothPresent() {
        PaginationTestAdapter testAdapter = new PaginationTestAdapter(List.of(
                singlePageResponse(1, 10)
        ));

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("page", 2);
        args.put("pageSize", 20);

        testAdapter.callToolWithPagination("token", "query_data", args, 20);

        Map<String, Object> receivedArgs = testAdapter.receivedArgs.get(0);
        assertThat(receivedArgs).containsEntry("page", 1);
        assertThat(receivedArgs).containsEntry("pageSize", 20);
    }

    // ============================================================
    // Test helpers
    // ============================================================

    private static Map<String, Object> item(int id) {
        return Map.of("id", id, "name", "item-" + id);
    }

    private static Map<String, Object> singlePageResponse(int totalCount, int pageSize) {
        return Map.of("result", Map.of(
                "totalCount", totalCount,
                "items", List.of(item(1))
        ));
    }

    private static Map<String, Object> paginatedResponse(List<Map<String, Object>> items, int totalCount, int pageSize) {
        return Map.of("result", Map.of(
                "totalCount", totalCount,
                "pageSize", pageSize,
                "items", items
        ));
    }

    private static Map<String, Object> fullPageResponse(List<Map<String, Object>> items, int totalCount) {
        return Map.of("result", Map.of(
                "totalCount", totalCount,
                "items", items
        ));
    }

    private static Map<String, Object> nestedDataItemsResponse(List<Map<String, Object>> items, int totalCount) {
        return Map.of("result", Map.of(
                "total", totalCount,
                "data", Map.of("items", items)
        ));
    }

    private static Map<String, Object> recordsResponse(List<Map<String, Object>> records, int totalCount) {
        return Map.of("result", Map.of(
                "totalCount", totalCount,
                "records", records
        ));
    }

    private static Map<String, Object> listResponse(List<Map<String, Object>> list, int totalCount) {
        return Map.of("result", Map.of(
                "totalCount", totalCount,
                "list", list
        ));
    }

    /**
     * Test adapter that overrides callTool to return canned responses,
     * while exercising the real callToolWithPagination logic.
     */
    static class PaginationTestAdapter extends McpAdapter {
        final List<Map<String, Object>> cannedResponses;
        final RuntimeException failure;
        final List<Map<String, Object>> receivedArgs = new ArrayList<>();
        int callCount = 0;

        PaginationTestAdapter(List<Map<String, Object>> cannedResponses) {
            this(cannedResponses, null);
        }

        PaginationTestAdapter(List<Map<String, Object>> cannedResponses, RuntimeException failure) {
            super(testProperties(), RestClient.builder(), new ObjectMapper());
            this.cannedResponses = cannedResponses;
            this.failure = failure;
        }

        @Override
        public Map<String, Object> callTool(String token, String toolName, Map<String, Object> arguments) {
            if (failure != null) {
                throw failure;
            }
            receivedArgs.add(new LinkedHashMap<>(arguments));
            if (callCount >= cannedResponses.size()) {
                return Map.of("result", Map.of("items", List.of()));
            }
            return cannedResponses.get(callCount++);
        }
    }
}
