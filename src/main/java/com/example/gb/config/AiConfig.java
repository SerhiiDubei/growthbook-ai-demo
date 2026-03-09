package com.example.gb.config;

import com.example.gb.ai.Assistant;
import com.example.gb.ai.tools.DomAnalyticsTools;
import com.example.gb.ai.tools.DomInventoryTools;
import com.example.gb.ai.tools.ExperimentTools;
import com.example.gb.ai.tools.GrowthBookTools;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiConfig {

    @Bean
    OpenAiChatModel chatModel(
            @Value("${openai.api-key}") String apiKey,
            @Value("${openai.model:gpt-4o-mini}") String model
    ) {
        return OpenAiChatModel.builder()
                .apiKey(apiKey)
                .modelName(model)
                .temperature(0.2)          // можна підлаштувати
                .build();
    }

    @Bean
    ChatMemoryProvider chatMemory() {
        // у 1.8.0 MessageWindowChatMemory залишився тим самим
        return memoryId -> MessageWindowChatMemory.withMaxMessages(20);
    }

    @Bean
    Assistant assistant(OpenAiChatModel model,
            ChatMemoryProvider memory,
            GrowthBookTools gbTools,
            DomAnalyticsTools domAnalyticsTools,
            DomInventoryTools domInventoryTools,
            ExperimentTools experimentTools) {

        return AiServices.builder(Assistant.class)
                .chatModel(model)
                .chatMemoryProvider(memory)
                .tools(domInventoryTools, gbTools, domAnalyticsTools, experimentTools)
                .maxSequentialToolsInvocations(25)
                .build();
    }

}
