# JewFlix for Android TV

Version 1.4.1 adds a black, white, and gray TV interface, compact browsing, a live Arabic catalog, and playback/cache reliability improvements.

## Browsing

- Full-width Home rows, Continue Watching, Search, Categories, and Library.
- Text-only category controls with an underline for selection.
- Arabic category with paginated StarDima movies and series. Pages load on demand rather than downloading the entire catalog.
- Series artwork with an episode list on the right. The Arabic switch loads the provider's broadcast order separately from normal story episodes.
- Provider titles open directly through their provider pages. Listed availability does not guarantee a working video server.

## Playback and storage

- Torrent data uses a bounded rolling disk cache. Completed pieces behind the playback window are evicted while a small rewind window and container boundaries are retained.
- Direct HTTP playback uses a separate 64 MB disk cache.
- Playback sessions prevent stale cleanup from deleting a newer session. Local engine requests are authenticated.
- Source failures have retry/error handling. Requests without a torrent file index match the requested episode rather than selecting file zero.
- Loading uses a title shimmer while resolving and a title fill based on buffered duration.

Arabic provider numbering may combine stories or differ from the original release. Catalogs, available seasons, server formats, and playback depend on the external provider. No separate Arabic-audio/4K-video synchronization feature is included.

## Build

Requires JDK 17, Android SDK 34, and Go matching `engine/go.mod` for native engine changes.

```powershell
.\build_engine.bat
.\build_apk.bat
```

The Gradle wrapper uses Gradle 8.9. Configure `ANDROID_HOME` or a local `local.properties` file. Native engine binaries for ARMv7 and ARM64 are included. APK output is `app/build/outputs/apk/debug/app-debug.apk`.

## Checks

```powershell
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug
cd engine
go test ./...
go vet ./...
```

The Android build, unit tests, lint, and Go checks passed during development. Version 1.4.1 was installed on the test TV, and removal of the fixed Home preview was visually verified. Provider outages can prevent live playback validation independently of the app build.
