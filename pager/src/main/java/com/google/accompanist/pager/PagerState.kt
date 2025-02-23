/*
 * Copyright 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:Suppress("MemberVisibilityCanBePrivate")

package com.google.accompanist.pager

import android.util.Log
import androidx.annotation.FloatRange
import androidx.annotation.IntRange
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.calculateTargetValue
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.map
import kotlin.math.absoluteValue
import kotlin.math.floor
import kotlin.math.roundToInt

private const val LogTag = "PagerState"

/**
 * Creates a [PagerState] that is remembered across compositions.
 *
 * Changes to the provided values for [initialPage] and [initialPageOffset] will **not** result
 * in the state being recreated or changed in any way if it has already been created.
 * Changes to [pageCount] will result in the [PagerState] being updated.
 *
 * @param pageCount the value for [PagerState.pageCount]
 * @param initialPage the initial value for [PagerState.currentPage]
 * @param initialPageOffset the initial value for [PagerState.currentPageOffset]
 */
@ExperimentalPagerApi
@Composable
fun rememberPagerState(
    @IntRange(from = 0) pageCount: Int,
    @IntRange(from = 0) initialPage: Int = 0,
    @FloatRange(from = 0.0, to = 1.0) initialPageOffset: Float = 0f,
): PagerState = rememberSaveable(saver = PagerState.Saver) {
    PagerState(
        pageCount = pageCount,
        currentPage = initialPage,
        currentPageOffset = initialPageOffset,
    )
}.apply {
    this.pageCount = pageCount
}

/**
 * A state object that can be hoisted to control and observe scrolling for [HorizontalPager].
 *
 * In most cases, this will be created via [rememberPagerState].
 *
 * @param pageCount the initial value for [PagerState.pageCount]
 * @param currentPage the initial value for [PagerState.currentPage]
 * @param currentPageOffset the initial value for [PagerState.currentPageOffset]
 */
@ExperimentalPagerApi
@Stable
class PagerState(
    @IntRange(from = 0) pageCount: Int,
    @IntRange(from = 0) currentPage: Int = 0,
    @FloatRange(from = 0.0, to = 1.0) currentPageOffset: Float = 0f,
) : ScrollableState {
    private var _pageCount by mutableStateOf(pageCount)
    private var _currentPage by mutableStateOf(currentPage)
    private val _currentPageOffset = mutableStateOf(currentPageOffset)
    internal var pageSize by mutableStateOf(0)

    /**
     * The current scroll position, as a float value between `0 until pageSize`
     */
    private val absolutePosition: Float
        get() = currentPage + currentPageOffset

    /**
     * The ScrollableController instance. We keep it as we need to call stopAnimation on it once
     * we reached the end of the list.
     */
    private val scrollableState = ScrollableState { deltaPixels ->
        // scrollByOffset expects values in an opposite sign to what we're passed, so we need
        // to negate the value passed in, and the value returned.
        val size = pageSize.coerceAtLeast(1)
        -scrollByOffset(-deltaPixels / size) * size
    }

    init {
        require(pageCount >= 0) { "pageCount must be >= 0" }
        requireCurrentPage(currentPage, "currentPage")
        requireCurrentPageOffset(currentPageOffset, "currentPageOffset")
    }

    /**
     * The number of pages to display.
     */
    @get:IntRange(from = 0)
    var pageCount: Int
        get() = _pageCount
        set(@IntRange(from = 0) value) {
            require(value >= 0) { "pageCount must be >= 0" }
            _pageCount = value
            currentPage = currentPage.coerceIn(0, lastPageIndex)
        }

    internal val lastPageIndex: Int
        get() = (pageCount - 1).coerceAtLeast(0)

    /**
     * The index of the currently selected page.
     *
     * To update the scroll position, use [scrollToPage] or [animateScrollToPage].
     */
    @get:IntRange(from = 0)
    var currentPage: Int
        get() = _currentPage
        private set(value) {
            _currentPage = value.coerceIn(0, lastPageIndex)
        }

    /**
     * The current offset from the start of [currentPage], as a fraction of the page width.
     *
     * To update the scroll position, use [scrollToPage] or [animateScrollToPage].
     */
    @get:FloatRange(from = 0.0, to = 1.0)
    var currentPageOffset: Float
        get() = _currentPageOffset.value
        private set(value) {
            _currentPageOffset.value = value.coerceIn(
                minimumValue = 0f,
                maximumValue = if (currentPage == lastPageIndex) 0f else 1f,
            )
        }

    /**
     * Animate (smooth scroll) to the given page.
     *
     * Cancels the currently running scroll, if any, and suspends until the cancellation is
     * complete.
     *
     * @param page the page to snap to. Must be between 0 and [pageCount] (inclusive).
     * @param pageOffset the percentage of the page width to offset, from the start of [page]
     * @param initialVelocity Initial velocity in pixels per second, or `0f` to not use a start velocity.
     * Must be in the range 0f..1f.
     */
    suspend fun animateScrollToPage(
        @IntRange(from = 0) page: Int,
        @FloatRange(from = 0.0, to = 1.0) pageOffset: Float = 0f,
        initialVelocity: Float = 0f,
    ) {
        requireCurrentPage(page, "page")
        requireCurrentPageOffset(pageOffset, "pageOffset")

        if (page == currentPage) return

        // We don't specifically use the ScrollScope's scrollBy, but
        // we do want to use it's mutex
        scroll {
            animateToPage(
                page = page.coerceIn(0, lastPageIndex),
                pageOffset = pageOffset.coerceIn(0f, 1f),
                initialVelocity = initialVelocity,
            )
        }
    }

    /**
     * Instantly brings the item at [page] to the middle of the viewport, offset by [pageOffset]
     * percentage of page width.
     *
     * Cancels the currently running scroll, if any, and suspends until the cancellation is
     * complete.
     *
     * @param page the page to snap to. Must be between 0 and [pageCount] (inclusive).
     * @param pageOffset the percentage of the page width to offset, from the start of [page].
     * Must be in the range 0f..1f.
     */
    suspend fun scrollToPage(
        @IntRange(from = 0) page: Int,
        @FloatRange(from = 0.0, to = 1.0) pageOffset: Float = 0f,
    ) {
        requireCurrentPage(page, "page")
        requireCurrentPageOffset(pageOffset, "pageOffset")

        // We don't specifically use the ScrollScope's scrollBy(), but
        // we do want to use it's mutex
        scroll {
            currentPage = page
            currentPageOffset = pageOffset
        }
    }

    private fun snapToNearestPage() {
        if (DebugLog) {
            Log.d(LogTag, "snapToNearestPage. currentPage:$currentPage, offset:$currentPageOffset")
        }
        currentPage += currentPageOffset.roundToInt()
        currentPageOffset = 0f
    }

    private suspend fun animateToPage(
        page: Int,
        pageOffset: Float = 0f,
        animationSpec: AnimationSpec<Float> = spring(),
        initialVelocity: Float = 0f,
    ) {
        animate(
            initialValue = absolutePosition,
            targetValue = page + pageOffset,
            initialVelocity = initialVelocity,
            animationSpec = animationSpec
        ) { value, _ ->
            updateFromScrollPosition(value)
        }
        snapToNearestPage()
    }

    private fun determineSpringBackOffset(
        velocity: Float,
        offset: Float = currentPageOffset,
    ): Float = when {
        // If the velocity is greater than 1 page per second (velocity is px/s), spring
        // in the relevant direction
        velocity >= pageSize -> 1f
        velocity <= -pageSize -> 0f
        // If the offset exceeds the scroll threshold (in either direction), we want to
        // move to the next/previous item
        offset < 0.5f -> 0f
        else -> 1f
    }

    private fun updateFromScrollPosition(position: Float) {
        currentPage = floor(position).toInt()
        currentPageOffset = position - currentPage
    }

    /**
     * Scroll by the pager with the given [deltaOffset].
     *
     * @param deltaOffset delta in offset values (0f..1f). Values > 0 signify scrolls
     * towards the end of the pager, and values < 0 towards the start.
     * @return any unconsumed [deltaOffset]
     */
    private fun scrollByOffset(deltaOffset: Float): Float {
        val current = absolutePosition
        val target = (current + deltaOffset).coerceIn(0f, lastPageIndex.toFloat())
        updateFromScrollPosition(target)

        if (DebugLog) {
            Log.d(
                LogTag,
                "scrollByOffset. delta:%.4f, new-page:%d, new-offset:%.4f"
                    .format(deltaOffset, currentPage, currentPageOffset),
            )
        }

        return deltaOffset - (target - current)
    }

    /**
     * Fling the pager with the given [initialVelocity]. [scrollBy] will called whenever a
     * scroll change is required by the fling.
     *
     * @param initialVelocity velocity in pixels per second. Values > 0 signify flings
     * towards the end of the pager, and values < 0 sign flings towards the start.
     * @param decayAnimationSpec The decay animation spec to use for decayed flings.
     * @param snapAnimationSpec The animation spec to use when snapping.
     * @param scrollBy block which is called when a scroll is required. Positive values passed in
     * signify scrolls towards the end of the pager, and values < 0 towards the start.
     * @return any remaining velocity after the scroll has finished.
     */
    internal suspend fun fling(
        initialVelocity: Float,
        decayAnimationSpec: DecayAnimationSpec<Float> = exponentialDecay(),
        snapAnimationSpec: AnimationSpec<Float> = spring(),
        scrollBy: (Float) -> Float,
    ): Float {
        // We calculate the target offset using pixels, rather than using the offset
        val targetOffset = decayAnimationSpec.calculateTargetValue(
            initialValue = currentPageOffset * pageSize,
            initialVelocity = initialVelocity
        ) / pageSize

        if (DebugLog) {
            Log.d(
                LogTag,
                "fling. velocity:%.4f, page: %d, offset:%.4f, targetOffset:%.4f"
                    .format(initialVelocity, currentPage, currentPageOffset, targetOffset)
            )
        }

        var lastVelocity: Float = initialVelocity

        // If the animation can naturally end outside of current page bounds, we will
        // animate with decay.
        if (targetOffset.absoluteValue >= 1) {
            // Animate with the decay animation spec using the fling velocity

            val targetPage = when {
                targetOffset > 0 -> (currentPage + 1).coerceAtMost(lastPageIndex)
                else -> currentPage
            }

            AnimationState(
                initialValue = currentPageOffset * pageSize,
                initialVelocity = initialVelocity
            ).animateDecay(decayAnimationSpec) {
                if (DebugLog) {
                    Log.d(
                        LogTag,
                        "fling. decay. value:%.4f, page: %d, offset:%.4f"
                            .format(value, currentPage, currentPageOffset)
                    )
                }

                // Keep track of velocity
                lastVelocity = velocity

                // Now scroll..
                val coerced = value.coerceIn(0f, pageSize.toFloat())
                scrollBy(coerced - (currentPageOffset * pageSize))

                // If we've scroll our target page (or beyond it), cancel the animation
                val pastStartBound = initialVelocity < 0 &&
                    (currentPage < targetPage || (currentPage == targetPage && currentPageOffset == 0f))
                val pastEndBound = initialVelocity > 0 &&
                    (currentPage > targetPage || (currentPage == targetPage && currentPageOffset > 0f))

                if (pastStartBound || pastEndBound) {
                    // If we reach the bounds of the allowed offset, cancel the animation
                    cancelAnimation()
                    currentPage = targetPage
                    currentPageOffset = 0f
                }
            }
        } else {
            // Otherwise we animate to the next item, or spring-back depending on the offset
            val targetPosition = currentPage + determineSpringBackOffset(
                velocity = initialVelocity,
                offset = targetOffset
            )
            animate(
                initialValue = absolutePosition * pageSize,
                targetValue = targetPosition * pageSize,
                initialVelocity = initialVelocity,
                animationSpec = snapAnimationSpec,
            ) { value, velocity ->
                scrollBy(value - (absolutePosition * pageSize))
                // Keep track of velocity
                lastVelocity = velocity
            }
        }

        snapToNearestPage()
        return lastVelocity
    }

    override val isScrollInProgress: Boolean
        get() = scrollableState.isScrollInProgress

    override fun dispatchRawDelta(delta: Float): Float {
        return scrollableState.dispatchRawDelta(delta)
    }

    override suspend fun scroll(
        scrollPriority: MutatePriority,
        block: suspend ScrollScope.() -> Unit
    ) {
        scrollableState.scroll(scrollPriority, block)
    }

    override fun toString(): String = "PagerState(" +
        "pageCount=$pageCount, " +
        "currentPage=$currentPage, " +
        "currentPageOffset=$currentPageOffset" +
        ")"

    private fun requireCurrentPage(value: Int, name: String) {
        if (pageCount == 0) {
            require(value == 0) { "$name must be 0 when pageCount is 0" }
        } else {
            require(value in 0 until pageCount) {
                "$name must be >= 0 and < pageCount"
            }
        }
    }

    private fun requireCurrentPageOffset(value: Float, name: String) {
        if (pageCount == 0) {
            require(value == 0f) { "$name must be 0f when pageCount is 0" }
        } else {
            require(value in 0f..1f) { "$name must be >= 0 and <= 1" }
        }
    }

    companion object {
        /**
         * The default [Saver] implementation for [PagerState].
         */
        val Saver: Saver<PagerState, *> = listSaver(
            save = { listOf<Any>(it.pageCount, it.currentPage, it.currentPageOffset) },
            restore = {
                PagerState(
                    pageCount = it[0] as Int,
                    currentPage = it[1] as Int,
                    currentPageOffset = it[2] as Float
                )
            }
        )
    }
}

/**
 * A flow which emits the [PagerState.currentPage] as it changes due to a scroll completing.
 *
 * This flow is not meant to be used for updating any UI within the attached [HorizontalPager]
 * or [VerticalPager]. For that use-case, you should read
 * [PagerScope.currentPage] and [PagerScope.currentPageOffset] from the content scope.
 *
 * @sample com.google.accompanist.sample.pager.PageChangesSample
 */
@ExperimentalPagerApi
inline val PagerState.pageChanges: Flow<Int>
    get() = snapshotFlow { isScrollInProgress }
        // Only emit when the scroll has finished
        .filterNot { it }
        .map { currentPage }
        .distinctUntilChanged()
