package com.streamguard.patterns;

import com.streamguard.core.Db;
import com.streamguard.i18n.Messages;
import com.streamguard.live.LiveHub;
import java.util.*;
import org.springframework.stereotype.Component;

/** Bridge: notice types evolve separately from delivery implementations. */
public final class NotificationBridge {
  public interface Delivery {
    void deliver(UUID user, String type, String title, String body);
  }

  public abstract static class Notice {
    protected final Delivery delivery;

    protected Notice(Delivery delivery) {
      this.delivery = delivery;
    }

    public abstract void send(UUID recipient, String body);
  }

  public static class ModerationNotice extends Notice {
    public ModerationNotice(Delivery delivery) {
      super(delivery);
    }

    public void send(UUID recipient, String body) {
      delivery.deliver(
          recipient, "MODERATION", Messages.text("notificationBridgeSendText01"), body);
    }
  }

  public static class ClipNotice extends Notice {
    public ClipNotice(Delivery delivery) {
      super(delivery);
    }

    public void send(UUID recipient, String body) {
      delivery.deliver(recipient, "CLIP", Messages.text("notificationBridgeSendText02"), body);
    }
  }

  public static class DatabaseDelivery implements Delivery {
    protected final Db db;

    public DatabaseDelivery(Db db) {
      this.db = db;
    }

    public void deliver(UUID user, String type, String title, String body) {
      db.exec(
          "INSERT INTO notifications(user_id,type,title,body) VALUES (?,?,?,?)",
          user,
          type,
          title,
          body);
    }
  }

  @Component
  public static class RealtimeDelivery extends DatabaseDelivery {
    private final LiveHub hub;

    public RealtimeDelivery(Db db, LiveHub hub) {
      super(db);
      this.hub = hub;
    }

    @Override
    public void deliver(UUID user, String type, String title, String body) {
      super.deliver(user, type, title, body);
      hub.toUser(user, Map.of("type", "notice", "title", title, "body", body));
    }
  }
}
