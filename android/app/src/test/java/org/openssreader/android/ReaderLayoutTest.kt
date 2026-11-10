package org.openssreader.android

import org.junit.Assert.*
import org.junit.Test

class ReaderLayoutTest {
    @Test fun coverIsAloneAndThenPairs() {
        assertEquals(listOf(0), ReaderSpread.indices(0, 6))
        assertEquals(listOf(1, 2), ReaderSpread.indices(1, 6))
        assertEquals(listOf(1, 2), ReaderSpread.indices(2, 6))
        assertEquals(listOf(3, 4), ReaderSpread.indices(3, 6))
        assertEquals(listOf(5), ReaderSpread.indices(5, 6))
    }
    @Test fun spreadNavigationNeverDropsLastPage() {
        assertEquals(1, ReaderSpread.next(0, 6))
        assertEquals(3, ReaderSpread.next(1, 6))
        assertEquals(5, ReaderSpread.next(3, 6))
        assertEquals(5, ReaderSpread.next(5, 6))
        assertEquals(3, ReaderSpread.previous(5, 6))
        assertEquals(1, ReaderSpread.previous(3, 6))
        assertEquals(0, ReaderSpread.previous(1, 6))
        assertEquals(0, ReaderSpread.previous(0, 6))
        assertEquals(listOf(0), ReaderSpread.indices(0, 1))
        assertEquals(listOf(1, 2), ReaderSpread.indices(2, 3))
        assertEquals(listOf(1), ReaderSpread.indices(1, 2))
    }
    @Test fun layoutsCanBeRestored() {
        assertEquals(ReaderLayout.SPREAD, ReaderLayout.parse("SPREAD"))
        assertEquals(ReaderLayout.CONTINUOUS, ReaderLayout.parse("CONTINUOUS"))
        assertEquals(ReaderLayout.SINGLE, ReaderLayout.parse("unexpected"))
    }
    @Test fun labelsAreUserFacingPages() {
        assertEquals("1 / 6", ReaderSpread.label(0, 6))
        assertEquals("2–3 / 6", ReaderSpread.label(1, 6))
        assertEquals("6 / 6", ReaderSpread.label(5, 6))
    }
    @Test fun sideTapZonesUseActualReaderWidth() {
        assertEquals(ReaderTapZone.PREVIOUS, ReaderTapNavigation.zone(0f, 1000f))
        assertEquals(ReaderTapZone.PREVIOUS, ReaderTapNavigation.zone(279f, 1000f))
        assertEquals(ReaderTapZone.CENTER, ReaderTapNavigation.zone(280f, 1000f))
        assertEquals(ReaderTapZone.CENTER, ReaderTapNavigation.zone(500f, 1000f))
        assertEquals(ReaderTapZone.NEXT, ReaderTapNavigation.zone(720f, 1000f))
        assertEquals(ReaderTapZone.NEXT, ReaderTapNavigation.zone(999f, 1000f))
        assertEquals(ReaderTapZone.CENTER, ReaderTapNavigation.zone(5f, 0f))
        assertEquals(ReaderTapZone.CENTER, ReaderTapNavigation.zone(Float.NaN, 100f))
        assertEquals(ReaderTapZone.CENTER, ReaderTapNavigation.zone(-1f, 100f))
    }
    @Test fun tapPageNavigationHandlesBoundsAndSpreads() {
        assertEquals(0, ReaderTapNavigation.destination(0, 5, false, ReaderTapZone.PREVIOUS))
        assertEquals(1, ReaderTapNavigation.destination(0, 5, false, ReaderTapZone.NEXT))
        assertEquals(4, ReaderTapNavigation.destination(4, 5, false, ReaderTapZone.NEXT))
        assertEquals(2, ReaderTapNavigation.destination(2, 5, false, ReaderTapZone.CENTER))
        assertEquals(1, ReaderTapNavigation.destination(0, 6, true, ReaderTapZone.NEXT))
        assertEquals(3, ReaderTapNavigation.destination(1, 6, true, ReaderTapZone.NEXT))
        assertEquals(1, ReaderTapNavigation.destination(3, 6, true, ReaderTapZone.PREVIOUS))
        assertEquals(0, ReaderTapNavigation.destination(0, 0, true, ReaderTapZone.NEXT))
    }

}
