package com.streamguard.core;

import com.streamguard.i18n.Messages;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class Db {
  public final JdbcTemplate jdbc;

  public Db(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public List<Map<String, Object>> list(String sql, Object... args) {
    var rows = jdbc.queryForList(sql, args);
    rows.forEach(
        row ->
            row.replaceAll(
                (key, value) ->
                    value instanceof java.sql.Timestamp time
                        ? time.toInstant().toString()
                        : value));
    return rows;
  }

  public Map<String, Object> one(String sql, Object... args) {
    var rows = list(sql, args);
    if (rows.isEmpty()) throw new ApiError(404, Messages.text("dbOneText01"));
    return rows.getFirst();
  }

  public Optional<Map<String, Object>> optional(String sql, Object... args) {
    return list(sql, args).stream().findFirst();
  }

  public int exec(String sql, Object... args) {
    return jdbc.update(sql, args);
  }

  public UUID insert(String sql, Object... args) {
    return (UUID) one(sql, args).get("id");
  }

  public long count(String sql, Object... args) {
    return jdbc.queryForObject(sql, Long.class, args);
  }

  public static UUID id(Object value) {
    return value instanceof UUID u ? u : UUID.fromString(value.toString());
  }
}
