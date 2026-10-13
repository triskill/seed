# Owner handoff: sign an approved Google Play AAB

**Not a release approval.** Seed currently builds an **unsigned** AAB. Do not sign,
upload or distribute it until the [NO-GO gates](google-play-readiness.md) are closed.
The local unsigned AAB reported in the audit is a test artifact, not a candidate for
upload. App signing keys and upload keys have different roles: Play App Signing
holds the app signing key; your private **upload key** signs the AAB you submit.
Follow [Google's Play App Signing instructions](https://support.google.com/googleplay/android-developer/answer/9842756)
and confirm the chosen arrangement in your own Console.

## Owner-only setup

1. Confirm package `cz.trety.seed`, Play developer account access and required
   testing track. Choose who owns the upload key, its strong password and safe
   offline backup. If generating a new upload key, use the official Play App
   Signing/Android Studio key-generation flow; record its alias and certificate
   fingerprint in your private release records, **not this repository**.
2. Keep the upload keystore and passwords outside Git, cloud build logs, chat and
   the Android project. Set `umask 077` before creating any signed artifact.
   Never reuse a developer debug keystore. Designate a release operator to run
   the following commands on a trusted machine. Do not pass passwords as CLI
   arguments or environment variables that may leak into process listings/logs.
3. Complete the release gates and rebuild the exact final ARM64 runtime, then run
   `make release-check PLAY_ABI=arm64-v8a`. Check that the generated bundle is
   `android/app/build/outputs/bundle/release/app-release.aab`. If rebuilding the
   runtime or editing sources later, discard the old candidate and repeat the
   audit. Increment `versionCode` before any subsequent Play upload; this branch
   remains `versionCode=1`.

## Sign and verify (operator action only)

Use a local absolute path for the upload keystore and a separate output directory
outside this checkout. Replace the placeholders in your private shell session;
`jarsigner` prompts for the store/key passwords. **Never paste actual paths or
passwords into an issue or chat.**

```sh
umask 077
jarsigner -keystore /secure/private/upload-key.jks \
  -signedjar /secure/private/seed-open-testing.aab \
  android/app/build/outputs/bundle/release/app-release.aab UPLOAD_KEY_ALIAS
jarsigner -verify -verbose -certs /secure/private/seed-open-testing.aab
# Read verification output: require "jar verified." and your expected signer.
# jarsigner may exit 0 for an unsigned JAR; exit status alone is insufficient.
python3 scripts/check-play-artifact.py /secure/private/seed-open-testing.aab \
  --expected-abi arm64-v8a
sha256sum /secure/private/seed-open-testing.aab
```

If verification does not explicitly report `jar verified.` with the intended
upload signer, the artifact checker fails, the certificate does not match your
registered upload key, or Console reports an unexpected package/version,
**stop**; do not upload. Record signed AAB hash, upload-certificate fingerprint,
version code/name, ABI/rootfs hash and date in private release records. Upload
only the verified signed AAB to the approved **test** track in Console. Review its
Play-delivered size, device coverage and pre-launch findings; run the signed/
Play-delivered device acceptance from the [owner checklist](google-play-human-tasks.md).
Do not confuse a locally signed AAB with Play-generated split APKs or Play approval.

[Android's app-signing documentation](https://developer.android.com/studio/publish/app-signing)
explains the key roles and Play upload flow. The SHA-256 hash identifies one file;
it is not proof of policy eligibility, safe runtime behavior or legal clearance.
