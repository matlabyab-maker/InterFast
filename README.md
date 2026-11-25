# InterFast

Lightweight Android WebView browser starter project.

## Features
- Compact two-row toolbar; low-frequency actions in Setup.
- URL/search bar, Send, File, Past, Select All, Copy, Setup, Up, Down, Back, Forward, Full Screen, Edit Page.
- Temporary page editing; Normal Page reloads the original URL without saving edits.
- Pinch zoom limited to 40%–160% of normal; Reset in Setup.
- Text Size control in Setup.
- Windows/Mobile user-agent layout choices in Setup.
- Image and JavaScript toggles.
- DownloadManager downloads with page User-Agent and available cookies.
- Persistent open-tab URL list, restored on next launch.
- Hourly toolbar light pulse while the app is running.

## Build
Open this folder in Android Studio with JDK 17 and Android SDK 35, then build `:app:assembleDebug`.
GitHub Actions workflow is configured for all branches and manual dispatch.

## Notes
Some sites restrict embedded browsers, require DRM, or use protected media streams. InterFast downloads ordinary downloadable resources and cannot bypass DRM or server-side access controls.
