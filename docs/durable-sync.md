# Durable watch-phone sync

SportLogger transfers canonical protobuf files over Wear Data Layer channels. Room
repositories remain the only durable authority; channel staging files are transient and are
deleted after each attempt.

## Watch activity flow

1. Recording reserves its stable completion identity with
   `WatchActivityRepository.reserveCompletion`.
2. Recording finalizes persisted samples with
   `finishSessionFromPersistedSamples`.
3. The resulting immutable outbox is handed to
   `WatchSyncFinishHook.onCompletionCommitted`.
4. Reachable-phone capability changes trigger an immediate whole-file channel attempt.
   A persisted `JobScheduler` job is the fallback for offline operation, reboot, process
   death, and lost acknowledgements.
5. The phone calls `PhoneActivityRepository.acceptActivityTransfer` before sending the
   repository's exact stored `CommitAck` bytes.
6. The watch calls `WatchActivityRepository.acceptCommitAck`; only a matching transfer,
   session, revision, and payload hash can reach `COMMITTED`.

The canonical activity outbox states are `READY`, `SENDING`,
`AWAITING_COMMIT_ACK`, `RETRYABLE`, and `COMMITTED`. `WatchSyncEvents` maps these
to `QUEUED`, `TRANSFERRING`, `FAILED`, and `SYNCED` for presentation and emits a
package-scoped refresh broadcast after every transport transition.

## Tombstones

Watch- and phone-origin tombstones use the same whole-file channel and exact-ACK pattern.
Both repositories deterministically order concurrent tombstones, retain exact receipts, return
the same ACK for duplicate delivery, and suppress covered activity revisions so a delayed
activity cannot resurrect deleted data.

Phone deletion code must pass the repository's `TombstoneOutboxItem` to
`PhoneTombstoneSyncHook.onDeletionCommitted`. Both apps expose `requestRetry(Context)`
on their sync hook; this only requests transport work and never creates parallel sync state.

## Data Layer contract

- Phone receiver capability: `sportlogger_phone_activity_sync_v1`
- Watch tombstone receiver capability: `sportlogger_watch_tombstone_sync_v1`
- Activity channel root: `/sportlogger/sync/v1/activity`
- Tombstone channel root: `/sportlogger/sync/v1/tombstone`
- Exact commit ACK message path: `/sportlogger/sync/v1/activity-commit-ack`

Legacy `/sportactivity` asset envelopes and their bounded deserialization path remain readable
for migration. New canonical transfers never use Java serialization as authority.

## UI refresh

Phone UIs should observe `PhoneActivityRepository.observeChanges` and then query canonical
summaries/details. `PhoneSyncEvents.ACTION_CANONICAL_ACTIVITY_CHANGED` is an immediate
refresh hint sent only after the repository transaction commits. Received phone activities are
truthfully `COMMITTED`; watch-side queued or retrying states are not fabricated on the phone.

## Device verification

Unit and component tests exercise real Room transactions with fake Data Layer boundaries.
An end-to-end paired Wear OS and Android device run is still required to validate Google Play
services routing, channel delivery, and capability discovery on physical peers.
