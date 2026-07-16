# Test Center VChart Demo Cards Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the test center's six legacy Feishu card presets with server-provided JSON 2.0 demos containing native Markdown, tables, buttons, and VChart bar/line/pie charts backed by richer single-project and multi-project data.

**Architecture:** A focused `FeishuDemoCardCatalog` owns trusted debug-only card definitions and exposes immutable preset records. `DebugService` and `DebugController` publish the catalog through a read-only endpoint; the Vue test center loads it and keeps the existing editable JSON/send workflow. Production answer rendering remains in `FeishuAnswerCardRenderer` and is not changed by this feature.

**Tech Stack:** Java 17, Spring Boot 3.3, JUnit 5/AssertJ, Vue 3, TypeScript, Ant Design Vue, Maven, Vite.

## Global Constraints

- All six presets use Feishu card `schema: "2.0"` and `body.elements`.
- Charts use native Feishu `chart` components with trusted VChart `chart_spec`; do not introduce ECharts, JavaScript, HTML, images, or arbitrary client-provided chart specs.
- Supported chart types are exactly `bar`, `line`, and `pie`, with no more than three charts per card.
- Include Roche seven-day trend data and Roche/Siemens/XDL cross-project data.
- Preserve preset selection, JSON editing/formatting, single-recipient sending, and batch sending.
- Do not change the production `FeishuAnswerCardRenderer` behavior.

---

### Task 1: Build the JSON 2.0 Demo Card Catalog

**Files:**
- Create: `backend/src/test/java/com/larkconnect/agent/admin/FeishuDemoCardCatalogTest.java`
- Create: `backend/src/main/java/com/larkconnect/agent/admin/FeishuDemoCardCatalog.java`

**Interfaces:**
- Produces: `List<FeishuDemoCardCatalog.CardPreset> presets()`.
- Produces record: `CardPreset(String key, String label, String description, String color, Map<String, Object> card)`.
- Later tasks consume the catalog through `DebugService.feishuCardPresets()`.

- [ ] **Step 1: Write the failing catalog contract tests**

Create tests that instantiate `new FeishuDemoCardCatalog()` and assert:

```java
@Test
void returnsSixUniqueJson2PresetsWithCharts() {
    List<FeishuDemoCardCatalog.CardPreset> presets = catalog.presets();
    assertThat(presets).hasSize(6);
    assertThat(presets).extracting(FeishuDemoCardCatalog.CardPreset::key).doesNotHaveDuplicates();
    for (FeishuDemoCardCatalog.CardPreset preset : presets) {
        assertThat(preset.card()).containsEntry("schema", "2.0").containsKey("body");
        assertThat(elements(preset.card())).anyMatch(element -> "chart".equals(element.get("tag")));
    }
}
```

Add focused tests that recursively reject `lark_md`, `action`, and `note`; verify no root-level `elements`; verify chart types are only `bar`, `line`, `pie`; verify the basic preset covers all three types; verify the Roche line chart contains seven unique date labels; and verify multi-project datasets contain `Roche`, `Siemens`, and `XDL`.

- [ ] **Step 2: Run the test and verify RED**

Run:

```bash
cd backend && mvn -Dtest=FeishuDemoCardCatalogTest test
```

Expected: compilation fails because `FeishuDemoCardCatalog` does not exist.

- [ ] **Step 3: Implement the catalog root and JSON 2.0 component helpers**

Create a Spring component with immutable results:

```java
@Component
public final class FeishuDemoCardCatalog {
    public record CardPreset(String key, String label, String description,
                             String color, Map<String, Object> card) {}

    public List<CardPreset> presets() {
        return List.of(answer(), basic(), todo(), construction(), risk(), weekly());
    }
}
```

Within the same focused file, add private helpers for `card`, `markdown`, `table`, `chart`, and `button`. The root helper must emit `schema`, `config.update_multi`, `header`, and `body.elements`; button helpers must emit callback entries under `behaviors`.

- [ ] **Step 4: Add the six scenario datasets and cards**

Implement the exact preset keys already used by the page:

```text
primelayer-answer
basic-test
daily-todo
construction-daily
risk-alert
weekly-summary
```

Use seven daily labels generated from `LocalDate.now(ZoneId.of("Asia/Shanghai"))`, fixed numeric series for planned/actual progress and issue creation/closure, and fixed project labels `Roche`, `Siemens`, `XDL`. Each table must contain 5–12 rows. Limit each card to 1–3 chart elements.

- [ ] **Step 5: Run the catalog test and verify GREEN**

Run:

```bash
cd backend && mvn -Dtest=FeishuDemoCardCatalogTest test
```

Expected: all catalog tests pass with no warnings or errors.

### Task 2: Expose the Catalog Through the Debug API

**Files:**
- Create: `backend/src/test/java/com/larkconnect/agent/admin/DebugControllerCardPresetTest.java`
- Modify: `backend/src/main/java/com/larkconnect/agent/admin/DebugService.java`
- Modify: `backend/src/main/java/com/larkconnect/agent/admin/DebugController.java`

**Interfaces:**
- Consumes: `FeishuDemoCardCatalog.presets()`.
- Produces: `DebugService.feishuCardPresets()` returning `List<FeishuDemoCardCatalog.CardPreset>`.
- Produces: `GET /api/admin/debug/feishu/card-presets` wrapped in `ApiResponse`.

- [ ] **Step 1: Write a failing controller mapping test**

Use `@WebMvcTest(DebugController.class)`, mock `DebugService`, and assert the endpoint returns preset metadata and the nested JSON 2.0 card:

```java
mockMvc.perform(get("/api/admin/debug/feishu/card-presets"))
    .andExpect(status().isOk())
    .andExpect(jsonPath("$.data[0].key").value("primelayer-answer"))
    .andExpect(jsonPath("$.data[0].card.schema").value("2.0"));
```

- [ ] **Step 2: Run the test and verify RED**

Run:

```bash
cd backend && mvn -Dtest=DebugControllerCardPresetTest test
```

Expected: 404 because the route is not defined.

- [ ] **Step 3: Add the service dependency and read-only route**

Inject `FeishuDemoCardCatalog` into `DebugService`, add:

```java
public List<FeishuDemoCardCatalog.CardPreset> feishuCardPresets() {
    return demoCardCatalog.presets();
}
```

Add to `DebugController`:

```java
@GetMapping("/feishu/card-presets")
public ApiResponse<List<FeishuDemoCardCatalog.CardPreset>> feishuCardPresets() {
    return ApiResponse.ok(debugService.feishuCardPresets());
}
```

Update direct `DebugService` constructor usage in tests to pass a catalog instance or mock.

- [ ] **Step 4: Run targeted backend tests and verify GREEN**

Run:

```bash
cd backend && mvn -Dtest=FeishuDemoCardCatalogTest,DebugControllerCardPresetTest test
```

Expected: both test classes pass.

### Task 3: Load Server Presets in the Vue Test Center

**Files:**
- Modify: `admin-web/src/api/admin.ts`
- Modify: `admin-web/src/views/TestCenterView.vue`

**Interfaces:**
- Consumes: `GET /api/admin/debug/feishu/card-presets`.
- Produces TypeScript interface: `FeishuCardPreset { key; label; description; color; card }`.
- Produces page method: `loadFeishuCardPresets(): Promise<void>`.

- [ ] **Step 1: Add the typed API contract and wire it into page initialization**

Define:

```ts
export interface FeishuCardPreset {
  key: string
  label: string
  description: string
  color: string
  card: Record<string, unknown>
}
```

Add `adminApi.debugFeishuCardPresets()` and change `cardPresets` to a `ref<FeishuCardPreset[]>([])`. Add `loading.feishuCardPresets`, disable send/template controls while loading or when no preset is available, and include `loadFeishuCardPresets()` in `refreshAll()`.

- [ ] **Step 2: Remove the legacy client-side card builders**

Delete `textBlock`, `fieldBlock`, `noteBlock`, `buttonAction`, and all six `create*Card` functions. `applyCardPreset()` must serialize `selectedPreset.value.card`; an unknown key selects the first valid returned preset. A failed or empty response clears `cardJson`, leaves the selector empty, and shows an explicit error.

- [ ] **Step 3: Run the production build**

Run:

```bash
cd admin-web && npm run build
```

Expected: `vue-tsc --noEmit` and Vite both succeed.

### Task 4: Regression Verification and Review

**Files:**
- Modify only files required to fix failures caused by Tasks 1–3.

**Interfaces:**
- Verifies the complete backend/frontend contract and preserves prior answer-card work.

- [ ] **Step 1: Run formatting and diff checks**

Run:

```bash
git diff --check
git status --short
```

Expected: no whitespace errors; only intended answer-card work, design/plan documents, catalog/API/frontend changes, and their tests are present.

- [ ] **Step 2: Run the full backend test suite**

Run:

```bash
cd backend && mvn test
```

Expected: all tests pass, including the existing answer presentation, renderer, feedback, fallback, and DeepSeek tests.

- [ ] **Step 3: Run the frontend production build again**

Run:

```bash
cd admin-web && npm run build
```

Expected: type checking and production bundle succeed.

- [ ] **Step 4: Review the generated cards**

Inspect the six preset payloads in the catalog tests or test output and confirm each card stays below three charts, tables have 5–12 rows, labels are readable Chinese, and no ECharts placeholder remains.

- [ ] **Step 5: Commit the implementation**

Stage only the feature files and existing related answer-card changes after reviewing the full diff, then commit with:

```bash
git commit -m "feat: add VChart demos to test center"
```
