package example.junitnone.multiplatform

class JupiterOnlyTest {
    @org.junit.jupiter.api.Test
    fun mustBeDiscovered() {
        org.junit.jupiter.api.Assertions.assertEquals("hello", greeting())
    }
}
