# JNI looks up the progress callback by this method name.
-keep interface app.photosphere.StitchProgress { *; }
-keepclassmembers class * implements app.photosphere.StitchProgress {
    public boolean onProgress(int, java.lang.String);
}

