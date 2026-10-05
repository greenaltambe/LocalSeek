package com.augt.localseek.ui.onboarding

enum class OnboardingPage { WELCOME, SEARCHABLE, PERMISSIONS, IMAGE_PACK }

/** Optional permissions, asked one at a time in this order. */
enum class PermissionStep { CONTACTS, FILES, PHOTOS }

/**
 * Pure navigation state of the first-run tour. No Android types, so it is unit-testable.
 *
 * The PERMISSIONS page holds one sub-step per optional permission; [next] walks through them before
 * moving to the following page, so "Not now" and "Continue" are the same transition. [finished] becomes
 * true when the last page is passed or the tour is skipped.
 */
data class OnboardingState(
    val pages: List<OnboardingPage>,
    val permissionSteps: List<PermissionStep>,
    val pageIndex: Int = 0,
    val permissionIndex: Int = 0,
    val finished: Boolean = false
) {
    val page: OnboardingPage get() = pages[pageIndex]

    /** The permission being asked about, or null when the current page is not the permissions page. */
    val currentPermission: PermissionStep?
        get() = if (page == OnboardingPage.PERMISSIONS) permissionSteps.getOrNull(permissionIndex) else null

    val isLastStep: Boolean
        get() = pageIndex == pages.lastIndex &&
            (page != OnboardingPage.PERMISSIONS || permissionIndex >= permissionSteps.lastIndex)

    val canGoBack: Boolean get() = pageIndex > 0 || permissionIndex > 0

    /** 0..1 over all pages, for the progress indicator. */
    val progress: Float get() = (pageIndex + 1).toFloat() / pages.size

    fun next(): OnboardingState = when {
        finished -> this
        page == OnboardingPage.PERMISSIONS && permissionIndex < permissionSteps.lastIndex ->
            copy(permissionIndex = permissionIndex + 1)
        pageIndex < pages.lastIndex -> copy(pageIndex = pageIndex + 1, permissionIndex = 0)
        else -> copy(finished = true)
    }

    fun back(): OnboardingState = when {
        finished -> this
        page == OnboardingPage.PERMISSIONS && permissionIndex > 0 -> copy(permissionIndex = permissionIndex - 1)
        pageIndex > 0 -> {
            val target = pageIndex - 1
            // Going back onto the permissions page lands on its last question, not the first.
            val lastPerm = if (pages[target] == OnboardingPage.PERMISSIONS) permissionSteps.lastIndex.coerceAtLeast(0) else 0
            copy(pageIndex = target, permissionIndex = lastPerm)
        }
        else -> this
    }

    fun skipAll(): OnboardingState = copy(finished = true)

    companion object {
        /**
         * @param includePhotos whether image search is compiled in (photos permission and the optional pack are only
         * relevant then).
         */
        fun create(includePhotos: Boolean): OnboardingState {
            val steps = buildList {
                add(PermissionStep.CONTACTS)
                add(PermissionStep.FILES)
                if (includePhotos) add(PermissionStep.PHOTOS)
            }
            val pages = buildList {
                add(OnboardingPage.WELCOME)
                add(OnboardingPage.SEARCHABLE)
                add(OnboardingPage.PERMISSIONS)
                if (includePhotos) add(OnboardingPage.IMAGE_PACK)
            }
            return OnboardingState(pages = pages, permissionSteps = steps)
        }
    }
}
