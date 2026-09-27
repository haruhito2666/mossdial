package com.mossdial.ai

import com.mossdial.ai.WordPieceVocabulary.Companion.CLASS
import com.mossdial.ai.WordPieceVocabulary.Companion.SEPARATOR
import com.mossdial.ai.WordPieceVocabulary.Companion.UNKNOWN
import com.mossdial.ai.WordPieceTokenizer.Companion.isCjk
import com.mossdial.ai.WordPieceTokenizer.Companion.isPunctuation
import com.mossdial.ai.WordPieceTokenizer.Companion.removeAccents
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WordPieceTokenizerTest {

    private val vocabulary = WordPieceVocabulary.parse(
        listOf(
            "[PAD]", UNKNOWN, CLASS, SEPARATOR,
            "hello", "world", "worldly",
            "un", "##aff", "##able",
            "cafe",
            "a", "b", "x",
            "##low", "low",
            "\u4f60", "\u597d", "\u4e16", "\u754c"
        )
    )
    private val tokenizer = WordPieceTokenizer(vocabulary)

    @Test
    fun `ids follow the line numbers of the vocabulary file`() {
        assertEquals(0, vocabulary.idOf("[PAD]"))
        assertEquals(1, vocabulary.unknownId)
        assertEquals(2, vocabulary.classId)
        assertEquals(3, vocabulary.separatorId)
        assertEquals(20, vocabulary.size)
    }

    @Test
    fun `an unknown token maps to the unknown id`() {
        assertEquals(1, vocabulary.idOf("nowhere"))
        assertFalse(vocabulary.contains("nowhere"))
        assertTrue(vocabulary.contains("hello"))
    }

    @Test
    fun `a sentence is wrapped in the two special tokens`() {
        val batch = tokenizer.encode("hello world")
        assertArrayEquals(longArrayOf(2, 4, 5, 3), batch.inputIds)
        assertArrayEquals(longArrayOf(1, 1, 1, 1), batch.attentionMask)
        assertArrayEquals(longArrayOf(0, 0, 0, 0), batch.tokenTypeIds)
        assertEquals(4, batch.length)
    }

    @Test
    fun `an empty sentence is still a valid sequence`() {
        val batch = tokenizer.encode("")
        assertArrayEquals(longArrayOf(2, 3), batch.inputIds)
        assertArrayEquals(longArrayOf(1, 1), batch.attentionMask)
    }

    @Test
    fun `whitespace and control characters are cleaned up`() {
        assertArrayEquals(longArrayOf(2, 4, 5, 3), tokenizer.encode("  hello\t\r\n world   ").inputIds)
        assertArrayEquals(longArrayOf(2, 3), tokenizer.encode(" \u0000\u000b").inputIds)
        assertArrayEquals(longArrayOf(2, 3), tokenizer.encode("").inputIds)
    }

    @Test
    fun `accents are removed for an uncased model`() {
        assertEquals("cafe", removeAccents("caf\u00e9"))
        assertEquals("cafe", removeAccents("cafe\u0301"))
        assertEquals("hello", removeAccents("hello"))
        assertEquals(10L, tokenizer.encode("CAF\u00c9").inputIds[1])
    }

    @Test
    fun `punctuation is split into its own tokens`() {
        assertArrayEquals(
            longArrayOf(2, 4, 1, 5, 1, 3),
            tokenizer.encode("hello, world!").inputIds
        )
        assertTrue(isPunctuation(','))
        assertTrue(isPunctuation('!'))
        assertTrue(isPunctuation('\u2019'))
        assertFalse(isPunctuation('a'))
    }

    @Test
    fun `a word is taken apart by its pieces`() {
        assertArrayEquals(longArrayOf(2, 7, 8, 9, 3), tokenizer.encode("unaffable").inputIds)
    }

    @Test
    fun `the longest matching piece is taken first`() {
        assertArrayEquals(longArrayOf(2, 6, 3), tokenizer.encode("worldly").inputIds)
    }

    @Test
    fun `a continuation piece is marked with a double hash`() {
        assertArrayEquals(longArrayOf(2, 13, 14, 3), tokenizer.encode("xlow").inputIds)
    }

    @Test
    fun `a word with no matching piece becomes one unknown token`() {
        assertArrayEquals(longArrayOf(2, 1, 3), tokenizer.encode("zebra").inputIds)
        assertEquals(listOf(UNKNOWN), tokenizer.tokenize("zebra"))
    }

    @Test
    fun `cjk runs are separated into single characters`() {
        assertTrue(isCjk('\u4f60'))
        assertFalse(isCjk('a'))
        assertArrayEquals(
            longArrayOf(2, 16, 17, 18, 19, 3),
            tokenizer.encode("\u4f60\u597d\u4e16\u754c").inputIds
        )
    }

    @Test
    fun `latin text next to cjk keeps its own words`() {
        assertArrayEquals(longArrayOf(2, 1, 16, 11, 3), tokenizer.encode("ab \u4f60a").inputIds)
    }

    @Test
    fun `a long sentence is truncated from the end so the separator survives`() {
        val batch = tokenizer.encode((1..600).joinToString(" ") { "x" })
        assertEquals(WordPieceVocabulary.MAX_SEQUENCE_LENGTH, batch.length)
        assertEquals(2L, batch.inputIds.first())
        assertEquals(3L, batch.inputIds.last())
        assertEquals(13L, batch.inputIds[WordPieceVocabulary.MAX_SEQUENCE_LENGTH - 2])
        assertTrue(batch.attentionMask.all { it == 1L })
    }

    @Test
    fun `a sequence at the limit is left alone`() {
        val batch = tokenizer.encode(
            (1..WordPieceVocabulary.MAX_SEQUENCE_LENGTH - 2).joinToString(" ") { "x" }
        )
        assertEquals(WordPieceVocabulary.MAX_SEQUENCE_LENGTH, batch.length)
    }

    @Test
    fun `an enormous input is bounded by the text cap`() {
        val batch = tokenizer.encode("hello ".repeat(40_000))
        assertEquals(WordPieceVocabulary.MAX_SEQUENCE_LENGTH, batch.length)
        assertEquals(3L, batch.inputIds.last())
    }

    @Test
    fun `a cased tokenizer keeps capitalisation`() {
        val cased = WordPieceTokenizer(vocabulary, lowerCase = false, stripAccents = false)
        assertEquals(listOf(UNKNOWN), cased.tokenize("Hello"))
        assertEquals(listOf("hello"), cased.tokenize("hello"))
    }

    @Test
    fun `tokenize lists the pieces without the special tokens`() {
        assertEquals(listOf("hello", "world"), tokenizer.tokenize("hello world"))
        assertEquals(listOf("un", "##aff", "##able"), tokenizer.tokenize("unaffable"))
        assertEquals(emptyList<String>(), tokenizer.tokenize("   "))
    }

    @Test
    fun `a carriage return left by a windows editor is not part of a token`() {
        val windows = WordPieceVocabulary.parse(listOf("$UNKNOWN\r", "$CLASS\r", "$SEPARATOR\r", "hello\r"))
        assertEquals(4, windows.size)
        assertEquals(3L, WordPieceTokenizer(windows).encode("hello").inputIds[1])
    }

    @Test
    fun `a byte order mark on the first line is ignored`() {
        val withBom = WordPieceVocabulary.parse(listOf("\ufeff$UNKNOWN", CLASS, SEPARATOR))
        assertEquals(0, withBom.unknownId)
    }

    @Test
    fun `one trailing newline is expected and dropped`() {
        val trailing = WordPieceVocabulary.parse(listOf("$UNKNOWN\n", "$CLASS\n", "$SEPARATOR\n", "hello\n"))
        assertEquals(4, trailing.size)
        assertEquals(3L, WordPieceTokenizer(trailing).encode("hello").inputIds[1])
    }

    @Test
    fun `a blank line anywhere else is still refused`() {
        assertThrows(WordPieceVocabulary.VocabularyException::class.java) {
            WordPieceVocabulary.parse(listOf(UNKNOWN, CLASS, SEPARATOR, "hello", "", "world"))
        }
        assertThrows(WordPieceVocabulary.VocabularyException::class.java) {
            WordPieceVocabulary.parse(listOf(UNKNOWN, CLASS, SEPARATOR, "hello", "", ""))
        }
    }

    @Test
    fun `a broken vocabulary is refused rather than half loaded`() {
        assertThrows(WordPieceVocabulary.VocabularyException::class.java) {
            WordPieceVocabulary.parse(listOf(UNKNOWN, CLASS))
        }
        assertThrows(WordPieceVocabulary.VocabularyException::class.java) {
            WordPieceVocabulary.parse(listOf(UNKNOWN, CLASS, SEPARATOR, "", "x"))
        }
        assertThrows(WordPieceVocabulary.VocabularyException::class.java) {
            WordPieceVocabulary.parse(listOf(UNKNOWN, CLASS, SEPARATOR, "a", "a"))
        }
        assertThrows(WordPieceVocabulary.VocabularyException::class.java) {
            WordPieceVocabulary.parse(listOf(UNKNOWN, CLASS, SEPARATOR, "x".repeat(129)))
        }
        assertThrows(WordPieceVocabulary.VocabularyException::class.java) {
            WordPieceVocabulary.parse((0..200_000).map { "t$it" })
        }
    }

    @Test
    fun `a vocabulary is read from a file`() {
        val directory = Files.createTempDirectory("mossdial-vocab").toFile()
        val file = File(directory, "vocab.txt")
        file.writeText(listOf(UNKNOWN, CLASS, SEPARATOR, "hello").joinToString("\n"))
        val loaded = WordPieceVocabulary.load(file)
        assertEquals(4, loaded.size)
        assertEquals(3L, WordPieceTokenizer(loaded).encode("hello").inputIds[1])
        assertTrue(directory.deleteRecursively())
    }

    @Test
    fun `an oversize vocabulary file is refused before it is read`() {
        val directory = Files.createTempDirectory("mossdial-vocab").toFile()
        val file = File(directory, "vocab.txt")
        file.writeBytes(ByteArray(4 * 1024 * 1024 + 1))
        assertThrows(WordPieceVocabulary.VocabularyException::class.java) {
            WordPieceVocabulary.load(file)
        }
        assertTrue(directory.deleteRecursively())
    }

    @Test
    fun `an empty batch is refused because a sequence needs its special tokens`() {
        assertThrows(IllegalArgumentException::class.java) { OnnxTokenBatch.of(IntArray(0), IntArray(0), IntArray(0)) }
    }

    @Test
    fun `the three inputs of a batch must be the same length`() {
        assertThrows(IllegalArgumentException::class.java) {
            OnnxTokenBatch(longArrayOf(1, 2), longArrayOf(1), longArrayOf(0, 0))
        }
    }

    @Test
    fun `an empty batch helper carries the special tokens`() {
        assertArrayEquals(longArrayOf(2, 3), OnnxTokenBatch.empty(vocabulary).inputIds)
    }

    @Test
    fun `batches compare by value`() {
        val first = tokenizer.encode("hello")
        val second = tokenizer.encode("hello")
        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
        assertFalse(first == tokenizer.encode("world"))
    }
}
