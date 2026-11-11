# NoopsNet Account Link

Server-side NeoForge adapter for private Minecraft/Discord account linking.

Official NoopsNet source: https://noops.world/

## Build

Use JDK 21. The Gradle wrapper downloads pinned Minecraft/loader development dependencies.

```sh
./gradlew -p . build --console=plain
```

This is the Minecraft adapter, not the Discord service. Configure a compatible private linking service at loopback port 8766 and `/etc/noopsnet-account-link/client.json` with `endpoint` and `shared_secret` fields. Missing configuration disables linking safely. Never commit a real secret. Commands: `/discord link`, `/discord status`, `/discord confirm <discord_id>`, `/discord unlink confirm`.

## Licensing and provenance

Original code is MPL-2.0; see [LICENSE](LICENSE). Artwork and third-party components are covered separately in [NOTICE.md](NOTICE.md). [Published artifact provenance](PROVENANCE.json) identifies the original pack binaries covered by our permission grant. Historical binaries may retain an older All Rights Reserved metadata label; the explicit grant in NOTICE.md also applies to those identified versions.

This repository starts with a reviewed source snapshot. It intentionally contains no private server history, player data, accounts or deployment configuration. The source snapshot differs from the original binary build in license metadata and standalone build wiring; its build is not claimed byte-identical.
