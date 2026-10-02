package com.github.guoyyit.contractabicodecjava.tests;

import com.github.guoyyit.contractabicodecjava.api.InProcessCodecApi;
import com.github.guoyyit.contractabicodecjava.crypto.SelectorHasher;
import com.github.guoyyit.contractabicodecjava.encoding.AbiDecoder;
import com.github.guoyyit.contractabicodecjava.encoding.AbiEncoder;
import com.github.guoyyit.contractabicodecjava.encoding.CodecService;
import com.github.guoyyit.contractabicodecjava.encoding.SelectorCalculator;
import com.github.guoyyit.contractabicodecjava.encoding.TypeLayoutCache;
import com.github.guoyyit.contractabicodecjava.registry.FunctionRegistry;
import com.github.guoyyit.contractabicodecjava.web.CodecController;
import com.github.guoyyit.contractabicodecjava.web.CodecExceptionHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Requirement 5: storage faults surface as HTTP 5xx, distinct from 4xx input errors.
 */
class WebPersistenceErrorTest {

    @Test
    void persistenceFaultReturnsServerError(@TempDir Path dir) {
        FaultInjection store = new FaultInjection(dir.resolve("registry.json"));
        store.failAt(FaultInjection.Stage.WRITE);
        FunctionRegistry registry = new FunctionRegistry(
                new SelectorCalculator(SelectorHasher.keccak()),
                new TypeLayoutCache(16), store);
        CodecService service = new CodecService(registry,
                new AbiEncoder(32), new AbiDecoder(32), new TypeLayoutCache(16), 1 << 20);
        CodecController controller =
                new CodecController(new InProcessCodecApi(registry, service), service);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new CodecExceptionHandler())
                .build();

        String body = """
            {"name":"ping","inputs":[],"outputs":[]}
            """;
        try {
            mvc.perform(post("/api/codec/functions")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value("PERSISTENCE_ERROR"));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void inputErrorStillReturnsBadRequest(@TempDir Path dir) {
        FaultInjection store = new FaultInjection(dir.resolve("registry.json"));
        FunctionRegistry registry = new FunctionRegistry(
                new SelectorCalculator(SelectorHasher.keccak()),
                new TypeLayoutCache(16), store);
        CodecService service = new CodecService(registry,
                new AbiEncoder(32), new AbiDecoder(32), new TypeLayoutCache(16), 1 << 20);
        CodecController controller =
                new CodecController(new InProcessCodecApi(registry, service), service);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new CodecExceptionHandler())
                .build();

        // Unknown ABI type is a caller input error -> 400, even with a healthy store.
        String body = """
            {"name":"ping","inputs":[{"name":"x","type":"notAType"}],"outputs":[]}
            """;
        try {
            mvc.perform(post("/api/codec/functions")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_SIGNATURE"));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
