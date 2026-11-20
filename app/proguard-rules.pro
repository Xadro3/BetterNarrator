# sherpa-onnx resolves this typed callback through JNI rather than Java dispatch.
-keep class com.betternarrator.OfflineSpeechEngine$GenerationCallback { *; }
