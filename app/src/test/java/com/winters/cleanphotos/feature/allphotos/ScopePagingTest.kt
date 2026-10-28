package com.winters.cleanphotos.feature.allphotos

import com.winters.cleanphotos.core.design.ScopeDialMath
import org.junit.Assert.assertEquals
import org.junit.Test

class ScopePagingTest {

    @Test
    fun jump_is_adjacent_far_or_snap() {
        assertEquals(ScopeJump.Stay, scopeJump(1, 1, animationsEnabled = true))
        assertEquals(ScopeJump.Stay, scopeJump(2, 2, animationsEnabled = false))
        assertEquals(ScopeJump.Adjacent, scopeJump(0, 1, animationsEnabled = true))
        assertEquals(ScopeJump.Adjacent, scopeJump(3, 2, animationsEnabled = true))
        assertEquals(ScopeJump.Far, scopeJump(0, 2, animationsEnabled = true))
        assertEquals(ScopeJump.Far, scopeJump(4, 1, animationsEnabled = true))
        assertEquals(ScopeJump.Snap, scopeJump(0, 1, animationsEnabled = false))
        assertEquals(ScopeJump.Snap, scopeJump(0, 4, animationsEnabled = false))
    }

    @Test
    fun shade_follows_offset_and_enter() {
        assertEquals(0f, pageShadeAlpha(0f, 1f), 0.0001f)
        assertEquals(0.15f, pageShadeAlpha(0.5f, 1f), 0.0001f)
        assertEquals(0.15f, pageShadeAlpha(-0.5f, 1f), 0.0001f)
        assertEquals(ScopeDialMath.PAGE_DIM, pageShadeAlpha(2f, 1f), 0.0001f)
        assertEquals(ScopeDialMath.PAGE_DIM, pageShadeAlpha(0f, 0f), 0.0001f)
        assertEquals(0.15f, pageShadeAlpha(0f, 0.5f), 0.0001f)
        assertEquals(ScopeDialMath.PAGE_DIM, pageShadeAlpha(0.2f, 0f), 0.0001f)
    }

    @Test
    fun visible_scopes_keep_on_this_day_last() {
        assertEquals(
            listOf(
                LibraryScope.All,
                LibraryScope.Similar,
                LibraryScope.LargeFiles,
                LibraryScope.Screenshots,
                LibraryScope.ChatApps,
            ),
            visibleLibraryScopes(showOnThisDay = false),
        )
        assertEquals(LibraryScope.OnThisDay, visibleLibraryScopes(showOnThisDay = true).last())
    }

    @Test
    fun neighbors_are_only_the_pages_beside_the_settled_one() {
        val visible = visibleLibraryScopes(showOnThisDay = false)
        assertEquals(
            listOf(LibraryScope.All, LibraryScope.Similar),
            neighborLibraryScopes(LibraryScope.All, visible),
        )
        assertEquals(
            listOf(LibraryScope.Similar, LibraryScope.LargeFiles, LibraryScope.Screenshots),
            neighborLibraryScopes(LibraryScope.LargeFiles, visible),
        )
    }

    @Test
    fun similar_scan_starts_only_after_settle() {
        assertEquals(true, similarScanStartsOn(LibraryScope.Similar))
        LibraryScope.entries.filter { it != LibraryScope.Similar }.forEach {
            assertEquals(false, similarScanStartsOn(it))
        }
    }
}
