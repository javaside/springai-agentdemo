package io.github.javaside.springai.codetui.agent.llm;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** ModelListEnv：*_MODELS 环境变量解析（逗号分隔，第一项为默认模型），畸形输入一律回退内置清单。 */
class ModelListEnvTest {

    private static final List<ModelOption> FALLBACK = List.of(
            new ModelOption("built-in-default", "built-in-default", "内置默认"),
            new ModelOption("built-in-alt",     "built-in-alt",     "内置备选"));

    @Test
    void nullOrBlank_returnsFallback() {
        assertSame(FALLBACK, ModelListEnv.parse(null, FALLBACK));
        assertSame(FALLBACK, ModelListEnv.parse("", FALLBACK));
        assertSame(FALLBACK, ModelListEnv.parse("   ", FALLBACK));
    }

    @Test
    void onlyCommasOrSpaces_returnsFallback() {
        assertSame(FALLBACK, ModelListEnv.parse(",,", FALLBACK));
        assertSame(FALLBACK, ModelListEnv.parse(" , , ", FALLBACK));
    }

    @Test
    void singleModel_parsed_labelIsId_descEmpty() {
        List<ModelOption> out = ModelListEnv.parse("my-model", FALLBACK);
        assertEquals(1, out.size());
        assertEquals(new ModelOption("my-model", "my-model", ""), out.get(0));
    }

    @Test
    void multiModels_trimmed_emptyItemsSkipped_orderKept() {
        List<ModelOption> out = ModelListEnv.parse(" m-pro , m-flash ,, m-lite ", FALLBACK);
        assertEquals(List.of("m-pro", "m-flash", "m-lite"),
                out.stream().map(ModelOption::id).toList());
    }
}
