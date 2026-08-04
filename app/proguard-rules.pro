# SPDX-License-Identifier: GPL-3.0-or-later
#
# R8 rules for the release build.
#
# The guiding principle: keep only what is genuinely reached by reflection, and let R8 remove
# everything else. Every rule below names *why* it exists, because an unexplained keep rule is
# indistinguishable from one that has outlived its cause and is now just making the APK bigger.

# --- kotlinx.serialization -----------------------------------------------------------------------
# The generated serializers are found reflectively via the `Companion.serializer()` convention, so
# R8 cannot see the link from the data class to its serializer. Without this the GPodder DTOs
# (LoginPollDto, EpisodeActionDto, …) fail at runtime with SerializationException rather than at
# build time — the worst kind of minification bug, because the JVM tests never exercise R8.
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# --- jaudiotagger --------------------------------------------------------------------------------
# Tag readers/writers are selected by name from a registry and instantiated reflectively, so the
# call graph does not reach them. It also ships resource bundles that resource shrinking would
# otherwise consider unused. A stripped tagger fails only when a download completes, which is late.
-keep class org.jaudiotagger.** { *; }
-dontwarn org.jaudiotagger.**

# --- OkHttp / Okio -------------------------------------------------------------------------------
# Both reference optional platform classes (Conscrypt, BouncyCastle, Android's own) that are absent
# here by design. These are warnings about code that is correctly never called.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# --- Room ----------------------------------------------------------------------------------------
# The generated implementations are instantiated by name (`AppDatabase_Impl`). Room's own consumer
# rules cover most of this; this keeps the entities' no-arg constructors, which the generated code
# calls reflectively when building a cursor row.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keepclassmembers class * {
    @androidx.room.* <methods>;
}

# --- Kotlin metadata -----------------------------------------------------------------------------
# Coroutines' debug agent and Compose's own tooling both read this. Cheap to keep, and its absence
# produces stack traces that cannot be read.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault,InnerClasses,Signature,SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
