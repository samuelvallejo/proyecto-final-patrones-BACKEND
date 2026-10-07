package com.streamguard.patterns;

import com.streamguard.core.Db;
import java.util.UUID;

/** Factory Method: each creator supplies a concrete action to the common workflow. */
public abstract class ModerationActionCreator {
  public record Context(
      Db db,
      UUID channel,
      UUID message,
      UUID target,
      UUID actor,
      String reason,
      boolean automated,
      int muteSeconds) {}

  public interface Action {
    String status();

    void apply(Context context);
  }

  protected abstract Action createAction();

  public final String execute(Context c) {
    Action action = createAction();
    action.apply(c);
    return action.status();
  }

  public static class AllowCreator extends ModerationActionCreator {
    protected Action createAction() {
      return new AllowAction();
    }
  }

  public static class ReviewCreator extends ModerationActionCreator {
    protected Action createAction() {
      return new ReviewAction();
    }
  }

  public static class HideCreator extends ModerationActionCreator {
    protected Action createAction() {
      return new HideAction();
    }
  }

  public static class MuteCreator extends ModerationActionCreator {
    protected Action createAction() {
      return new MuteAction();
    }
  }

  public static class AllowAction implements Action {
    public String status() {
      return "VISIBLE";
    }

    public void apply(Context c) {
      c.db().exec("UPDATE chat_messages SET status='VISIBLE' WHERE id=?", c.message());
    }
  }

  public static class ReviewAction implements Action {
    public String status() {
      return "REVIEW";
    }

    public void apply(Context c) {
      c.db().exec("UPDATE chat_messages SET status='REVIEW' WHERE id=?", c.message());
      c.db()
          .exec(
              "INSERT INTO moderation_queue(message_id) VALUES (?) ON CONFLICT(message_id) DO"
                  + " NOTHING",
              c.message());
    }
  }

  public static class HideAction implements Action {
    public String status() {
      return "HIDDEN";
    }

    public void apply(Context c) {
      c.db().exec("UPDATE chat_messages SET status='HIDDEN' WHERE id=?", c.message());
      UUID action =
          c.db()
              .insert(
                  "INSERT INTO"
                      + " moderation_actions(channel_id,message_id,target_id,actor_id,action,reason,automated)"
                      + " VALUES (?,?,?,?,'HIDE',?,?) RETURNING id",
                  c.channel(),
                  c.message(),
                  c.target(),
                  c.actor(),
                  c.reason(),
                  c.automated());
      c.db()
          .exec(
              "INSERT INTO user_warnings(channel_id,user_id,action_id,reason) VALUES (?,?,?,?)",
              c.channel(),
              c.target(),
              action,
              c.reason());
      c.db()
          .exec(
              "INSERT INTO moderation_queue(message_id) VALUES (?) ON CONFLICT(message_id) DO"
                  + " NOTHING",
              c.message());
    }
  }

  public static class MuteAction extends HideAction {
    @Override
    public void apply(Context c) {
      super.apply(c);
      UUID action =
          c.db()
              .insert(
                  "INSERT INTO"
                      + " moderation_actions(channel_id,message_id,target_id,actor_id,action,reason,automated)"
                      + " VALUES (?,?,?,?,'MUTE',?,?) RETURNING id",
                  c.channel(),
                  c.message(),
                  c.target(),
                  c.actor(),
                  c.reason(),
                  c.automated());
      c.db()
          .exec(
              "INSERT INTO user_sanctions(channel_id,user_id,action_id,type,expires_at) VALUES"
                  + " (?,?,?,'MUTE',now()+make_interval(secs => ?))",
              c.channel(),
              c.target(),
              action,
              c.muteSeconds());
    }
  }
}
