package com.mossdial.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatPromptTest {

    @Test
    fun formatsATranscriptAndEndsWithTheAssistantTurn() {
        val prompt = ChatPrompt.format(
            listOf(
                ChatTurn(ChatTurn.Role.User, "Hello"),
                ChatTurn(ChatTurn.Role.Assistant, "Hi"),
                ChatTurn(ChatTurn.Role.User, "  How are you?  ")
            )
        )

        assertEquals("User: Hello\nAssistant: Hi\n\nUser: How are you?\nAssistant:", prompt)
    }

    @Test
    fun skipsBlankTurns() {
        val prompt = ChatPrompt.format(
            listOf(
                ChatTurn(ChatTurn.Role.User, "   "),
                ChatTurn(ChatTurn.Role.Assistant, ""),
                ChatTurn(ChatTurn.Role.User, "Question")
            )
        )

        assertEquals("User: Question\nAssistant:", prompt)
    }

    @Test
    fun handlesAnEmptyConversation() {
        assertEquals("Assistant:", ChatPrompt.format(emptyList()))
    }

    @Test
    fun defaultConfigCarriesTheStopSequencesForTheTranscriptFormat() {
        assertTrue(GenerationConfig().stopStrings.contains("\nUser:"))
        assertTrue(GenerationConfig().stopStrings.contains("\nAssistant:"))
    }
}
