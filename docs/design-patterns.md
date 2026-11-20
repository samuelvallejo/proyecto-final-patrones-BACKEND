# Design patterns in StreamGuard

The Java backend implements ten design patterns. The original Builder, Factory Method,
Abstract Factory, Adapter and Bridge remain in use. Singleton, Prototype, Decorator and
Facade extend those workflows without changing the PostgreSQL schema. Proxy controls access to the
AI provider and to content moderation.

All ten are written by hand in plain Java: the classes in `patterns/` import nothing from Spring
and are created with `new` in `PatternAssembly`, a hand-written assembly class. Spring only creates
the application services (controllers, repositories and services) that use them.
`PatternAssemblyTest` fails if a Spring import or annotation appears in that package.

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
| Proxy | `AiGatewayProxy`, `AdminAuditProxy` | Control access to the AI provider and to the admin content operations. |

## Proxy: controlled access to the AI provider and to content moderation

Two proxies stand in front of a real object with the same interface, so callers cannot tell them
apart from the object itself.

**`AiGatewayProxy` (protection proxy).** It implements `AiGateway`, like `OllamaAdapter` and
`GeminiAdapter`. `PatternAssembly.aiGateway` creates one per `AiService` around the configured
adapter, and `AiService` passes it to the Abstract Factory toolkits, so no external AI call
bypasses it. Before forwarding, it checks two rules that administrators set in
`platform_settings` (`ai_enabled`, `ai_hourly_limit`, sliding one-hour window). A refused call
throws `AccessDenied`; the existing fallback decorator and editorial fallback answer with local
rules, exactly as when the provider is down. `stats()` reports forwarded and refused calls.

**`AdminAuditProxy` (dynamic protection + audit proxy).** A `java.lang.reflect.Proxy` over the
`ContentModeration` interface. `ContentModerationController` only receives the proxy, built in
`PatternAssembly.contentModeration`. Each call checks the administrator role with
`RoleService.isAdmin`; methods annotated `@Audited` run in one transaction with an `audit_logs`
row in the panel's format (`ADMIN_TAKEDOWN`, `ADMIN_RESTORE`, `ADMIN_AI_RULES` and a label), so
they also appear in the panel's activity log. `ContentModerationService` contains neither check.
The transaction is passed in as a function, so the proxy imports nothing from Spring.

Takedown is logical: `removed_at`, `removed_by` and `removed_reason` hide a stream or clip from
public lists, explore, stream pages and media access; the owner and administrators still see it
and it can be restored. Difference with Decorator: a decorator adds behavior around a call that
always happens; these proxies decide whether the real object is reached at all.

## Singleton: shared AI transport

Both external AI adapters obtain their `HttpClient` from `SharedAiHttpClient.getInstance()`.
The initialization-on-demand holder guarantees thread-safe lazy initialization without
locking every access. The shared client uses HTTP/1.1 and an eight-second connection
timeout; each adapter retains its own request timeout, credentials and serialization.
Credentials, headers, user sessions and moderation policies are never stored in the singleton.
The instance is shared within one JVM, not across separate Railway replicas.

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
