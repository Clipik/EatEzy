-keepattributes *Annotation*, InnerClasses, Signature
-keep,includedescriptorclasses class app.eatezy.**$$serializer { *; }
-keepclassmembers class app.eatezy.** { *** Companion; }
-dontwarn org.slf4j.**

# ML Kit creates its components by class name from the manifest; R8 full mode dropped their
# no-arg constructors and the barcode scanner crashed on start.
-keep class * implements com.google.firebase.components.ComponentRegistrar { <init>(); *; }
-keep interface com.google.firebase.components.ComponentRegistrar { *; }
-keep class com.google.mlkit.** { <init>(...); }
