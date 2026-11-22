# SafeVision

Android app (Kotlin, Jetpack Compose, Material 3) that detects inappropriate images and video on-device and blocks them inside the app: Home, Safe Browser, Protection Settings.

## Build
Push to GitHub. The "Build APK" workflow converts the models, downloads the blocklist, runs unit tests and builds the APK. Download `SafeVision-debug-apk` from the Actions run (zip, APK inside).

Local build: `pip install ... ; python tools/export_models.py ; cp assets_out/* app/src/main/assets/` then build in Android Studio (also needs `app/src/main/assets/blocklist.txt`, optional).

See MODELS.md for models, licenses and limitations.
