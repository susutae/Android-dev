# Mesh Device Search

Native Android app implementing the UDP device discovery protocol from `device search interface.docx`.

The app lets the user select an active IPv4 network interface and search on destination port 7000. Android automatically allocates the local UDP source port. It broadcasts:

```json
{"device":"mesh","method":"discover"}
```

Valid `mesh` / `response` replies are de-duplicated by their reported `ipAddr` and displayed with `nwMask`. A search listens for five seconds and can be cancelled.

## Build

Use Android Studio with JDK 17 and Android SDK 35, or run:

```bash
./gradlew test assembleDebug
```

Install `app/build/outputs/apk/debug/app-debug.apk` on an Android device connected to the same local network as the mesh devices. Network or access-point client isolation can prevent UDP broadcasts from reaching peers.
