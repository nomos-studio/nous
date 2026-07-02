# lib/maven — project-local Maven repository

This directory holds vendored Maven artefacts that are not available from
Maven Central or Clojars.

## org.erlang.otp:jinterface:1.16.0

Built from the Erlang/OTP 29.0.2 (master) source tree at
`lib/jinterface/java_src`. The Homebrew Erlang formula does not compile the
Java library, so the jar must be provided here.

**To rebuild** (run from the project root):

```
git clone --depth 1 --filter=blob:none --no-checkout https://github.com/erlang/otp.git /tmp/otp-src
cd /tmp/otp-src
git sparse-checkout init --cone
git sparse-checkout set lib/jinterface/java_src
git checkout master
javac -source 11 -target 11 -d /tmp/jinterface-classes \
  lib/jinterface/java_src/com/ericsson/otp/erlang/*.java
jar cf lib/maven/org/erlang/otp/jinterface/1.16.0/jinterface-1.16.0.jar \
  -C /tmp/jinterface-classes .
```

The version string **must match** the OTP release in use:
- OTP 29.0.2 → jinterface-1.16.0
- Check `otp/lib/jinterface/vsn.mk` for the exact version.

**Bundled JVM note**: when nous is packaged with a bundled JRE (jpackage or
similar), this jar is included automatically because Leiningen resolves it at
uberjar time. No additional packaging step is required.
