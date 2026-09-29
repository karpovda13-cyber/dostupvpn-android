# gomobile вызывает Java-классы из нативного кода по именам — не обфусцировать и не вырезать.
-keep class go.** { *; }
-keep class libv2ray.** { *; }
-dontwarn go.**
-dontwarn libv2ray.**
