package app.agentichydra

import org.junit.Assert.*
import org.junit.Test

class ThinkingMessagesTest {

    @Test
    fun thinkingMessagesListIsHugeAndContainsClaudeAndHarnessMessages() {
        val messages = ThinkingMessages.ALL_MESSAGES
        assertTrue("List should be extensive (>= 200 items)", messages.size >= 200)

        // Verify key Claude spinner verbs from the prompt and leaked set
        assertTrue(messages.contains("Discombobulating"))
        assertTrue(messages.contains("Combobulating"))
        assertTrue(messages.contains("Recombobulating"))
        assertTrue(messages.contains("Flibbertigibbeting"))
        assertTrue(messages.contains("Spelunking"))
        assertTrue(messages.contains("Reticulating splines") || messages.contains("Reticulating"))
        assertTrue(messages.contains("Bloviating"))
        assertTrue(messages.contains("Clauding"))
        assertTrue(messages.contains("Canoodling"))
        assertTrue(messages.contains("Beboppin'"))

        // Verify harness phrases from popular AI tools (Cursor, Copilot, Aider, OpenHands)
        assertTrue(messages.contains("Consulting the rubber duck"))
        assertTrue(messages.contains("Bribing the compiler"))
        assertTrue(messages.contains("Refactoring reality"))
        assertTrue(messages.contains("Resolving quantum merge conflicts"))
    }

    @Test
    fun initialDescriptionProvidesContextualSentences() {
        val fixDesc = ThinkingMessages.initialDescription("Please fix the crash when clicking back")
        assertTrue(fixDesc.contains("issue") || fixDesc.contains("fix"))
        assertTrue(fixDesc.endsWith("…"))

        val testDesc = ThinkingMessages.initialDescription("Run unit test suite and verify")
        assertTrue(testDesc.contains("verification") || testDesc.contains("test"))

        val featureDesc = ThinkingMessages.initialDescription("Implement a new export button")
        assertTrue(featureDesc.contains("implementation plan") || featureDesc.contains("feature"))

        val refactorDesc = ThinkingMessages.initialDescription("Refactor the network client")
        assertTrue(refactorDesc.contains("refactoring") || refactorDesc.contains("structure"))

        val explainDesc = ThinkingMessages.initialDescription("Explain why this function works")
        assertTrue(explainDesc.contains("question") || explainDesc.contains("explanation"))

        val generalDesc = ThinkingMessages.initialDescription("Hello Hydra")
        assertTrue(generalDesc.endsWith("…"))
        assertTrue(generalDesc.isNotBlank())
    }

    @Test
    fun shuffledSequenceYieldsEllipsisMessagesAndCycles() {
        val seq = ThinkingMessages.shuffledSequence().take(20).toList()
        assertEquals(20, seq.size)
        seq.forEach { msg ->
            assertTrue("Message '$msg' must end with ellipsis", msg.endsWith("…"))
            assertTrue(msg.length > 2)
        }
        // Check variety: 20 random items shouldn't all be identical
        assertTrue(seq.distinct().size > 10)
    }
}
