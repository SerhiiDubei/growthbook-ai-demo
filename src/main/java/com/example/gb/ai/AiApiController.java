package com.example.gb.ai;


import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/ai")
class AiApiController {

    private final Assistant assistant;

    public AiApiController(Assistant assistant) {
        this.assistant = assistant;
    }

    @PostMapping(path = "/chat", consumes = "application/json", produces = "application/json")
    public ResponseEntity<ChatResponse> chatJson(@Valid @RequestBody ChatRequest req, HttpSession session) {

        log.info("AI chat request: sessionId={}, msgLen={}", session.getId(), req.getMessage().length());

        try {
            String reply = assistant.chat(session.getId(), req.getMessage());
            return ResponseEntity.ok(new ChatResponse(reply));
        } catch (Exception e) {
            log.error("AI chat failed", e);
            throw e; // або return 500 з повідомленням
        }
    }

    // Скидання пам'яті (на випадок «висячого» tool call)
    @PostMapping("/reset")
    public ResponseEntity<Void> reset(HttpSession session) {
        session.invalidate();
        return ResponseEntity.noContent().build();
    }

    @Data
    public static class ChatRequest {
        @NotBlank(message = "message is required")
        String message;
        String memoryId; // optional
    }

    @Data
    @AllArgsConstructor
    public static class ChatResponse {
        String reply;

    }

}

