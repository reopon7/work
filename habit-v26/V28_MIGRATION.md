# V28 Java migration checkpoint

Migrated implementations compiled from Java:

- Browser/Model/ConfigrationStatus
- Browser/Model/WebViewData and WebViewData$1
- Browser/Util/CookieUtil
- Browser/Util/WebViewManager
- Browser/Util/WebViewTimerManager
- SettingPattern/Util/SettingPatternManager
- UserAgent/Util/UserAgentManager
- UserAgentPattern/Util/UserAgentPatternManager
- Component/Application/Mod compatibility layer

The legacy primary DEX is used only as the source for classes not migrated yet.
APK assembly removes class_def entries for Java-migrated classes so there are no
duplicate definitions across classes.dex and classes2.dex. Executable method
instructions of the remaining legacy classes are not patched.
