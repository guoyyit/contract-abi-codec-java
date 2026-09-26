package com.github.guoyyit.contractabicodecjava.tests;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"codec.store-file=target/test-web-registry.bin",
                "codec.persist-on-change=false"})
class WebApiTest {

    @Autowired
    TestRestTemplate rest;

    @Test
    @SuppressWarnings("unchecked")
    void endToEndOverHttp() {
        // 注册
        ResponseEntity<Map> reg = rest.postForEntity("/api/functions",
                Map.of("name", "transfer",
                        "inputs", List.of("address", "uint256"),
                        "outputs", List.of("bool")), Map.class);
        assertEquals(HttpStatus.OK, reg.getStatusCode());
        assertEquals("0xa9059cbb", reg.getBody().get("selector"));

        // 编码调用
        ResponseEntity<Map> enc = rest.postForEntity("/api/encode",
                Map.of("function", "transfer",
                        "args", List.of("0x0000000000000000000000000000000000000001", "1000")), Map.class);
        assertEquals(HttpStatus.OK, enc.getStatusCode());
        String calldata = (String) enc.getBody().get("calldata");
        assertTrue(calldata.startsWith("0xa9059cbb"));

        // 解码调用
        ResponseEntity<List> dec = rest.postForEntity("/api/decode-call",
                Map.of("calldata", calldata), List.class);
        assertEquals(HttpStatus.OK, dec.getStatusCode());
        assertEquals("0x0000000000000000000000000000000000000001", dec.getBody().get(0));
        assertEquals("1000", dec.getBody().get(1));

        // 解码返回值
        ResponseEntity<List> decRes = rest.postForEntity("/api/decode-result",
                Map.of("function", "transfer",
                        "data", "0x0000000000000000000000000000000000000000000000000000000000000001"),
                List.class);
        assertEquals(Boolean.TRUE, decRes.getBody().get(0));
    }

    @Test
    @SuppressWarnings("unchecked")
    void structuredErrorReturned() {
        // 未注册的函数
        ResponseEntity<Map> resp = rest.postForEntity("/api/encode",
                Map.of("function", "ghost", "args", List.of()), Map.class);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertEquals("UNKNOWN_SELECTOR", resp.getBody().get("code"));
        assertNotNull(resp.getBody().get("message"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void collisionVisibleOverHttp() {
        rest.postForEntity("/api/functions",
                Map.of("name", "f38491", "inputs", List.of("uint256"), "outputs", List.of()), Map.class);
        rest.postForEntity("/api/functions",
                Map.of("name", "f116643", "inputs", List.of("uint256"), "outputs", List.of()), Map.class);
        ResponseEntity<List> resp = rest.getForEntity("/api/functions/collisions", List.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertFalse(resp.getBody().isEmpty());
        Map<String, Object> first = (Map<String, Object>) resp.getBody().get(0);
        assertEquals("0x77dbd42e", first.get("selectorHex"));
    }
}
