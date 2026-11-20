package com.streamguard.patterns;

import com.streamguard.core.ApiError;
import com.streamguard.core.Db;
import com.streamguard.i18n.Messages;
import java.lang.annotation.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Proxy (dynamic protection + audit): a {@link java.lang.reflect.Proxy} with the same interface as
 * the real service. Every call first checks that the actor (always the first argument) is an
 * administrator. Calls marked {@link Audited} run in one transaction together with an audit_logs
 * row in the panel's format (ADMIN_ action and a label), so a change is never saved without its
 * record. The real service contains neither check.
 */
public final class AdminAuditProxy implements InvocationHandler {
  /** Marks an interface method that changes data and must be recorded. */
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.METHOD)
  public @interface Audited {
    String entity();

    String action();
  }

  private final Object target;
  private final Predicate<UUID> isAdmin;
  private final Db db;
  private final Function<Supplier<Object>, Object> inTransaction;

  private AdminAuditProxy(
      Object target, Predicate<UUID> isAdmin, Db db, Function<Supplier<Object>, Object> inTransaction) {
    this.target = target;
    this.isAdmin = isAdmin;
    this.db = db;
    this.inTransaction = inTransaction;
  }

  /**
   * Wrap the real service; callers only ever receive the returned proxy. The caller supplies how
   * to run work in one transaction, so this class does not depend on the framework.
   */
  public static <T> T protect(
      Class<T> contract,
      T target,
      Predicate<UUID> isAdmin,
      Db db,
      Function<Supplier<Object>, Object> inTransaction) {
    if (!contract.isInterface()) throw new IllegalArgumentException("Proxy needs an interface");
    Object proxy =
        Proxy.newProxyInstance(
            contract.getClassLoader(),
            new Class<?>[] {contract},
            new AdminAuditProxy(Objects.requireNonNull(target), isAdmin, db, inTransaction));
    return contract.cast(proxy);
  }

  @Override
  public Object invoke(Object proxy, Method method, Object[] args) {
    if (method.getDeclaringClass() == Object.class) return call(method, args);
    if (args == null || args.length == 0 || !(args[0] instanceof UUID actor))
      throw new IllegalStateException("Admin operations take the acting user first");
    if (!isAdmin.test(actor)) throw new ApiError(403, Messages.text("adminOnly"));
    Audited audited = method.getAnnotation(Audited.class);
    if (audited == null) return call(method, args);
    return inTransaction.apply(
        () -> {
          Object result = call(method, args);
          record(actor, audited, args);
          return result;
        });
  }

  private Object call(Method method, Object[] args) {
    try {
      return method.invoke(target, args);
    } catch (InvocationTargetException e) {
      if (e.getCause() instanceof RuntimeException runtime) throw runtime;
      if (e.getCause() instanceof Error error) throw error;
      throw new IllegalStateException(e.getCause());
    } catch (IllegalAccessException e) {
      throw new IllegalStateException(e);
    }
  }

  /** The first UUID after the actor is the target; the other arguments become the label. */
  private void record(UUID actor, Audited audited, Object[] args) {
    UUID entity = null;
    List<String> parts = new ArrayList<>();
    for (int i = 1; i < args.length; i++) {
      if (entity == null && args[i] instanceof UUID id) entity = id;
      else parts.add(String.valueOf(args[i]));
    }
    String label = parts.stream().collect(Collectors.joining(" · "));
    db.exec(
        "INSERT INTO audit_logs(actor_id,entity_type,entity_id,action,details) VALUES"
            + " (?,?,?,?,jsonb_build_object('label',?::text))",
        actor,
        audited.entity(),
        entity,
        "ADMIN_" + audited.action(),
        label.length() > 160 ? label.substring(0, 160) : label);
  }
}
