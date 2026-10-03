package com.example.smoothplayer.engine.impl.threading

/** Marks code that must run on the single GL thread, where its EGL context is current. */
@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.CONSTRUCTOR)
internal annotation class GlThread
