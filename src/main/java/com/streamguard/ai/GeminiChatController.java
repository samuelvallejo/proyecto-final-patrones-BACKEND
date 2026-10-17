package com.streamguard.ai;

import com.streamguard.auth.AuthService;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

/** Authenticated Gemini conversation, independent of channel ownership and moderation provider. */
@RestController
@RequestMapping("/api/assistant/chat")
public class GeminiChatController {
  private final GeminiChatService chat;

  public GeminiChatController(GeminiChatService chat) {
    this.chat = chat;
  }

  @GetMapping("/config")
  public Map<String, Object> configuration() {
    AuthService.current();
    return chat.configuration();
  }

  @PostMapping
  public GeminiChatService.Answer reply(@Valid @RequestBody GeminiChatService.Question question) {
    return chat.reply(AuthService.current(), question);
  }
}
