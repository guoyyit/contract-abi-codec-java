package com.github.guoyyit.contractabicodecjava.web;

import com.github.guoyyit.contractabicodecjava.abi.AbiException;
import com.github.guoyyit.contractabicodecjava.abi.Keccak256;
import com.github.guoyyit.contractabicodecjava.registry.FunctionDefinition;
import com.github.guoyyit.contractabicodecjava.registry.SelectorCollision;
import com.github.guoyyit.contractabicodecjava.service.CodecService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 本地进程内调用接口（HTTP）。所有错误以结构化 JSON 返回：
 * {@code {"code": "...", "offset": n, "message": "..."}}。
 */
@RestController
@RequestMapping("/api")
public class CodecController {

    private final CodecService service;

    public CodecController(CodecService service) {
        this.service = service;
    }

    public record RegisterRequest(String name, List<String> inputs, List<String> outputs, String alias) {}

    public record FunctionView(String name, String signature, String selector, String alias,
                               List<String> inputs, List<String> outputs) {}

    public record EncodeRequest(String function, List<Object> args) {}

    public record DecodeCallRequest(String calldata, String alias) {}

    public record DecodeResultRequest(String function, String data) {}

    public record ErrorView(String code, long offset, String message) {}

    @PostMapping("/functions")
    public FunctionView register(@RequestBody RegisterRequest req) {
        FunctionDefinition def = service.register(req.name(),
                req.inputs() == null ? List.of() : req.inputs(),
                req.outputs() == null ? List.of() : req.outputs(),
                req.alias());
        return view(def);
    }

    @GetMapping("/functions")
    public List<FunctionView> list() {
        return service.snapshot().list().stream().map(CodecController::view).toList();
    }

    @GetMapping("/functions/collisions")
    public List<SelectorCollision> collisions() {
        return service.collisions();
    }

    @PostMapping("/encode")
    public Map<String, String> encode(@RequestBody EncodeRequest req) {
        byte[] calldata = service.encodeCall(req.function(),
                req.args() == null ? List.of() : req.args());
        return Map.of("calldata", Keccak256.toHex(calldata));
    }

    @PostMapping("/decode-call")
    public List<Object> decodeCall(@RequestBody DecodeCallRequest req) {
        byte[] calldata = hexToBytes(req.calldata());
        return toJsonFriendly(service.decodeCall(calldata, req.alias()));
    }

    @PostMapping("/decode-result")
    public List<Object> decodeResult(@RequestBody DecodeResultRequest req) {
        byte[] data = hexToBytes(req.data());
        return toJsonFriendly(service.decodeResult(req.function(), data));
    }

    @GetMapping("/cache/stats")
    public Map<String, Object> cacheStats() {
        return Map.of("size", service.cache().size(), "capacity", service.cache().capacity());
    }

    @ExceptionHandler(AbiException.class)
    public ResponseEntity<ErrorView> onAbiError(AbiException e) {
        return ResponseEntity.badRequest()
                .body(new ErrorView(e.code().name(), e.offset(), e.getMessage()));
    }

    // ---------------------------------------------------------------- 辅助

    private static FunctionView view(FunctionDefinition def) {
        return new FunctionView(def.name(), def.canonicalSignature(), def.selectorHex(), def.alias(),
                def.inputs().stream().map(com.github.guoyyit.contractabicodecjava.abi.TypeParser::canonical).toList(),
                def.outputs().stream().map(com.github.guoyyit.contractabicodecjava.abi.TypeParser::canonical).toList());
    }

    /** 把解码结果转成 JSON 友好形式：byte[] -> 0x hex，BigInteger -> 十进制字符串。 */
    private static List<Object> toJsonFriendly(List<Object> values) {
        List<Object> out = new ArrayList<>(values.size());
        for (Object v : values) {
            out.add(toJsonFriendly(v));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Object toJsonFriendly(Object v) {
        if (v instanceof byte[] b) {
            return Keccak256.toHex(b);
        }
        if (v instanceof BigInteger bi) {
            return bi.toString();
        }
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>(l.size());
            for (Object item : l) {
                out.add(toJsonFriendly(item));
            }
            return out;
        }
        return v;
    }

    private static byte[] hexToBytes(String hex) {
        if (hex == null || !hex.startsWith("0x") || hex.length() % 2 != 0) {
            throw new AbiException(AbiException.ErrorCode.TYPE_MISMATCH,
                    "expected 0x-prefixed even-length hex string");
        }
        byte[] out = new byte[(hex.length() - 2) / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(hex.charAt(2 + i * 2), 16);
            int lo = Character.digit(hex.charAt(3 + i * 2), 16);
            if (hi < 0 || lo < 0) {
                throw new AbiException(AbiException.ErrorCode.TYPE_MISMATCH, "invalid hex: " + hex);
            }
            out[i] = (byte) (hi * 16 + lo);
        }
        return out;
    }
}
