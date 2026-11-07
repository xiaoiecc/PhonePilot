# In-app proxy

The Android app launches its own packaged Mihomo process and passes a loopback HTTP proxy explicitly to its OpenAI OkHttp client. It does not install a device VPN, modify system proxy settings, or need Clash or a computer gateway.

## Build integration

- Dependency: `implementation("org.yaml:snakeyaml:2.4")`. Version 2.4 fixes the UTF-16 surrogate-pair read-buffer boundary bug present in 2.3; emoji-bearing subscriptions require this fix.
- Bundle the official Android arm64 executable as `app/src/main/jniLibs/arm64-v8a/libmihomo.so`.
- Enable `android.packaging.jniLibs.useLegacyPackaging = true` and application `android:extractNativeLibs="true"`. The executable must be loaded from `applicationInfo.nativeLibraryDir`, not downloaded into writable app storage.
- Register `.proxy.ProxySettingsActivity` as non-exported. Open it using an explicit intent from settings.
- Before each new API client: `val proxy = ProxyManager.getInstance(context); proxy.ensureStarted(); val http = proxy.configureClient(OkHttpClient.Builder()).build()`.
- A previously enabled proxy that cannot start fails the request. It never silently falls back to direct access. Explicitly stopping it enables direct access.

## User flow

Open in-app proxy settings, paste an HTTPS Clash/Mihomo YAML subscription, and fetch. Seattle is the initial preference when present; otherwise the first supported node is selected. Choose a node and connect. The OpenAI connectivity button sends no API key and treats the official API's expected HTTP 401 as network reachability, not proof of model/account entitlement.

Only explicit `proxies` lists are supported. Provider-only YAML, encoded URI subscriptions, certificate-file references, chained proxies and external executable plugins are rejected. Refreshing a live subscription does not silently change the running connection; reconnect to apply changes. Starting/stopping the proxy during a task may interrupt its network calls; configure it before starting tasks.

The subscription URL and complete node definitions use Android encrypted preferences with no plaintext fallback. Generated runtime credentials/config live in `noBackupFilesDir/proxy` only during operation. No subscription global listeners, rules, controllers, DNS configuration or remote providers are imported. Core output is drained and discarded. A random local HTTP proxy password prevents other apps from using the listener anonymously. The listener binds only to `127.0.0.1:17890`; an occupied port causes a failure without touching the owning process. Core processes may be stopped by Android when the app is killed; the next API request restarts an enabled connection.

## Upstream and licensing

- [Official Mihomo releases](https://github.com/MetaCubeX/mihomo/releases).
- [General configuration and authentication](https://wiki.metacubex.one/en/config/general/).
- [Mihomo GPL-3.0 license](https://github.com/MetaCubeX/mihomo/blob/Meta/LICENSE).

Mihomo is distributed separately from the original project's MIT-licensed source and is GPL-3.0 licensed. Distributing an APK with the core requires accompanying GPL license and corresponding-source availability for the exact bundled binary (including any local changes). Pin the upstream release, verify its published SHA-256 before unpacking, and include the exact source/version in distribution notices. Do not check private subscriptions or API keys into the repository or APK.
