# Release keystore

`shkolo-release.jks` is **not committed** (it is in `.gitignore`). It signs both
`phone` and `wear`, which is mandatory: the Wearable Data Layer only works when
the phone and watch apps are signed with the same certificate.

## If you need to recreate it

```bash
keytool -genkeypair -v -keystore keystore/shkolo-release.jks \
  -storepass <password> -keypass <password> -alias shkolo \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=Shkolo Wear, O=Shkolo, C=BG"
```

Then either edit the default in both `*/build.gradle.kts` files or pass:

```bash
./gradlew :phone:assembleRelease :wear:assembleRelease \
  -PshkoloStorePass=<password> -PshkoloKeyPass=<password>
```

## Warning

Recreating the keystore changes the app's signing identity, so an existing
install must be uninstalled first — and if the phone and watch end up on
different keys, they will not be able to talk to each other.
