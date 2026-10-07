package com.streamguard.ai;

import com.fasterxml.jackson.databind.JsonNode;

public interface AiGateway {
  JsonNode generate(String instruction, JsonNode input, JsonNode schema);
}
