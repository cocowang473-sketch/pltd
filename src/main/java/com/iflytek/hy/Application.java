package com.iflytek.hy;

import com.iflytek.hy.config.LynxiaoProperties;
import com.iflytek.hy.config.OpenAiProperties;
import com.iflytek.hy.config.XfyunProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({OpenAiProperties.class, XfyunProperties.class, LynxiaoProperties.class})
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
