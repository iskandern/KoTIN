# filament-kmp 0.8 не поставляет правил для R8, а нативный код находит классы пакета jni по имени
# (FilaCallback, Callbacks, Upload…): без них release падает на старте с ClassNotFoundException.
-keep class io.github.erkko68.filament.jni.** { *; }
