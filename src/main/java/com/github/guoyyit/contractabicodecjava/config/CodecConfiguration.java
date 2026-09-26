package com.github.guoyyit.contractabicodecjava.config;

import com.github.guoyyit.contractabicodecjava.api.CodecApi;
import com.github.guoyyit.contractabicodecjava.api.InProcessCodecApi;
import com.github.guoyyit.contractabicodecjava.crypto.SelectorHasher;
import com.github.guoyyit.contractabicodecjava.encoding.AbiDecoder;
import com.github.guoyyit.contractabicodecjava.encoding.AbiEncoder;
import com.github.guoyyit.contractabicodecjava.encoding.CodecService;
import com.github.guoyyit.contractabicodecjava.encoding.SelectorCalculator;
import com.github.guoyyit.contractabicodecjava.encoding.TypeLayoutCache;
import com.github.guoyyit.contractabicodecjava.model.FunctionDefinition;
import com.github.guoyyit.contractabicodecjava.registry.FunctionRegistry;
import com.github.guoyyit.contractabicodecjava.registry.FunctionStore;
import com.github.guoyyit.contractabicodecjava.registry.JsonFileFunctionStore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.util.List;

/** Wires the codec subsystem. Registry construction performs crash-safe recovery. */
@Configuration
@EnableConfigurationProperties(CodecProperties.class)
public class CodecConfiguration {

    @Bean
    public SelectorHasher selectorHasher() {
        return SelectorHasher.keccak();
    }

    @Bean
    public SelectorCalculator selectorCalculator(SelectorHasher hasher) {
        return new SelectorCalculator(hasher);
    }

    @Bean
    public TypeLayoutCache typeLayoutCache(CodecProperties props) {
        return new TypeLayoutCache(props.getLayoutCacheCapacity());
    }

    @Bean
    public FunctionStore functionStore(CodecProperties props) {
        if (!props.isPersistenceEnabled()) {
            return new FunctionStore() {
                @Override public void persist(List<FunctionDefinition> functions) { }
                @Override public List<FunctionDefinition> load() { return List.of(); }
            };
        }
        return new JsonFileFunctionStore(Path.of(props.getRegistryFile()));
    }

    @Bean
    public AbiEncoder abiEncoder(CodecProperties props) {
        return new AbiEncoder(props.getMaxNestingDepth());
    }

    @Bean
    public AbiDecoder abiDecoder(CodecProperties props) {
        return AbiDecoderFactory.create(props);
    }

    @Bean
    public FunctionRegistry functionRegistry(SelectorCalculator selectorCalculator,
                                             TypeLayoutCache typeLayoutCache,
                                             FunctionStore functionStore) {
        return new FunctionRegistry(selectorCalculator, typeLayoutCache, functionStore);
    }

    @Bean
    public CodecService codecService(FunctionRegistry registry,
                                     AbiEncoder encoder,
                                     AbiDecoder decoder,
                                     TypeLayoutCache typeLayoutCache,
                                     CodecProperties props) {
        return new CodecService(registry, encoder, decoder, typeLayoutCache, props.getMaxInputBytes());
    }

    @Bean
    public CodecApi codecApi(FunctionRegistry registry, CodecService codecService) {
        return new InProcessCodecApi(registry, codecService);
    }

    /** Indirection kept tiny so tests can reuse the same construction. */
    static final class AbiDecoderFactory {
        static AbiDecoder create(CodecProperties props) {
            return new AbiDecoder(props.getMaxNestingDepth());
        }
    }
}
