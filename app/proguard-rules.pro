# App code does not rely on reflection-heavy frameworks. Keep this file for
# deployment-specific rules if scanner SDK integrations are added later.

# Datalogic OEM SDK: binds to a device system service (partly via reflection) to
# read the hardware serial number. Keep it intact so R8 does not strip it.
-keep class com.datalogic.** { *; }
-dontwarn com.datalogic.**
