package org.openssreader.android.core

import org.junit.Assert.*
import org.junit.Test

/** Structural page-order regression. Synthetic names are deliberately scrambled. */
class PdzMixedPagePlanTest {
    private fun catalog(vararg names: String): PdzCatalog = PdzCatalog(
        names.mapIndexed { i, name -> PdzEntry(name, 96L + i * 1024, 1024) },
        96L + names.size * 1024
    )

    @Test fun frontTocAndInterleavedBodyAreOrderedByPhysicalPage() {
        val plan = PdzMixedPagePlan.plan(catalog(
            "000006.pdg", "4_2.pdg", "!00002.pdg", "fow001.pdg",
            "1_2.pdg", "000003.pdg", "!00001.pdg"
        ))!!
        assertEquals(1, plan.frontPages)
        assertEquals(2, plan.tocPages)
        assertEquals(6, plan.bodyPages)
        assertEquals(9, plan.totalPages)
        assertEquals(listOf("fow001.pdg", "!00001.pdg", "!00002.pdg",
            "1_2.pdg", "000003.pdg", "4_2.pdg", "000006.pdg"),
            plan.parts.map { when (it) {
                is PdzMixedPagePlan.Part.Single -> it.entry.name
                is PdzMixedPagePlan.Part.PdfBundle -> it.entry.name
            } })
        assertEquals(listOf(0,1,2,3,5,6,8), plan.parts.map { it.firstPage })
        assertEquals(listOf(1,1,1,2,1,2,1), plan.parts.map { it.pageCount })
    }

    @Test fun firstAndLastPagesCanBeIndependent() {
        val plan = PdzMixedPagePlan.plan(catalog("000004.pdg", "2_2.pdg", "000001.pdg"))!!
        assertEquals(4, plan.totalPages)
        assertEquals(listOf(0,1,3), plan.parts.map { it.firstPage })
    }
    @Test fun prefaceAndBundleOnlyAreSupported() {
        val plan = PdzMixedPagePlan.plan(catalog("1_3.pdg", "!00001.pdg", "fow001.pdg"))!!
        assertEquals(5, plan.totalPages)
        assertEquals(listOf(0,1,2), plan.parts.map { it.firstPage })
    }
    @Test fun pureNonHybridLayoutsAreNotIntercepted() {
        assertNull(PdzMixedPagePlan.plan(catalog("000001.pdg", "000002.pdg")))
        assertNull(PdzMixedPagePlan.plan(catalog("1_2.pdg", "3_2.pdg")))
    }
    @Test fun missingBodyPageRejected() {
        assertThrows(PdzFormatException::class.java) {
            PdzMixedPagePlan.plan(catalog("000001.pdg", "3_2.pdg"))
        }
    }
    @Test fun overlappingBundleRejected() {
        assertThrows(PdzFormatException::class.java) {
            PdzMixedPagePlan.plan(catalog("1_3.pdg", "000003.pdg"))
        }
    }
    @Test fun appendixAfterBodyIsPreserved() {
        // Body: 000001 (1 page) + 2_2 (2 pages) + 000004 (1 page).
        // Appendix att018 follows those four body pages; it is NOT body page 18.
        val plan = PdzMixedPagePlan.plan(catalog(
            "att018.pdg", "000004.pdg", "2_2.pdg", "000001.pdg"
        ))!!
        assertEquals(4, plan.bodyPages)
        assertEquals(1, plan.appendixPages)
        assertEquals(5, plan.totalPages)
        assertEquals(listOf(0, 1, 3, 4), plan.parts.map { it.firstPage })
        assertEquals(listOf(1, 2, 1, 1), plan.parts.map { it.pageCount })
        assertEquals(listOf("000001.pdg", "2_2.pdg", "000004.pdg", "att018.pdg"),
            plan.parts.map { when (it) {
                is PdzMixedPagePlan.Part.Single -> it.entry.name
                is PdzMixedPagePlan.Part.PdfBundle -> it.entry.name
            } })
    }
    @Test fun appendixAfterLastBundleIsPreserved() {
        // 1 single page + 2 pages in a bundle + 1 appendix = 4 physical pages.
        val plan = PdzMixedPagePlan.plan(catalog("000001.pdg", "2_2.pdg", "att018.pdg"))!!
        assertEquals(3, plan.bodyPages)
        assertEquals(1, plan.appendixPages)
        assertEquals(4, plan.totalPages)
        assertEquals(listOf(0, 1, 3), plan.parts.map { it.firstPage })
        assertEquals(listOf(1, 2, 1), plan.parts.map { it.pageCount })
    }
    @Test fun appendixGapStillRejected() {
        assertThrows(PdzFormatException::class.java) {
            PdzMixedPagePlan.plan(catalog("1_2.pdg", "att018.pdg", "att020.pdg"))
        }
    }
    @Test fun missingTocIndexRejected() {
        assertThrows(PdzFormatException::class.java) {
            PdzMixedPagePlan.plan(catalog("1_2.pdg", "!00002.pdg"))
        }
    }
    @Test fun bodyGapAfterSegmentRejected() {
        assertThrows(PdzFormatException::class.java) {
            PdzMixedPagePlan.plan(catalog("1_2.pdg", "000004.pdg"))
        }
    }
    @Test fun bizarrePartIsNotTreatedAsBody() {
        assertThrows(PdzFormatException::class.java) {
            PdzMixedPagePlan.plan(catalog("1_2.pdg", "000003.pdg", "other.pdg"))
        }
    }
    @Test fun unreasonableRangeRejected() {
        assertThrows(PdzFormatException::class.java) {
            PdzMixedPagePlan.plan(catalog("1_999999.pdg", "000001.pdg"))
        }
    }
}
