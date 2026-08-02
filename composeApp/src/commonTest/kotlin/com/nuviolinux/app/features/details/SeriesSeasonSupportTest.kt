package com.nuviolinux.app.features.details

import kotlin.test.Test
import kotlin.test.assertEquals

class SeriesSeasonSupportTest {

    @Test
    fun `normalize season number maps zero and null to specials`() {
        assertEquals(SPECIALS_SEASON_NUMBER, normalizeSeasonNumber(0))
        assertEquals(SPECIALS_SEASON_NUMBER, normalizeSeasonNumber(null))
        assertEquals(2, normalizeSeasonNumber(2))
    }

    @Test
    fun `sorted playable episodes place specials after numbered seasons`() {
        val meta = MetaDetails(
            id = "show",
            type = "series",
            name = "Show",
            videos = listOf(
                MetaVideo(id = "special-1", title = "Special 1", season = 0, episode = 1),
                MetaVideo(id = "season-2", title = "Episode 1", season = 2, episode = 1),
                MetaVideo(id = "season-1", title = "Episode 1", season = 1, episode = 1),
                MetaVideo(id = "special-2", title = "Special 2", season = 0, episode = 2),
            ),
        )

        assertEquals(
            listOf("season-1", "season-2", "special-1", "special-2"),
            meta.sortedPlayableEpisodes().map(MetaVideo::id),
        )
    }
}
