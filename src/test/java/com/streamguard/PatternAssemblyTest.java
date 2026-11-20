package com.streamguard;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.streamguard.ai.AiGateway;
import com.streamguard.patterns.AiToolkitFactory;
import com.streamguard.patterns.AiToolkitFactory.Verdict;
import com.streamguard.patterns.ModerationPolicy;
import com.streamguard.patterns.ModerationPresets;
import com.streamguard.patterns.PatternAssembly;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** The patterns are written by hand: no Spring inside them, and they are assembled with new. */
class PatternAssemblyTest {
  private static final Path PATTERNS = Path.of("src/main/java/com/streamguard/patterns");

  @Test
  void patternClassesDoNotDependOnSpring() throws IOException {
    try (Stream<Path> files = Files.list(PATTERNS)) {
      List<Path> sources = files.filter(file -> file.toString().endsWith(".java")).toList();
      assertTrue(sources.size() >= 13, "pattern sources should be found");
      for (Path source : sources) {
        String code = Files.readString(source);
        assertFalse(code.contains("org.springframework"), source + " imports Spring");
        assertFalse(code.matches("(?s).*@(Component|Service|Bean|Configuration|Autowired)\\b.*"), source + " uses a Spring annotation");
      }
    }
  }

  @Test
  void abstractFactoryPicksTheToolkitFamilyByProvider() {
    var json = new ObjectMapper();
    AiGateway gemini = mock(AiGateway.class), ollama = mock(AiGateway.class);
    assertInstanceOf(AiToolkitFactory.GeminiToolkit.class, PatternAssembly.toolkit("gemini", gemini, ollama, json));
    assertInstanceOf(AiToolkitFactory.OllamaToolkit.class, PatternAssembly.toolkit("OLLAMA", gemini, ollama, json));
    assertInstanceOf(AiToolkitFactory.LocalToolkit.class, PatternAssembly.toolkit("LOCAL_RULES", gemini, ollama, json));
  }

  @Test
  void decoratorChainFallsBackToLocalRulesWhenTheProviderFails() {
    var local = new AiToolkitFactory.LocalToolkit(new ObjectMapper()).moderation();
    var failures = new java.util.ArrayList<RuntimeException>();
    var chain =
        PatternAssembly.moderationChain(
            (text, policy) -> { throw new IllegalStateException("provider down"); },
            local,
            new Semaphore(1),
            failures::add);
    Verdict verdict = chain.analyze("hello everyone", new ModerationPolicy.Builder().build());
    assertEquals("SAFE", verdict.category());
    assertEquals(1, failures.size());
  }

  @Test
  void prototypeRegistryIsSharedButHandsOutIndependentCopies() {
    assertSame(ModerationPresets.shared(), ModerationPresets.shared());
    assertNotSame(ModerationPresets.shared().copy("STRICT"), ModerationPresets.shared().copy("STRICT"));
  }
}
