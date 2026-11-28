# VPN compile-only platform signatures

These four handwritten Java declarations let GWH compile its concrete
`GwhNetworkAgent` subclass against the hidden Android 16 platform constructor.
The signature and abstract superclass modifier were checked against the locally
collected BZF4 `framework-connectivity.jar`; these files contain no firmware code.

`compileVpnApiStubs` and `vpnApiStubsJar` build a temporary JAR under `app/build/`.
It is a `compileOnly` dependency and must never be packaged into the APK. At
runtime, the superclass and argument types come from the device boot classpath.
The module still uses the existing traditional Xposed API 82 entrypoint.

`NetworkAgent` is abstract even though its callback methods have implementations.
Do not instantiate it directly with reflection; instantiate `GwhNetworkAgent`.
