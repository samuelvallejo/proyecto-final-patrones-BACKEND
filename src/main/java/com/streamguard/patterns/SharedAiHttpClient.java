package com.streamguard.patterns;

import java.net.http.HttpClient;
import java.time.Duration;

/** Singleton: one thread-safe connection pool for the external AI adapters in this JVM. */
public final class SharedAiHttpClient {
  private final HttpClient client;

  private SharedAiHttpClient() {
    client =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(8))
            .build();
  }

  private static final class Holder {
    private static final SharedAiHttpClient INSTANCE = new SharedAiHttpClient();
  }

  public static SharedAiHttpClient getInstance() {
    return Holder.INSTANCE;
  }

  public HttpClient client() {
    return client;
  }
}
