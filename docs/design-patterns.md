# Design patterns in StreamGuard

The Java backend implements nine design patterns. The original Builder, Factory Method,
Abstract Factory, Adapter and Bridge remain in use. Singleton, Prototype, Decorator and
Facade extend those workflows without changing the PostgreSQL schema.

| Pattern | Production implementation | Responsibility |
| --- | --- | --- |
| Builder | `ModerationPolicy.Builder` | Build validated, immutable channel rules. |
| Factory Method | `ModerationActionCreator` | Create allow, review, hide and mute actions. |
| Abstract Factory | `AiToolkitFactory` | Create compatible moderation and editorial tools. |
| Adapter | `OllamaAdapter`, `GeminiAdapter` | Translate the internal AI gateway contract. |
| Bridge | `NotificationBridge` | Separate notice types from database/realtime delivery. |
| Singleton | `SharedAiHttpClient` | Reuse one thread-safe HTTP connection pool in each JVM. |
| Prototype | `Prototype<T>`, `ModerationPolicy`, `ModerationPresets` | Copy a policy template before customization. |
| Decorator | `ModerationAnalyzerDecorator` and concrete decorators | Compose local restrictions, capacity limits and failure recovery. |
| Facade | `CreatorStudioFacade` | Coordinate authorized dashboard reads, preset previews and public AI capabilities. |

## Singleton: shared AI transport

Both external AI adapters obtain their `HttpClient` from `SharedAiHttpClient.getInstance()`.
The initialization-on-demand holder guarantees thread-safe lazy initialization without
locking every access. The shared client uses HTTP/1.1 and an eight-second connection
timeout; each adapter retains its own request timeout, credentials and serialization.
Credentials, headers, user sessions and moderation policies are never stored in the singleton.
The instance is shared within one JVM, not across separate Render containers.

## Prototype: independent moderation policies

`ModerationPolicy.copy()` returns a distinct, equal policy. `toBuilder()` supports editing
the copy. Lists are defensively copied and immutable, so sharing an immutable list cannot
let one channel modify another channel's rules. Validation runs in the record constructor
as well as through Builder; direct construction cannot bypass its invariants.

`ModerationPresets` keeps immutable prototypes for the three levels:

| Level | Review threshold | Block threshold | Initial mute duration |
| --- | --- | --- | --- |
| RELAXED | 0.70 | 0.95 | 300 seconds |
| BALANCED | 0.55 | 0.85 | 300 seconds |
| STRICT | 0.40 | 0.70 | 300 seconds |

All templates enable automatic hiding and leave automatic muting disabled. The creator
can change those choices before saving. Previewing a template retains the channel's blocked
words, blocked topics and link permission. It performs no database writes.

## Decorator: moderation pipeline

`AiService.moderate()` builds this chain around the selected factory's analyzer:

1. `LocalRulesModerationDecorator` applies channel restrictions first. Restricted messages
   never need a provider request.
2. `FallbackModerationDecorator` recovers runtime/provider failures with the same local
   rules. Ordinary messages remain `SAFE` when the model is unavailable.
3. `ConcurrencyLimitedModerationDecorator` reserves the semaphore already shared with
   editorial requests and releases it in a `finally` block.
4. The selected analyzer calls the appropriate adapter and validates the model response.

Each decorator implements the existing `ModerationAnalyzer` interface, so factories and
adapters keep their contracts. The fallback does not swallow JVM errors. Database audit
writes happen after analysis, outside the recovery chain, and retain the allowed `LOCAL`
or `SUCCEEDED` request status. Provider failures are logged without message text or secrets.

## Facade: creator studio

`PlatformController` delegates dashboard and preset-preview requests to
`CreatorStudioFacade`. The facade coordinates `PlatformService`, `ModerationPresets` and
`AiService`; clients do not need to orchestrate those subsystems themselves.

| Endpoint | Authorization | Result |
| --- | --- | --- |
| `GET /api/channels/{channel}/dashboard` | Channel owner or moderator | Existing dashboard plus `moderationPresets` and `assistantCapabilities`. |
| `GET /api/channels/{channel}/policy-presets/{level}` | Channel owner | Independent policy preview with existing channel restrictions. |
| `PUT /api/channels/{channel}/policy` | Channel owner | Existing validated save operation, explicitly confirmed in the UI. |

Authorization happens before template or AI-capability data is assembled. The facade
returns only provider name, model name and configuration state, never provider credentials.
Existing dashboard keys and save endpoints remain compatible.

## Verification

`ExtendedPatternsTest` checks concurrent singleton access, clone isolation, delegation,
capacity release, fallback behavior and facade authorization. `ModerationPolicyContractTest`
reads `src/test/resources/patterns/moderation-contracts.json`: 96 valid configurations and
44 invalid boundaries. The frontend consumes the same policy contracts independently.
`PlatformIntegrationTest.policyPresetsRequireOwnershipAndDoNotSaveUntilConfirmed` checks
the real HTTP, session, PostgreSQL, preview and save flow with synthetic local accounts.

Run `mvn verify` against the dedicated PostgreSQL test database. No test sends credentials
or synthetic accounts to production.
