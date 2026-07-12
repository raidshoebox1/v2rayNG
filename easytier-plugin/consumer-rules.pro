# EasyTier Plugin Consumer Rules
# Keep JNI bridge classes (package is com.easytier.jni, NOT com.easytier.plugin)
-keep class com.easytier.jni.EasyTierJNI { *; }
-keep class com.easytier.jni.EasyTierDataPlaneJNI { *; }
-keep class com.easytier.jni.ConfigServerEventCallback { *; }
-keep class com.easytier.jni.DataPlaneTcpStream { *; }
-keep class com.easytier.jni.DataPlaneTcpListener { *; }
-keep class com.easytier.jni.DataPlaneUdpSocket { *; }
-keep class com.easytier.jni.DataPlaneSocketAddress { *; }
-keep class com.easytier.jni.DataPlaneTcpConnectResult { *; }
-keep class com.easytier.jni.DataPlaneTcpBindResult { *; }
-keep class com.easytier.jni.DataPlaneTcpAcceptResult { *; }
-keep class com.easytier.jni.DataPlaneTcpReadResult { *; }
-keep class com.easytier.jni.DataPlaneUdpBindResult { *; }
-keep class com.easytier.jni.DataPlaneUdpRecvResult { *; }

# Keep plugin classes
-keep class com.easytier.plugin.EasyTierPlugin { *; }
-keep class com.easytier.plugin.EasyTierPlugin$* { *; }
-keep class com.easytier.plugin.EasyTierConfig { *; }
-keep class com.easytier.plugin.EasyTierConfig$* { *; }
-keep class com.easytier.plugin.EasyTierSettingsManager { *; }

# Keep Kotlin metadata
-keepattributes *Annotation*
