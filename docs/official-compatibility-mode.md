# Official compatibility mode

The module APK remains independent from the official Ninebot APK. This mode is enabled by default.

When enabled, `MirrorModule` returns from `onPackageLoaded` and `onPackageReady` before creating any Ninebot hook manager or installing any hook. It does not hook `Application`, `ClassLoader`, `LayoutInflater`, `View.draw`, network code, login code, or vehicle code.

## Device procedure

1. Install `NinebotEnhance-官方认证兼容模式-debug.apk`.
2. Open the module settings page and leave `官方认证兼容模式` enabled.
3. Force-stop Ninebot, then open the official Ninebot app and test login and vehicle connection.
4. If the official app works, the runtime module was affecting the connection. Keep compatibility mode enabled when using the official app normally.
5. To use enhancement features later, disable the switch, force-stop Ninebot again, and reopen it. This re-enables the existing hooks and may reproduce the server rejection.

The switch is stored in the module's private `SharedPreferences` and is exposed to the injected Ninebot process through the read-only provider `dev.ichinomiya.ninebotenhance.compatibility`. The provider only accepts calls from the module itself or `cn.ninebot.ninebot`.

The default is enabled. If the preference or provider cannot be read, the module fails closed and treats compatibility mode as enabled.
