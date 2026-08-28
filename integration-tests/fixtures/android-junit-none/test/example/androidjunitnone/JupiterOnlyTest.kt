package example.androidjunitnone

class JupiterOnlyTest {
    @org.junit.jupiter.api.Test
    fun mustBeDiscovered() {
        org.junit.jupiter.api.Assertions.assertEquals("converted", payload)
    }
}
