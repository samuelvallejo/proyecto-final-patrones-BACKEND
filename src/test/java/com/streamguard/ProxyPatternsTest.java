package com.streamguard;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.streamguard.ai.AiGateway;
import com.streamguard.core.ApiError;
import com.streamguard.core.Db;
import com.streamguard.patterns.AdminAuditProxy;
import com.streamguard.patterns.AdminAuditProxy.Audited;
import com.streamguard.patterns.AiGatewayProxy;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import java.util.function.Function;
import java.util.function.Supplier;
import org.mockito.ArgumentCaptor;

public class ProxyPatternsTest {
  private final ObjectMapper json = new ObjectMapper();
  private final JsonNode empty = json.createObjectNode();

  private static AiGatewayProxy.AccessRules rules(boolean enabled, int limit) {
    return new AiGatewayProxy.AccessRules() {
      public boolean enabled() {
        return enabled;
      }

      public int hourlyLimit() {
        return limit;
      }
    };
  }

  @Test
  void aiProxyForwardsToTheRealAdapterWhenAllowed() {
    AiGateway real = mock(AiGateway.class);
    JsonNode answer = json.createObjectNode().put("category", "SAFE");
    when(real.generate("task", empty, empty)).thenReturn(answer);
    var proxy = new AiGatewayProxy(real, rules(true, 10));

    assertSame(answer, proxy.generate("task", empty, empty));
    assertEquals(1, proxy.stats().forwarded());
    assertEquals(1, proxy.stats().lastHour());
  }

  @Test
  void aiProxyNeverContactsTheProviderWhenAnAdminSwitchedItOff() {
    AiGateway real = mock(AiGateway.class);
    var proxy = new AiGatewayProxy(real, rules(false, 10));

    var refused = assertThrows(AiGatewayProxy.AccessDenied.class, () -> proxy.generate("t", empty, empty));
    assertEquals("DISABLED", refused.reason);
    verifyNoInteractions(real);
    assertEquals(1, proxy.stats().refused());
  }

  @Test
  void aiProxyEnforcesTheHourlyLimitWithASlidingWindow() {
    AiGateway real = mock(AiGateway.class);
    AtomicLong now = new AtomicLong(1_000_000);
    var proxy = new AiGatewayProxy(real, rules(true, 2), now::get);

    proxy.generate("a", empty, empty);
    proxy.generate("b", empty, empty);
    var refused = assertThrows(AiGatewayProxy.AccessDenied.class, () -> proxy.generate("c", empty, empty));
    assertEquals("HOURLY_LIMIT", refused.reason);
    verify(real, times(2)).generate(anyString(), any(), any());

    now.addAndGet(3_600_001);
    proxy.generate("d", empty, empty);
    verify(real, times(3)).generate(anyString(), any(), any());
  }

  /** A tiny contract to exercise the dynamic proxy without the real admin service. */
  public interface Sample {
    String read(UUID actor);

    @Audited(entity = "USER", action = "STATUS")
    void change(UUID actor, UUID target, String reason);
  }

  private static final Function<Supplier<Object>, Object> NOW = Supplier::get;

  @Test
  void adminProxyRejectsNonAdminsBeforeReachingTheService() {
    Db db = mock(Db.class);
    Sample real = mock(Sample.class);
    Sample proxy = AdminAuditProxy.protect(Sample.class, real, user -> false, db, NOW);

    var error = assertThrows(ApiError.class, () -> proxy.read(UUID.randomUUID()));
    assertEquals(403, error.status);
    verifyNoInteractions(real);
  }

  @Test
  void adminProxyDelegatesReadsWithoutAnAuditRecord() {
    Db db = mock(Db.class);
    Sample real = mock(Sample.class);
    UUID admin = UUID.randomUUID();
    when(real.read(admin)).thenReturn("data");
    Sample proxy = AdminAuditProxy.protect(Sample.class, real, user -> true, db, NOW);

    assertEquals("data", proxy.read(admin));
    verify(db, never()).exec(anyString(), any(Object[].class));
  }

  @Test
  void adminProxyRecordsEveryChangeWithItsTargetAndReason() {
    Db db = mock(Db.class);
    Sample real = mock(Sample.class);
    UUID admin = UUID.randomUUID(), target = UUID.randomUUID();
    Sample proxy = AdminAuditProxy.protect(Sample.class, real, user -> true, db, NOW);

    proxy.change(admin, target, "spam");

    verify(real).change(admin, target, "spam");
    var args = ArgumentCaptor.forClass(Object[].class);
    verify(db).exec(contains("audit_logs"), args.capture());
    Object[] values = args.getValue();
    assertEquals(admin, values[0]);
    assertEquals("USER", values[1]);
    assertEquals(target, values[2]);
    assertEquals("ADMIN_STATUS", values[3]);
    assertEquals("spam", values[4]);
  }

  @Test
  void adminProxyDoesNotAuditAFailedChange() {
    Db db = mock(Db.class);
    Sample real = mock(Sample.class);
    doThrow(new ApiError(409, "conflict")).when(real).change(any(), any(), any());
    Sample proxy = AdminAuditProxy.protect(Sample.class, real, user -> true, db, NOW);

    var error = assertThrows(ApiError.class, () -> proxy.change(UUID.randomUUID(), UUID.randomUUID(), "x"));
    assertEquals(409, error.status);
    verify(db, never()).exec(anyString(), any(Object[].class));
  }
}
