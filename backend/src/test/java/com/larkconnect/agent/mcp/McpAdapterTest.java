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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;

class McpAdapterTest {

    private static AppProperties testProperties() {
        return new AppProperties(
                new AppProperties.Admin(3600, "admin", "admin"),
                new AppProperties.Agent(5, 30000, 30000),
                new AppProperties.Feishu("", "", "", "", false),
                new AppProperties.DeepSeek("https://api.deepseek.com", "key"),
                new AppProperties.Mcp("http://localhost/mcp", "X-API-Key")
        );
    }

    private final McpAdapter adapter = new McpAdapter(testProperties(), RestClient.builder(), new ObjectMapper());

    @Test
    void shouldRejectJsonRpcErrorReturnedWithHttpSuccess() {
        assertThatThrownBy(() -> adapter.parseResponse("""
                {"jsonrpc":"2.0","id":"1","error":{"code":-32602,"message":"invalid arguments"}}
                """))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invalid arguments");
    }

    @Test
    void shouldRejectMcpToolResultMarkedAsError() {
        assertThatThrownBy(() -> adapter.parseResponse("""
                {"jsonrpc":"2.0","id":"1","result":{"isError":true,"content":[{"type":"text","text":"tool failed"}]}}
                """))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tool failed");
    }

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
    void shouldExtractItemsFromDeeplyNestedResultDataRecords() {
        List<Map<String, String>> records = List.of(Map.of("id", "1"), Map.of("id", "2"));
        Map<String, Object> response = Map.of("result", Map.of("data", Map.of("records", records)));

        assertThat(adapter.extractItems(response)).hasSize(2);
        assertThat(adapter.extractReturnedCount(response)).isEqualTo(2);
        assertThat(adapter.extractReportedTotalCount(response)).isNull();
    }

    @Test
    void shouldKeepUnknownReturnedCountNullForNonListResponse() {
        Map<String, Object> response = Map.of("result", Map.of("message", "ok"));

        assertThat(adapter.extractReturnedCount(response)).isNull();
        assertThat(adapter.extractReportedTotalCount(response)).isNull();
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

    @Test
    void shouldNormalizeBusinessJsonFromMcpTextContent() {
        Map<String, Object> response = wrappedTextResponse(List.of(item(1), item(2)), 3439);

        assertThat(adapter.extractItems(response)).hasSize(2);
        assertThat(adapter.extractReturnedCount(response)).isEqualTo(2);
        assertThat(adapter.extractReportedTotalCount(response)).isEqualTo(3439);
    }

    @Test
    void shouldIgnoreInvalidTextContentWhenAnotherBlockContainsBusinessJson() {
        Map<String, Object> response = Map.of("result", Map.of("content", List.of(
                Map.of("type", "text", "text", "not-json"),
                Map.of("type", "text", "text", "{\"total\":2,\"data\":[{\"id\":1},{\"id\":2}]}")
        )));

        assertThat(adapter.extractItems(response)).hasSize(2);
        assertThat(adapter.extractReportedTotalCount(response)).isEqualTo(2);
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
    void shouldUseSchemaSelectedOffsetStyleWhenOptionalPaginationArgumentsAreOmitted() {
        PaginationTestAdapter testAdapter = new PaginationTestAdapter(List.of(singlePageResponse(1, 10)));

        testAdapter.callToolWithPagination("token", "query_data", Map.of("formId", "test-form"), 10,
                McpAdapter.PaginationStyle.OFFSET_LIMIT);

        Map<String, Object> receivedArgs = testAdapter.receivedArgs.get(0);
        assertThat(receivedArgs).containsEntry("offset", 0).containsEntry("limit", 10);
        assertThat(receivedArgs).doesNotContainKeys("page", "pageSize");
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
    void notifiesObserverAroundEachPhysicalPageWithExactRequestAndCounts() {
        PaginationTestAdapter testAdapter = new PaginationTestAdapter(List.of(
                paginatedResponse(List.of(item(1), item(2)), 2, 100)));
        List<Map<String, Object>> startedRequests = new ArrayList<>();
        List<PageData> completedPages = new ArrayList<>();

        testAdapter.callToolWithPagination("token", "query_data", Map.of(), 100,
                McpAdapter.PaginationStyle.PAGE_SIZE, new McpAdapter.PaginationObserver() {
                    @Override
                    public Object onStart(int pageIndex, int pageSize, Map<String, Object> rawRequest) {
                        startedRequests.add(rawRequest);
                        return "page-" + pageIndex;
                    }

                    @Override
                    public void onComplete(Object context, PageData page) {
                        assertThat(context).isEqualTo("page-" + page.page());
                        completedPages.add(page);
                    }
                });

        assertThat(startedRequests).singleElement().satisfies(request -> assertThat(request.toString())
                .contains("query_data").contains("page=1").contains("pageSize=100"));
        assertThat(completedPages).singleElement().satisfies(page -> {
            assertThat(page.rawRequest()).isEqualTo(startedRequests.get(0));
            assertThat(page.returnedCount()).isEqualTo(2);
            assertThat(page.reportedTotalCount()).isEqualTo(2);
        });
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
                fullPageResponse(List.of(item(1), item(2), item(3), item(4), item(5)), 6),
                paginatedResponse(List.of(item(6)), 6, 0)
        ));

        PaginationResult result = testAdapter.callToolWithPagination("token", "query_data", Map.of(), 5);

        assertThat(result.pages()).hasSize(2);
        assertThat(result.successPages()).isEqualTo(2);
    }

    @Test
    void shouldContinuePastFiftyPagesUntilReportedTotalIsFetched() {
        List<Map<String, Object>> responses = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            responses.add(paginatedResponse(List.of(item(i * 2), item(i * 2 + 1)), 120, 2));
        }
        PaginationTestAdapter testAdapter = new PaginationTestAdapter(responses);

        PaginationResult result = testAdapter.callToolWithPagination("token", "query_data", Map.of(), 2);

        assertThat(result.pages()).hasSize(60);
        assertThat(result.fetchedCount()).isEqualTo(120);
        assertThat(result.limitReached()).isFalse();
    }

    @Test
    void shouldNotReportLimitWhenFiftiethPageIsPartial() {
        List<Map<String, Object>> responses = new ArrayList<>();
        for (int i = 0; i < 49; i++) {
            responses.add(paginatedResponse(List.of(item(i * 2), item(i * 2 + 1)), 99, 2));
        }
        responses.add(paginatedResponse(List.of(item(99)), 99, 1));
        PaginationTestAdapter testAdapter = new PaginationTestAdapter(responses);

        PaginationResult result = testAdapter.callToolWithPagination("token", "query_data", Map.of(), 2);

        assertThat(result.pages()).hasSize(50);
        assertThat(result.limitReached()).isFalse();
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
                paginatedResponse(List.of(item(1), item(2), item(3)), 7, 3),
                paginatedResponse(List.of(item(4), item(5), item(6)), 7, 3),
                paginatedResponse(List.of(item(7)), 7, 3)
        ));

        PaginationResult result = testAdapter.callToolWithPagination("token", "query_data", Map.of(), 3);

        assertThat(result.totalCount()).isEqualTo(7);
        assertThat(result.pages()).hasSize(3);
        assertThat(result.successPages()).isEqualTo(3);
        assertThat(result.failedPages()).isEqualTo(0);
        // totalPages reflects actual pages fetched
        assertThat(result.totalPages()).isEqualTo(3);
        assertThat(result.pages()).extracting(PageData::returnedCount).containsExactly(3, 3, 1);
        assertThat(result.pages()).extracting(PageData::reportedTotalCount).containsOnly(7);
    }

    @Test
    void shouldFetchAllThirtyFiveWrappedPagesFor3439Records() {
        List<Map<String, Object>> responses = new ArrayList<>();
        int nextId = 1;
        for (int page = 0; page < 35; page++) {
            int size = page == 34 ? 39 : 100;
            List<Map<String, Object>> items = new ArrayList<>();
            for (int i = 0; i < size; i++) items.add(item(nextId++));
            responses.add(wrappedTextResponse(items, 3439));
        }
        PaginationTestAdapter testAdapter = new PaginationTestAdapter(responses);

        PaginationResult result = testAdapter.callToolWithPagination(
                "token", "query_form_data_list", Map.of(), 100);

        assertThat(result.pages()).hasSize(35);
        assertThat(result.fetchedCount()).isEqualTo(3439);
        assertThat(result.reportedTotalCount()).isEqualTo(3439);
        assertThat(result.coverageComplete()).isTrue();
        assertThat(result.incompleteReason()).isNull();
    }

    @Test
    void resumesFivePageBatchFromCheckpointWithFullStreamingStatistics() {
        List<Map<String, Object>> responses = new ArrayList<>();
        for (int page = 0; page < 7; page++) {
            int start = page * 2;
            responses.add(paginatedResponse(List.of(item(start + 1), item(start + 2)), 14, 2));
        }
        PaginationTestAdapter testAdapter = new PaginationTestAdapter(responses);

        McpAdapter.PaginationBatchResult first = testAdapter.callToolWithPaginationBatch(
                "token", "query_data", Map.of(), 2, McpAdapter.PaginationStyle.PAGE_SIZE,
                null, 5, null);
        McpAdapter.PaginationBatchResult second = testAdapter.callToolWithPaginationBatch(
                "token", "query_data", Map.of(), 2, McpAdapter.PaginationStyle.PAGE_SIZE,
                null, 5, first.continuation());

        assertThat(first.complete()).isFalse();
        assertThat(first.result().fetchedCount()).isEqualTo(10);
        assertThat(second.complete()).isTrue();
        assertThat(second.result().fetchedCount()).isEqualTo(14);
        assertThat(second.result().coverageComplete()).isTrue();
        assertThat(testAdapter.receivedArgs.get(5)).containsEntry("page", 6);
    }

    @Test
    void shouldContinuePartialPagesUntilReportedTotalIsFetched() {
        PaginationTestAdapter testAdapter = new PaginationTestAdapter(List.of(
                paginatedResponse(List.of(item(1), item(2)), 5, 100),
                paginatedResponse(List.of(item(3), item(4)), 5, 100),
                paginatedResponse(List.of(item(5)), 5, 100)
        ));

        PaginationResult result = testAdapter.callToolWithPagination("token", "query_data", Map.of(), 100);

        assertThat(result.pages()).hasSize(3);
        assertThat(result.fetchedCount()).isEqualTo(5);
        assertThat(result.coverageComplete()).isTrue();
    }

    @Test
    void shouldMarkCoverageIncompleteWhenEmptyPageAppearsBeforeReportedTotal() {
        PaginationTestAdapter testAdapter = new PaginationTestAdapter(List.of(
                paginatedResponse(List.of(item(1), item(2)), 5, 100),
                paginatedResponse(List.of(), 5, 100)
        ));

        PaginationResult result = testAdapter.callToolWithPagination("token", "query_data", Map.of(), 100);

        assertThat(result.fetchedCount()).isEqualTo(2);
        assertThat(result.coverageComplete()).isFalse();
        assertThat(result.incompleteReason()).contains("empty_page_before_total");
    }

    @Test
    void shouldAdvanceCursorUntilServerReportsNoMoreData() {
        PaginationTestAdapter testAdapter = new PaginationTestAdapter(List.of(
                cursorResponse(List.of(item(1), item(2)), 4, true, "cursor-2"),
                cursorResponse(List.of(item(3), item(4)), 4, false, null)
        ));

        PaginationResult result = testAdapter.callToolWithPagination("token", "query_data", Map.of(), 100,
                McpAdapter.PaginationStyle.CURSOR);

        assertThat(result.fetchedCount()).isEqualTo(4);
        assertThat(result.coverageComplete()).isTrue();
        assertThat(testAdapter.receivedArgs.get(0)).doesNotContainKey("cursor");
        assertThat(testAdapter.receivedArgs.get(1)).containsEntry("cursor", "cursor-2");
    }

    @Test
    void shouldRejectRepeatedPageBeforeReportedTotalAsIncompleteCoverage() {
        List<Map<String, Object>> sameItems = List.of(item(1), item(2));
        PaginationTestAdapter testAdapter = new PaginationTestAdapter(List.of(
                paginatedResponse(sameItems, 4, 2),
                paginatedResponse(sameItems, 4, 2)
        ));

        PaginationResult result = testAdapter.callToolWithPagination("token", "query_data", Map.of(), 2);

        assertThat(result.fetchedCount()).isEqualTo(2);
        assertThat(result.coverageComplete()).isFalse();
        assertThat(result.incompleteReason()).contains("duplicate_page_before_total");
        assertThat(result.pages().get(1).duplicate()).isTrue();
        assertThat(result.pages().get(1).cumulativeFetchedCount()).isEqualTo(2);
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

    private static Map<String, Object> wrappedTextResponse(List<Map<String, Object>> items, int totalCount) {
        try {
            String text = new ObjectMapper().writeValueAsString(Map.of("total", totalCount, "data", items));
            return Map.of("result", Map.of("content", List.of(Map.of("type", "text", "text", text))));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, Object> cursorResponse(List<Map<String, Object>> items, int totalCount,
                                                      boolean hasMore, String nextCursor) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalCount", totalCount);
        result.put("items", items);
        result.put("hasMore", hasMore);
        if (nextCursor != null) result.put("nextCursor", nextCursor);
        return Map.of("result", result);
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
            return callToolObserved(token, toolName, arguments).rawResponse();
        }

        @Override
        @SuppressWarnings("unchecked")
        protected ObservedCall executeObservedRequest(String token, Map<String, Object> rawRequest) {
            Map<String, Object> params = (Map<String, Object>) rawRequest.get("params");
            Map<String, Object> arguments = (Map<String, Object>) params.get("arguments");
            if (failure != null) {
                throw failure;
            }
            receivedArgs.add(new LinkedHashMap<>(arguments));
            if (callCount >= cannedResponses.size()) {
                Map<String, Object> response = Map.of("result", Map.of("items", List.of()));
                return new ObservedCall(rawRequest, response, 0, 0, null);
            }
            Map<String, Object> response = cannedResponses.get(callCount++);
            return new ObservedCall(rawRequest, response, 0,
                    extractReturnedCount(response), extractReportedTotalCount(response));
        }
    }
}
