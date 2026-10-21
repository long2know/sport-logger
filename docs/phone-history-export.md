# Phone activity experience and TCX export

The Material 3 Compose phone app reads canonical activity summaries from
`PhoneActivityRepository`. On startup it first runs
`LegacyPhoneInboxBackfill.backfillDefault(...)` on a background dispatcher, retaining the private
serialized inbox and recording typed import receipts. Room invalidation is the durable refresh
authority; post-commit sync broadcasts are immediate hints.

History is paged without loading point graphs. Detail, phone-owned title/notes editing, persisted
weekly trends, canonical import/recovery state, per-measurement permission/capability evidence,
Health Connect state/actions, and truthful connector availability are available from the phone
screens. Process recreation keeps the selected canonical revision and edit draft.

## Export behavior

- **Save TCX** uses Android's create-document picker. The app writes only to the returned URI.
- **Share TCX** writes under private `files/shared-tcx/` and grants read access only through the
  app's narrow `FileProvider` path.
- `utilities.export.tcx.TcxWriter` streams UTF-8 XML to a caller-owned output stream and supports
  cancellation, bounded point counts, UTC timestamps, explicit optional values, and typed errors.
- `CanonicalTcxExportSource` adapts one immutable canonical revision and pages ordered points from
  Room. Explicit position presence preserves valid `0,0`.
- Connector pipelines can freeze phone metadata, persist it with their outbox, and reconstruct the
  same source after process death with
  `CanonicalTcxExportSource.fromRevision(repository, sessionId, revision, frozenMetadata)`.

Garmin TCX v2 activity laps require values such as calories, intensity, and trigger method even
when the source did not record them. The export source keeps those fields absent. To remain
schema-valid without pretending they were measured, the writer uses a required format placeholder
only for an absent required field, shows a warning before export, appends a plain-language
limitation note, and writes a machine-readable SportLogger extension naming every placeholder.
Explicit zero values remain distinct from absent values. Optional unrecorded metrics are omitted.

## Storage, sync, and external copies

`CanonicalPhoneDataSource` is the Compose-facing boundary around `PhoneActivityRepository`. It
retains one repository for the ViewModel lifetime, performs all Room/file work off the main thread,
and closes it from `onCleared`.

Deleting an activity uses the atomic canonical delete primitive and queues a bidirectional
phone-to-watch tombstone only after the local transaction commits. The confirmation explicitly
states that the phone and paired watch are affected. Health Connect and Endurain copies are never
silently deleted; each provider requires its own explicit action and owned-record receipt.

Health Connect controls use the real provider availability, permission launcher, durable operation
results, and explicit publish/update/delete/retry capabilities. Endurain remains visibly
not configured with no action until its separately reviewed publisher exposes a compiled UI
binding; the phone does not present a success-shaped placeholder button.
