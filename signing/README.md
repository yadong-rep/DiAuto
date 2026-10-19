# Development signing

`hongqi-debug.keystore` is a public Android development key (standard `android`
password and `androiddebugkey` alias). It is used only for debug builds so repeated
builds of this source can update the installed test app. Never use it for release
distribution. Release builds continue to require the owner's signing configuration.

The 0.3.11-hongqi.3 debug certificate differs from earlier test packages. Android
may require uninstalling an earlier debug package once, which clears that app's
settings. Subsequent builds using this development key can be installed as updates.
