package io.github.kongweiguang.voice.agent.autoconfigure;

import io.github.kongweiguang.voice.agent.audio.AudioFormatSpec;
import io.github.kongweiguang.voice.agent.eou.EouConfig;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * voice 公共模块的基础默认装配，仅负责注册 core 层稳定存在的配置属性。
 * ASR、VAD、EOU、LLM、TTS 和传输层的具体实现由 extension 或 app 模块负责提供。
 *
 * @author kongweiguang
 */
@AutoConfiguration
@EnableConfigurationProperties({AudioFormatSpec.class, EouConfig.class})
public class VoiceDefaultAutoConfiguration {
}
