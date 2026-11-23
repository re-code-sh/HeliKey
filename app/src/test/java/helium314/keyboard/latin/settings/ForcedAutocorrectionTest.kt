// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.settings

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
class ForcedAutocorrectionTest {
    private val replacements = mapOf("salam" to "سلام", "khob" to "خوب")

    @Test fun `exact match is replaced`() {
        assertEquals("سلام", ForcedAutocorrection.lookup(true, replacements, emptySet(), "salam"))
    }

    @Test fun `matching is case-insensitive`() {
        assertEquals("سلام", ForcedAutocorrection.lookup(true, replacements, emptySet(), "Salam"))
    }

    @Test fun `arabic yeh and kaf match persian ones`() {
        val persian = mapOf("می‌خواهم" to "می‌خواهم!")
        // typed with Arabic Yeh (U+064A) and Arabic Kaf (U+0643)
        assertEquals("می‌خواهم!", ForcedAutocorrection.lookup(true, persian, emptySet(), "مى‌خواهم"))
        // keys are normalized when the list is loaded, so the map here uses Persian letters;
        // typing the same word with Arabic Kaf (U+0643) still matches
        assertEquals("کتاب", ForcedAutocorrection.lookup(true, mapOf("کتاب" to "کتاب!"), emptySet(), "كتاب"))
    }

    @Test fun `unknown trigger returns null`() {
        assertNull(ForcedAutocorrection.lookup(true, replacements, emptySet(), "hello"))
    }

    @Test fun `disabled master switch returns null`() {
        assertNull(ForcedAutocorrection.lookup(false, replacements, emptySet(), "salam"))
    }

    @Test fun `individually disabled trigger returns null`() {
        assertNull(ForcedAutocorrection.lookup(true, replacements, setOf("salam"), "salam"))
        assertEquals("خوب", ForcedAutocorrection.lookup(true, replacements, setOf("salam"), "khob"))
    }

    @Test fun `replacement identical to typed word returns null`() {
        assertNull(ForcedAutocorrection.lookup(true, mapOf("salam" to "salam"), emptySet(), "salam"))
    }

    @Test fun `normalize trims and lowercases`() {
        assertEquals("salam", ForcedAutocorrection.normalizeTrigger("  Salam "))
    }
}
