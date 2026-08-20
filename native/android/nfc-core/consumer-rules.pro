# JMRTD (BAC / PACE / Secure Messaging / LDS)
-keep class org.jmrtd.** { *; }

# Scuba Smartcard (IsoDepCardService được load bằng reflection)
-keep class net.sf.scuba.** { *; }

# BouncyCastle Cryptography
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
