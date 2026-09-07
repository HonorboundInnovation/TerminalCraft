# Operations Projects

TerminalCraft now has a server-authoritative configuration layer that a setup wizard, graphical
control center, or terminal command can share. This is the boundary between the existing machine
APIs and future user interfaces.

## Stable contract

- `OperationsProject` is the versioned document. It records Easy or Advanced mode, RedNet defaults,
  stable device UUID bindings, required capabilities/integrations, and ordered typed deployment
  calls with compensating rollback calls.
- `OperationsProjectSavedData` stores projects per logical server. It enforces exact typed-principal
  ownership, bounded collections, optimistic revisions, and resilient per-project NBT loading.
- `OperationsDiscoverySnapshot` captures a bounded, immutable device/mod view for previews.
- `OperationsProjectValidator` produces stable issue codes suitable for inline GUI errors. Required
  devices, types, capabilities, methods, permissions, integrations, argument schemas, and rollback
  coverage are checked before changes can run.
- `OperationsDeploymentService` separates preview from apply, revalidates immediately before apply,
  rejects stale or replayed plans, and compensates attempted writes in reverse order after failure.
- `OperationsProjectRuntime` retains ready plans on the server. A client applies only an opaque plan
  UUID; it cannot replace the validated plan, caller identity, or project revision.

## GUI development sequence

1. Add bounded edit/save/preview/apply packets over `OperationsProjectRuntime`; never accept a plan
   supplied by a client during apply.
2. Build the Easy-mode wizard from discovery filters and validator issue codes. It should generate
   an ordinary `OperationsProject`, not a second configuration format.
3. Build the Advanced-mode project editor over the same document, exposing bindings, typed method
   arguments, rollback actions, and raw validation details.
4. Add live deployment progress and result views from `StepOutcome`, then device health views from
   refreshed discovery snapshots.
5. Add project import/export only through the bounded project codec and require a new server preview
   before imported content can be applied.

The project layer does not make optional integrations mandatory. Integration requirements are data
inside an individual project and are validated against the loaded-mod snapshot at preview time.
