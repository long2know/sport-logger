# Phone history and TCX export

The phone app reads the immutable activity payloads already accepted into its private
`received-sport-activities` inbox. History loading runs off the main thread, retains only summary
rows, and reports empty, partial, corrupt, oversized, and I/O states without changing the source
payloads. Opening a row loads that one activity for detail and export.

## Export behavior

- **Save TCX** uses Android's create-document picker. The app writes only to the returned URI.
- **Share TCX** writes under private `files/shared-tcx/` and grants read access only through the
  app's narrow `FileProvider` path.
- `utilities.export.tcx.TcxWriter` streams UTF-8 XML to a caller-owned output stream and supports
  cancellation, bounded point counts, UTC timestamps, explicit optional values, and typed errors.
- `utilities.export.tcx.TcxExportSource` is an export-specific read boundary. A future canonical
  persistence adapter can implement it without depending on the legacy serialized model.

Garmin TCX v2 activity laps require values such as calories, intensity, and trigger method even
when the source did not record them. The export source keeps those fields absent. To remain
schema-valid without pretending they were measured, the writer uses a required format placeholder
only for an absent required field, shows a warning before export, appends a plain-language
limitation note, and writes a machine-readable SportLogger extension naming every placeholder.
Explicit zero values remain distinct from absent values. Optional unrecorded metrics are omitted.

## Data-access handoff

`PhoneActivityDataSource` isolates the UI from storage. `LegacyInboxActivityDataSource` is the
working private-inbox implementation; a later Room-backed implementation can replace it while
preserving the history/detail UI contract. `PhoneActivityStore` remains the receipt authority and
adds only bounded, read-only listing/loading APIs; acknowledgement and payload persistence behavior
is unchanged.
