package io.heapy.ktctogradle

import io.heapy.ktctogradle.load.globMatches
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The `modules:` globs of project.yaml, matched against module notations such as `libs/shared`.
 *
 * The function is pure, so it is tested here rather than through a project on disk.
 */
class GlobMatchesTest {
    @Test
    fun aStarMatchesWithinOneSegmentOnly() {
        assertTrue(globMatches("libs/*", "libs/shared"))
        assertFalse(globMatches("libs/*", "libs/shared/inner"))
        assertTrue(globMatches("*", "app"))
        assertFalse(globMatches("*", "libs/shared"))
    }

    @Test
    fun aQuestionMarkMatchesExactlyOneCharacterWithinASegment() {
        assertTrue(globMatches("app?", "app1"))
        assertFalse(globMatches("app?", "app"))
        assertFalse(globMatches("app?", "app12"))
        assertFalse(globMatches("app?x", "app/x"))
    }

    @Test
    fun bracesMatchAnyOfTheirAlternatives() {
        assertTrue(globMatches("{app,libs}", "app"))
        assertTrue(globMatches("{app,libs}", "libs"))
        assertFalse(globMatches("{app,libs}", "tools"))
        assertTrue(globMatches("libs/{a,b}/core", "libs/b/core"))
    }

    @Test
    fun aCharacterClassMatchesOneOfItsCharacters() {
        assertTrue(globMatches("app[123]", "app2"))
        assertFalse(globMatches("app[123]", "app4"))
    }

    @Test
    fun aNegatedCharacterClassMatchesAnythingElse() {
        assertTrue(globMatches("app[!123]", "app4"))
        assertFalse(globMatches("app[!123]", "app2"))
    }

    /** A dot is a literal in a glob, not the regex wildcard the pattern is compiled into. */
    @Test
    fun aDotIsLiteral() {
        assertTrue(globMatches("my.app", "my.app"))
        assertFalse(globMatches("my.app", "myXapp"))
    }

    /** An unmatched brace or bracket is a literal too, rather than a compilation failure. */
    @Test
    fun anUnclosedBraceOrBracketIsLiteral() {
        assertTrue(globMatches("app{x", "app{x"))
        assertTrue(globMatches("app[x", "app[x"))
    }

    @Test
    fun aPatternMustMatchTheWholeNotation() {
        assertFalse(globMatches("libs", "libs/shared"))
        assertFalse(globMatches("shared", "libs/shared"))
    }
}
