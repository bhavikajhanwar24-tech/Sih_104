# F17 — gRPC extension plan (not implemented)

**Status:** REST + webhooks are the shipped public surface (F17). gRPC is a documented extension only.

## Why REST first

- Core banking / HRMS / SIEM teams already speak HTTPS + JSON.
- Webhooks cover push (`risk.level_changed`, `action.executed`, …) without a long-lived stream.
- OpenAPI + thin SDKs (`/sdk/python`, `/sdk/js`) cover the 80% path with low ops cost.

## When gRPC would help

| Need | REST today | gRPC extension |
|------|------------|----------------|
| High-QPS pre-transaction checks | `POST /transactions/request` | Unary `GateCheck` with binary framing |
| Live risk stream | Poll `GET /sessions/{id}/risk` or webhook | Server-streaming `SubscribeRisk` |
| Directory bulk sync | `POST /directory/sync` | Client-streaming upsert |

## Proposed package (future)

```protobuf
syntax = "proto3";
package sentinelvoice.integrations.v1;

service Integrations {
  rpc TransactionRequest (TransactionRequestIn) returns (TransactionDecision);
  rpc SubscribeRisk (SubscribeRiskIn) returns (stream RiskEvent);
  rpc DirectorySync (stream EmployeeUpsert) returns (DirectorySyncSummary);
}

message TransactionDecision {
  string decision = 1; // ALLOW | CHALLENGE | BLOCK
  string level = 2;
  repeated string reasons = 3;
  int32 gate_check_ms = 4;
}
```

## Auth & tenancy

- Same API keys (`sv_live_…`) via gRPC metadata `authorization: Bearer …`.
- Tenant resolved from key hash (identical to `ApiKeyAuthFilter`).
- Audit `actor_type=API_KEY` unchanged.

## Rollout sketch

1. Publish `.proto` under `sdk/proto/` and generate stubs.
2. Dual-run: gRPC gateway sidecar or spring-grpc on a separate port (e.g. `:9090`).
3. Keep REST as canonical; gRPC maps 1:1 to existing services (`IntegrationTransactionService`, `WebhookService` out of scope for streams).

**Do not implement gRPC in this release** unless the above becomes trivial relative to REST coverage already shipping.
