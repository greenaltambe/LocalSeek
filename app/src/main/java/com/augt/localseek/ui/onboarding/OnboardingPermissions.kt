package com.augt.localseek.ui.onboarding

/**
 * Bridge to the activity's existing permission-request code paths. The activity owns the result launchers,
 * so onboarding only asks it to start a request and re-reads [isGranted] when the screen resumes.
 */
interface OnboardingPermissions {
    fun isGranted(step: PermissionStep): Boolean

    /** Starts the same system request the app has always used for this permission. */
    fun request(step: PermissionStep)

    /** Called once when the tour ends (finished or skipped); kicks off indexing with whatever was granted. */
    fun onOnboardingFinished()

    companion object {
        /** For previews and tests: nothing is granted and requests do nothing. */
        val None = object : OnboardingPermissions {
            override fun isGranted(step: PermissionStep) = false
            override fun request(step: PermissionStep) {}
            override fun onOnboardingFinished() {}
        }
    }
}
