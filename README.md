# Android SportLogger

An open source sports logger (tracker) for Wear OS.

You may ask why do we need another sports tracker?  Well, the number one reason I had for creating this app is that there none of the major sport tracking platforms support indoor activities.  As such, I wanted an app that could record my heart rate and create a TCX file.
My initial incarnation was a .NET Core application that would simply motify a TCX recorded by any sport logger to massage the data to represent a typical treadmill workout with the user entering distance and time.  The modified TCX data would have GPS coordinates removed and the track points would be spread out and modified to represent the total distance and time.

This process was cumbersome.  So, I decided to write my own WearOS app that would streamline this process.

## Features

* Records location, heart rate, and step counts from your Wear device (requires GPS/HRM on your Wear device)
* Android phone companion app with data can be synced, edited, and uploaded to your favorite activity tracking site (Strava, Endomondo, Smashrun)

## Future

* Integrate with sport tracker websites for storing data and analysis

Tagged v0.11

## Legacy migration oracle

`tools/legacy-fixtures/` contains the synthetic, executable contract for the
watch SQLite database. Its 18 fixtures use byte-pinned API26/API36 Android
formatter evidence, cover all 10 emitted decimal digit blocks, and model Thai
and other localized timestamps with Android's Gregorian semantics. They also
cover WAL snapshots, capability-selected schema preflight down to Android API
26, safe FTS4/FTS5 module probes, explicit N/A reporting for unsupported
generated-column/FTS detectors, corruption, precision, ordering, and idempotent
replay.

See [the legacy database contract](docs/legacy-database-contract.md) and
[`tools/legacy-fixtures/README.md`](tools/legacy-fixtures/README.md) for all
generation, verification, detector, determinism, unit, and 100,000-point gates.
