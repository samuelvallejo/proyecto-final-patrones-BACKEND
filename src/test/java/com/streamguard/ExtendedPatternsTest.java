package com.streamguard;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.streamguard.ai.AiService;
import com.streamguard.core.ApiError;
import com.streamguard.core.PlatformService;
import com.streamguard.patterns.*;
import com.streamguard.patterns.AiToolkitFactory.ModerationAnalyzer;
import com.streamguard.patterns.AiToolkitFactory.Verdict;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ExtendedPatternsTest {
  private final ModerationPolicy policy = new ModerationPolicy.Builder().build();
  private final ModerationAnalyzer local =
      new AiToolkitFactory.LocalToolkit(new ObjectMapper()).moderation();

  @Test
  void singletonSharesOneConnectionPoolAcrossConcurrentCallers() throws Exception {
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var tasks = new ArrayList<Future<HttpClient>>();
      for (int index = 0; index < 100; index++)
        tasks.add(executor.submit(() -> SharedAiHttpClient.getInstance().client()));
      for (var task : tasks) assertSame(SharedAiHttpClient.getInstance().client(), task.get());
    }
    assertEquals(HttpClient.Version.HTTP_1_1, SharedAiHttpClient.getInstance().client().version());
    assertEquals(
        Duration.ofSeconds(8),
        SharedAiHttpClient.getInstance().client().connectTimeout().orElseThrow());
  }

  @Test
  void prototypeCopiesAllFieldsWithoutSharingMutableRestrictions() {
    var words = new ArrayList<>(List.of("restricted"));
    var original =
        new ModerationPolicy.Builder()
            .level("STRICT")
            .autoHide(false)
            .autoMute(true)
            .muteSeconds(60)
            .thresholds(.4, .7)
            .allowLinks(true)
            .blockedWords(words)
            .blockedTopics(List.of("private topic"))
            .build();
    var copied = original.copy();
    assertNotSame(original, copied);
    assertEquals(original, copied);
    words.clear();
    assertEquals(List.of("restricted"), copied.blockedWords());
    assertThrows(UnsupportedOperationException.class, () -> copied.blockedTopics().clear());
    assertEquals(original, copied.toBuilder().build());
    var changed = copied.toBuilder().blockedWords(List.of("another")).autoMute(false).build();
    assertEquals(List.of("restricted"), original.blockedWords());
    assertEquals(List.of("another"), changed.blockedWords());
    assertTrue(original.autoMute());
  }

  @Test
  void prototypeRegistryCannotLeakOneChannelsCustomizationsToAnother() {
    var presets = new ModerationPresets();
    var first = presets.copy("STRICT");
    var changed = first.toBuilder().blockedWords(List.of("private")).muteSeconds(90).build();
    var second = presets.copy("STRICT");
    assertNotSame(first, second);
    assertEquals(.4, second.reviewThreshold());
    assertEquals(.7, second.blockThreshold());
    assertEquals(300, second.muteSeconds());
    assertTrue(second.blockedWords().isEmpty());
    assertEquals(90, changed.muteSeconds());
    assertEquals(
        List.of("RELAXED", "BALANCED", "STRICT"),
        presets.all().stream().map(ModerationPolicy::level).toList());
    assertThrows(IllegalArgumentException.class, () -> presets.copy("unknown"));
    assertThrows(IllegalArgumentException.class, () -> presets.copy(null));
  }

  @Test
  void localRulesDecoratorAvoidsProviderCallsForRestrictedMessages() {
    ModerationAnalyzer remote = mock(ModerationAnalyzer.class);
    var decorated = new LocalRulesModerationDecorator(remote, local);
    assertEquals("LINK", decorated.analyze("https://example.com", policy).category());
    var custom = policy.toBuilder().blockedWords(List.of("restricted")).build();
    assertEquals("RESTRICTED", decorated.analyze("restricted", custom).category());
    verifyNoInteractions(remote);
  }

  @Test
  void decoratorsPreserveHealthyProviderVerdicts() {
    var expected = new Verdict("SAFE", 0, "Allowed", "OLLAMA");
    AtomicInteger failures = new AtomicInteger();
    var resilient =
        new FallbackModerationDecorator(
            (text, rules) -> expected, local, failure -> failures.incrementAndGet());
    assertSame(
        expected, new LocalRulesModerationDecorator(resilient, local).analyze("hello", policy));
    assertEquals(0, failures.get());
  }

  @Test
  void providerFailureKeepsOrdinaryChatVisibleAndRetainsChannelRestrictions() {
    AtomicInteger failures = new AtomicInteger();
    var resilient =
        new FallbackModerationDecorator(
            (text, rules) -> {
              throw new IllegalStateException("offline");
            },
            local,
            failure -> failures.incrementAndGet());
    assertEquals("SAFE", resilient.analyze("hello", policy).category());
    assertEquals("LOCAL_RULES", resilient.analyze("great stream", policy).provider());
    assertEquals("LINK", resilient.analyze("https://example.com", policy).category());
    assertEquals(3, failures.get());
  }

  @Test
  void concurrencyDecoratorReleasesCapacityAfterProviderFailure() {
    var slots = new Semaphore(1);
    var limited =
        new ConcurrencyLimitedModerationDecorator(
            (text, rules) -> {
              throw new IllegalStateException("offline");
            },
            slots);
    assertThrows(IllegalStateException.class, () -> limited.analyze("hello", policy));
    assertEquals(1, slots.availablePermits());
    slots.acquireUninterruptibly();
    ModerationAnalyzer remote = mock(ModerationAnalyzer.class);
    var resilient =
        new FallbackModerationDecorator(
            new ConcurrencyLimitedModerationDecorator(remote, slots), local, failure -> {});
    assertEquals("SAFE", resilient.analyze("hello", policy).category());
    verifyNoInteractions(remote);
    assertEquals(0, slots.availablePermits());
    slots.release();
  }

  @Test
  void concurrencyDecoratorReturnsCapacityAfterSuccessfulRequests() {
    var slots = new Semaphore(1);
    var limited = new ConcurrencyLimitedModerationDecorator(local, slots);
    for (int index = 0; index < 20; index++)
      assertEquals("SAFE", limited.analyze("hello", policy).category());
    assertEquals(1, slots.availablePermits());
  }

  @Test
  void fallbackHandlesMissingVerdictsButDoesNotSwallowFatalErrors() {
    assertEquals(
        "SAFE",
        new FallbackModerationDecorator((text, rules) -> null, local, failure -> {})
            .analyze("hello", policy)
            .category());
    var fatal = new AssertionError("fatal");
    var analyzer =
        new FallbackModerationDecorator(
            (text, rules) -> {
              throw fatal;
            },
            local,
            failure -> fail("Unexpected fallback"));
    assertSame(fatal, assertThrows(AssertionError.class, () -> analyzer.analyze("hello", policy)));
  }

  @Test
  void facadeComposesAuthorizedDashboardWithoutMutatingSubsystemData() {
    PlatformService platform = mock(PlatformService.class);
    AiService ai = mock(AiService.class);
    UUID user = UUID.randomUUID(), channel = UUID.randomUUID();
    var source = new HashMap<String, Object>(Map.of("channel", Map.of("name", "Creator")));
    when(platform.dashboard(user, channel)).thenReturn(source);
    when(ai.provider()).thenReturn("OLLAMA");
    when(ai.model()).thenReturn("test-model");
    when(ai.configured()).thenReturn(true);
    var dashboard =
        new CreatorStudioFacade(platform, ai, new ModerationPresets()).dashboard(user, channel);
    assertEquals(3, ((List<?>) dashboard.get("moderationPresets")).size());
    assertEquals(
        Map.of("provider", "OLLAMA", "model", "test-model", "configured", true),
        dashboard.get("assistantCapabilities"));
    assertEquals(Set.of("channel"), source.keySet());
    verify(platform).dashboard(user, channel);
  }

  @Test
  void facadeChecksAuthorizationBeforeAccessingOtherSubsystems() {
    PlatformService platform = mock(PlatformService.class);
    AiService ai = mock(AiService.class);
    ModerationPresets presets = mock(ModerationPresets.class);
    UUID user = UUID.randomUUID(), channel = UUID.randomUUID();
    when(platform.dashboard(user, channel)).thenThrow(new ApiError(403, "Denied"));
    doThrow(new ApiError(403, "Denied")).when(platform).owner(channel, user);
    var facade = new CreatorStudioFacade(platform, ai, presets);
    assertThrows(ApiError.class, () -> facade.dashboard(user, channel));
    assertThrows(ApiError.class, () -> facade.previewPreset(user, channel, "STRICT"));
    verifyNoInteractions(ai, presets);
    verify(platform, never()).policy(any());
  }

  @Test
  void facadePreviewPreservesRestrictionsAndDoesNotPersistChanges() {
    PlatformService platform = mock(PlatformService.class);
    UUID user = UUID.randomUUID(), channel = UUID.randomUUID();
    var current =
        policy.toBuilder()
            .blockedWords(List.of("restricted"))
            .blockedTopics(List.of("private"))
            .allowLinks(true)
            .build();
    when(platform.policy(channel)).thenReturn(current);
    var preview =
        new CreatorStudioFacade(platform, mock(AiService.class), new ModerationPresets())
            .previewPreset(user, channel, "STRICT");
    assertEquals("STRICT", preview.level());
    assertEquals(current.blockedWords(), preview.blockedWords());
    assertEquals(current.blockedTopics(), preview.blockedTopics());
    assertTrue(preview.allowLinks());
    assertEquals("BALANCED", current.level());
    verify(platform).owner(channel, user);
    verify(platform).policy(channel);
    verifyNoMoreInteractions(platform);
  }
}
