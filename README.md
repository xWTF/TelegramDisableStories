## Disable Telegram Stories
Yet another simple Xposed module, which disables Stories feature in Telegram official APP.

I don't want to build Telegram myself, or use 3rd party apps, or cancel Premium subscription (I hate ADs in channels), so I made this.

Tested with `LSPosed 1.9.2`, `Telegram 13.1.1`.

Also hides Telegram's official Stories and the Story posting button, with support for obfuscated releases.

## Build

Requires JDK 17+, Android SDK 33, and Build Tools 36.0.0.

Run `./gradlew :app:assembleDebug` (`gradlew.bat` on Windows).

## How to use

1. Build (or grab the apk from [releases](https://github.com/xWTF/TelegramDisableStories/releases/)) & install the app
2. Turn on the module, select **Recommended** apps (Telegram)
3. Force stop and reopen the Telegram app
4. The Stories function is gone
