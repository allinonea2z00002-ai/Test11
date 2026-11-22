# SafeVision

Android app (Kotlin, Jetpack Compose, Material 3) that detects inappropriate images and video on-device and blocks them inside the app: Home, Safe Browser, Protection Settings.

## Build
Push to GitHub. The "Build APK" workflow converts the models, downloads the blocklist, runs unit tests and builds the APK. Download `SafeVision-debug-apk` from the Actions run (zip, APK inside).

Local build: `pip install ... ; python tools/export_models.py ; cp assets_out/* app/src/main/assets/` then build in Android Studio (also needs `app/src/main/assets/blocklist.txt`, optional).

See MODELS.md for models, licenses and limitations.

## Screen Guard (all apps, optional)
Needs Android 11+ (minSdk 30). Turn on "Screen Guard (all apps)" in Protection Settings, then enable "SafeVision Screen Guard" in Android Accessibility settings. On Android 13+ a sideloaded APK may need App info > menu > "Allow restricted settings" first.
It screenshots the screen about once a second, classifies it on-device, and covers the screen when the result is INAPPROPRIATE. Limits: a brief delay (unsafe content can show for about a second), extra battery use, and protected screens (banking apps, DRM video) cannot be captured. Google Play restricts accessibility apps; this build is for personal sideloading.
