package com.starchive.player.aa

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Tab
import androidx.car.app.model.TabContents
import androidx.car.app.model.TabTemplate
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.starchive.player.R
import com.starchive.player.data.Repository

/**
 * Root screen of the Android Auto experience.
 *
 * Renders the three top-level destinations (Artists, Live, Favorites) as a [TabTemplate].
 * The tab set is built once and reused on every refresh so the host sees a consistent tab
 * structure. Each tab's content is provided by a dedicated tab-content object that returns a
 * bare template with no header/header-action, because headers inside [TabContents] are ignored
 * by the host.
 */
class HomeScreen(
    carContext: CarContext,
    repository: Repository,
    imageCache: AaImageCache,
    playbackClient: AaPlaybackClient,
) : Screen(carContext) {

    private val artistsTab = ArtistsTabContent(
        carContext,
        repository,
        imageCache,
        playbackClient,
        push = { screenManager.push(it) },
        onInvalidate = { invalidate() },
    )
    private val liveTab = LiveTabContent(
        carContext,
        repository,
        imageCache,
        playbackClient,
        push = { screenManager.push(it) },
        onInvalidate = { invalidate() },
    )
    private val favoritesTab = FavoritesTabContent(
        carContext,
        repository,
        imageCache,
        playbackClient,
        push = { screenManager.push(it) },
    )

    private var activeTabId = TAB_ARTISTS

    private val tabs = listOf(
        Tab.Builder()
            .setContentId(TAB_ARTISTS)
            .setTitle(carContext.getString(R.string.aa_tab_artists))
            .setIcon(createIcon(R.drawable.aa_tab_artists))
            .build(),
        Tab.Builder()
            .setContentId(TAB_LIVE)
            .setTitle(carContext.getString(R.string.aa_tab_live))
            .setIcon(createIcon(R.drawable.aa_tab_live))
            .build(),
        Tab.Builder()
            .setContentId(TAB_FAVORITES)
            .setTitle(carContext.getString(R.string.aa_tab_favorites))
            .setIcon(createIcon(R.drawable.aa_tab_favorites))
            .build(),
    )

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                artistsTab.onDestroy()
                liveTab.onDestroy()
            }
        })
    }

    override fun onGetTemplate(): Template {
        val contentTemplate = when (activeTabId) {
            TAB_ARTISTS -> artistsTab.template()
            TAB_LIVE -> liveTab.template()
            else -> favoritesTab.template()
        }

        val builder = TabTemplate.Builder(tabCallback)
            .setHeaderAction(Action.APP_ICON)
            .setActiveTabContentId(activeTabId)
            .setTabContents(TabContents.Builder(contentTemplate).build())

        tabs.forEach { builder.addTab(it) }

        return builder.build()
    }

    private val tabCallback = object : TabTemplate.TabCallback {
        override fun onTabSelected(tabContentId: String) {
            if (tabContentId == activeTabId) return
            activeTabId = tabContentId
            invalidate()
        }
    }

    private fun createIcon(resId: Int): CarIcon {
        return CarIcon.Builder(IconCompat.createWithResource(carContext, resId)).build()
    }

    companion object {
        const val TAB_ARTISTS = "artists"
        const val TAB_LIVE = "live"
        const val TAB_FAVORITES = "favorites"
    }
}
