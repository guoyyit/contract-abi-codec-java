package com.github.guoyyit.contractabicodecjava.web;

import com.github.guoyyit.contractabicodecjava.api.CodecApi;
import com.github.guoyyit.contractabicodecjava.model.FunctionDefinition;
import com.github.guoyyit.contractabicodecjava.model.Parameter;
import com.github.guoyyit.contractabicodecjava.encoding.AbiTypeParser;
import com.github.guoyyit.contractabicodecjava.model.AbiType;
import com.github.guoyyit.contractabicodecjava.model.SelectorCollision;
import com.github.guoyyit.contractabicodecjava.util.Hex;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/** HTTP surface mirroring the local {@link CodecApi}. */
@RestController
@RequestMapping("/api/codec")
public class CodecController {

    private final CodecApi api;
    private final com.github.guoyyit.contractabicodecjava.encoding.CodecService codecService;

    public CodecController(CodecApi api,
                           com.github.guoyyit.contractabicodecjava.encoding.CodecService codecService) {
        this.api = api;
        this.codecService = codecService;
    }

    public record ParamDto(String name, String type) {}
    public record FunctionDto(String name, List<ParamDto> inputs, List<ParamDto> outputs) {}
    public record FunctionView(String name, String signature, String selector,
                               FunctionDto definition) {}
    public record EncodeRequest(List<Object> values) {}
    public record DecodeRequest(String data) {}
    public record DataResponse(String data) {}
    public record DecodeResponse(List<Object> values) {}
    public record AliasRequest(String alias, String signature) {}

    @PostMapping("/functions")
    public FunctionView register(@RequestBody FunctionDto dto) {
        FunctionDefinition registered = api.registerFunction(toDefinition(dto));
        return toView(registered);
    }

    @PostMapping("/functions/batch")
    public List<FunctionView> registerBatch(@RequestBody List<FunctionDto> dtos) {
        List<FunctionDefinition> defs = new ArrayList<>();
        for (FunctionDto dto : dtos) {
            defs.add(toDefinition(dto));
        }
        return api.registerFunctions(defs).stream().map(CodecController::toView).toList();
    }

    @GetMapping("/functions/{ref}")
    public FunctionView get(@PathVariable String ref) {
        return toView(api.getFunction(ref));
    }

    @GetMapping("/collisions")
    public List<SelectorCollision> collisions() {
        return api.getCollisions();
    }

    @PostMapping("/aliases")
    public void assignAlias(@RequestBody AliasRequest req) {
        api.assignAlias(req.alias(), req.signature());
    }

    @PostMapping("/functions/{ref}/encode")
    public DataResponse encode(@PathVariable String ref, @RequestBody EncodeRequest req) {
        byte[] out = api.encode(ref, req.values() == null ? List.of() : req.values());
        return new DataResponse(Hex.encode0x(out));
    }

    @PostMapping("/functions/{ref}/decode")
    public DecodeResponse decode(@PathVariable String ref, @RequestBody DecodeRequest req) {
        byte[] data = Hex.decode(req.data() == null ? "" : req.data());
        List<Object> values = api.decode(ref, data);
        return new DecodeResponse(toJsonValues(values));
    }

    @PostMapping("/functions/{ref}/decode-args")
    public DecodeResponse decodeArgs(@PathVariable String ref, @RequestBody DecodeRequest req) {
        byte[] data = Hex.decode(req.data() == null ? "" : req.data());
        List<Object> values = codecService.decodeArgs(ref, data);
        return new DecodeResponse(toJsonValues(values));
    }

    private static FunctionDefinition toDefinition(FunctionDto dto) {
        return new FunctionDefinition(dto.name(), toParams(dto.inputs()), toParams(dto.outputs()));
    }

    private static List<Parameter> toParams(List<ParamDto> dtos) {
        if (dtos == null) {
            return List.of();
        }
        List<Parameter> params = new ArrayList<>();
        for (ParamDto p : dtos) {
            AbiType type = AbiTypeParser.parse(p.type());
            params.add(new Parameter(p.name() == null ? "" : p.name(), type));
        }
        return params;
    }

    private static FunctionView toView(FunctionDefinition f) {
        String sig = com.github.guoyyit.contractabicodecjava.encoding.CanonicalSignature.of(f);
        List<ParamDto> inputs = f.inputs().stream()
                .map(p -> new ParamDto(p.name(), p.type().canonicalName())).toList();
        List<ParamDto> outputs = f.outputs().stream()
                .map(p -> new ParamDto(p.name(), p.type().canonicalName())).toList();
        return new FunctionView(f.name(), sig, f.selector(),
                new FunctionDto(f.name(), inputs, outputs));
    }

    /** byte[] values are surfaced as 0x-hex strings in JSON; everything else passes through. */
    private static List<Object> toJsonValues(List<Object> values) {
        List<Object> out = new ArrayList<>(values.size());
        for (Object v : values) {
            out.add(convert(v));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Object convert(Object v) {
        if (v instanceof byte[] b) {
            return Hex.encode0x(b);
        }
        if (v instanceof List<?> list) {
            List<Object> converted = new ArrayList<>(list.size());
            for (Object o : list) {
                converted.add(convert(o));
            }
            return converted;
        }
        return v;
    }
}
