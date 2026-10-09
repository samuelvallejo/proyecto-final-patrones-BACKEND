package com.streamguard.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.streamguard.auth.AuthService;
import com.streamguard.auth.FieldCrypto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;

/** Physical location is opt-in, separate from the channel URL, and expires when updates stop. */
@RestController
@RequestMapping("/api/channels/{channel}/location")
public class ChannelLocationController {
  private final Db db; private final PlatformService platform; private final FieldCrypto crypto; private final ObjectMapper json;
  public ChannelLocationController(Db db, PlatformService platform, FieldCrypto crypto, ObjectMapper json) {
    this.db=db; this.platform=platform; this.crypto=crypto; this.json=json;
  }
  public record Location(boolean shared, @DecimalMin("-90") @DecimalMax("90") Double latitude,
      @DecimalMin("-180") @DecimalMax("180") Double longitude) {}
  @PutMapping
  public Map<?, ?> update(@PathVariable UUID channel, @Valid @RequestBody Location body) throws Exception {
    platform.owner(channel, AuthService.current());
    if (body.shared() && (body.latitude()==null || body.longitude()==null || !Double.isFinite(body.latitude()) || !Double.isFinite(body.longitude())))
      throw new ApiError(400, com.streamguard.i18n.Messages.text("operationFailed"));
    String encrypted = body.shared() ? crypto.encrypt(json.writeValueAsString(Map.of("latitude", body.latitude(), "longitude", body.longitude()))) : null;
    db.exec("UPDATE channels SET location_shared=?,location_encrypted=?,location_updated_at=now() WHERE id=?", body.shared(), encrypted, channel);
    return Map.of("ok", true);
  }
  @GetMapping
  public Map<?, ?> read(@PathVariable UUID channel) throws Exception {
    AuthService.current();
    var row = db.optional("SELECT location_encrypted FROM channels WHERE id=? AND location_shared=true AND location_updated_at>now()-interval '1 minute'", channel);
    if (row.isEmpty() || row.get().get("location_encrypted")==null) return Map.of("shared", false);
    var result = new HashMap<String, Object>(json.readValue(crypto.decrypt(row.get().get("location_encrypted").toString()), Map.class));
    result.put("shared",true); return result;
  }
}
