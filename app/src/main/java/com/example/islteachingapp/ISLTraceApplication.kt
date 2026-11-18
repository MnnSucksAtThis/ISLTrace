package com.example.islteachingapp

import android.app.Activity
import android.app.Application
import android.os.Bundle

/** Counts only time while at least one ISL Trace activity is visible in the foreground. */
class ISLTraceApplication : Application(), Application.ActivityLifecycleCallbacks {
    private var startedActivities = 0

    override fun onCreate() {
        super.onCreate()
        // A new Application instance means the previous process ended. Close its session
        // without counting time spent while that process was not running.
        StudyLogger.recoverInterruptedSession(this)
        registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityStarted(activity: Activity) {
        startedActivities++
        if (startedActivities == 1) StudyLogger.appForegroundChanged(this, true)
    }

    override fun onActivityStopped(activity: Activity) {
        startedActivities = (startedActivities - 1).coerceAtLeast(0)
        if (startedActivities == 0) StudyLogger.appForegroundChanged(this, false)
    }

    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
