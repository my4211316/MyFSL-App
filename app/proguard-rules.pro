# 正式版 R8 規則。
# Room、Hilt、Compose、kotlinx.serialization 的函式庫都自帶 consumer rules，這裡只補 App 自己的部分。

# 當機紀錄要看得懂行號。
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# 備份檔與情境 JSON 用 kotlinx.serialization：保留產生的 serializer 與欄位名稱，
# 否則混淆後欄位名稱改變，舊備份會讀不回來。
-keepattributes *Annotation*, InnerClasses
-keep,includedescriptorclasses class tw.myfsl.app.**$$serializer { *; }
-keepclassmembers class tw.myfsl.app.** {
    *** Companion;
}
-keepclasseswithmembers class tw.myfsl.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep @kotlinx.serialization.Serializable class tw.myfsl.app.** { *; }

# 列舉以名稱存進資料庫（valueOf），名稱不能被混淆。
-keepclassmembers enum tw.myfsl.app.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    <fields>;
}
