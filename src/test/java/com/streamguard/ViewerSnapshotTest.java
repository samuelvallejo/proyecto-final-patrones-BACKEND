package com.streamguard;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.streamguard.core.Db;
import com.streamguard.core.PlatformService;
import com.streamguard.live.LiveHub;
import com.streamguard.patterns.ModerationPolicy;
import java.util.*;
import org.junit.jupiter.api.Test;

class ViewerSnapshotTest {
  @Test
  void dashboardUsesCurrentAudienceAndResetsEndedStreams() {
    Db db = mock(Db.class);
    LiveHub hub = mock(LiveHub.class);
    PlatformService platform = spy(new PlatformService(db, hub));
    UUID user = UUID.randomUUID(), channel = UUID.randomUUID(), streamId = UUID.randomUUID();
    Map<String, Object> stream = new HashMap<>(Map.of("id", streamId, "status", "LIVE"));
    doNothing().when(platform).manager(channel, user);
    doReturn(new ModerationPolicy.Builder().build()).when(platform).policy(channel);
    when(db.optional(anyString(), eq(channel))).thenReturn(Optional.of(stream));
    when(hub.viewers(streamId)).thenReturn(7, 1);
    assertEquals(7, ((Map<?, ?>) platform.dashboard(user, channel).get("stream")).get("viewers"));
    assertEquals(1, ((Map<?, ?>) platform.dashboard(user, channel).get("stream")).get("viewers"));
    stream.put("status", "ENDED");
    assertEquals(0, ((Map<?, ?>) platform.dashboard(user, channel).get("stream")).get("viewers"));
    verify(hub, times(2)).viewers(streamId);
  }
}
