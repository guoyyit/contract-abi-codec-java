package com.github.guoyyit.contractabicodecjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.junit.jupiter.api.TestInstance;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(locations = "classpath:application-test.properties")
class WebApiIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeAll
    void setUp() throws Exception {
        Files.deleteIfExists(Path.of("target/test-data/registry-it.json"));
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    @Order(1)
    void registersFunctionAndReturnsSelector() throws Exception {
        String body = """
            {"name":"transfer",
             "inputs":[{"name":"to","type":"address"},{"name":"amount","type":"uint256"}],
             "outputs":[{"name":"ok","type":"bool"}]}
            """;
        mvc.perform(post("/api/codec/functions").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.selector").value("0xa9059cbb"))
                .andExpect(jsonPath("$.signature").value("transfer(address,uint256)"));
    }

    @Test
    @Order(2)
    void encodesAndDecodesOverHttp() throws Exception {
        String body = """
            {"values":["0x1122334455667788990011223344556677889900", 12345]}
            """;
        MvcResult res = mvc.perform(post("/api/codec/functions/transfer/encode")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode node = json.readTree(res.getResponse().getContentAsString());
        String calldata = node.get("data").asText();
        org.junit.jupiter.api.Assertions.assertTrue(calldata.startsWith("0xa9059cbb"));

        var decodeRes = mvc.perform(post("/api/codec/functions/transfer/decode-args")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"data\":\"" + calldata.substring("0x".length() + 8) + "\"}"))
                .andReturn();
        org.junit.jupiter.api.Assertions.assertEquals(200, decodeRes.getResponse().getStatus());
    }

    @Test
    @Order(3)
    void returnsStructuredErrorForUnknownFunction() throws Exception {
        mvc.perform(get("/api/codec/functions/nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FUNCTION_NOT_FOUND"));
    }

    @Test
    @Order(4)
    void reportsCollisionsEndpointEmpty() throws Exception {
        mvc.perform(get("/api/codec/collisions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
