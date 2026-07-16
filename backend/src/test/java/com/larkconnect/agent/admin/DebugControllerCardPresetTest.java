package com.larkconnect.agent.admin;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DebugControllerCardPresetTest {
    @Test
    void exposesJson2CardPresetsAtReadOnlyDebugRoute() throws Exception {
        DebugService service = mock(DebugService.class);
        var preset = new FeishuDemoCardCatalog.CardPreset(
                "primelayer-answer", "AI 回答卡片", "图表 Demo", "#1455d9",
                Map.of("schema", "2.0", "body", Map.of("elements", List.of()))
        );
        when(service.feishuCardPresets()).thenReturn(List.of(preset));
        DebugController controller = new DebugController(service);

        var response = controller.feishuCardPresets();

        assertThat(response.data()).containsExactly(preset);
        Method method = DebugController.class.getMethod("feishuCardPresets");
        assertThat(method.getAnnotation(GetMapping.class).value())
                .containsExactly("/feishu/card-presets");
    }
}
