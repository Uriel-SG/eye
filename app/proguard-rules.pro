# Regole R8 di Eye

# ML Kit OCR bundled: la libreria nativa legge classi/campi Java via JNI per nome (altrimenti NPE con R8).
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_text_common.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_text_bundled_common.** { *; }

# ONNX Runtime: il codice nativo usa classi/campi Java per nome via JNI.
-keep class ai.onnxruntime.** { *; }
