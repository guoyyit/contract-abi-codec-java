package com.github.guoyyit.contractabicodecjava.config;

import com.github.guoyyit.contractabicodecjava.service.CodecService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(CodecProperties.class)
public class CodecConfiguration {

    /** 编解码服务 Bean：构建后立即从持久化文件恢复注册表。 */
    @Bean
    public CodecService codecService(CodecProperties props) {
        CodecService service = new CodecService(props);
        service.recover();
        return service;
    }
}
