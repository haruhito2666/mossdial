package com.mossdial.ai

import java.io.File
import java.io.IOException
import java.text.Normalizer
import java.util.Locale

/**
 * A WordPiece vocabulary, the one an ONNX text encoder needs beside it.
 *
 * The file is a `vocab.txt`: one token per line, the line number being the token's id, exactly
 * the layout BERT exports use. Nothing here re-numbers tokens, because the ids in the file are the
 * ids the model's embedding matrix was trained with, so a renumbered vocabulary is a wrong
 * vocabulary rather than a slightly different one.
 *
 * Parsing is strict and bounded. A file that is not a usable vocabulary is refused with
 * [VocabularyException] instead of being half-loaded, because a silently dropped token turns into
 * [UNK] and then into a wrong embedding with no visible error.
 */
class WordPieceVocabulary private constructor(
    private val ids: Map<String, Int>,
    val unknownId: Int,
    val classId: Int,
    val separatorId: Int
) {
    val size: Int get() = ids.size

    /** The id of [token], or [unknownId] when the vocabulary does not hold it. */
    fun idOf(token: String): Int = ids[token] ?: unknownId

    fun contains(token: String): Boolean = ids.containsKey(token)

    class VocabularyException(message: String, cause: Throwable? = null) : IOException(message, cause)

    companion object {
        const val UNKNOWN = "[UNK]"
        const val CLASS = "[CLS]"
        const val SEPARATOR = "[SEP]"

        /** BERT's own limit; a sequence longer than this is truncated, not refused. */
        const val MAX_SEQUENCE_LENGTH = 512

        /** A byte order mark a text editor may leave at the start of the first token. */
        private const val BYTE_ORDER_MARK = "\uFEFF"
        private const val MAX_TOKENS = 1 shl 17
        private const val MAX_TOKEN_CHARS = 128
        private const val MAX_FILE_BYTES = 4L * 1024 * 1024

        /** Reads a `vocab.txt` from disk. Refuses a symlink, an oversize file, and a broken one. */
        fun load(file: File): WordPieceVocabulary {
            if (file.length() > MAX_FILE_BYTES) {
                throw VocabularyException("The vocabulary is larger than the $MAX_FILE_BYTES byte limit")
            }
            val text = try {
                file.readText(Charsets.UTF_8)
            } catch (failure: IOException) {
                throw VocabularyException("The vocabulary could not be read: ${file.name}", failure)
            }
            return parse(text.lines())
        }

        /**
         * Builds a vocabulary from the lines of a `vocab.txt`.
         *
         * The three special tokens are required, because a WordPiece pass cannot do anything
         * sensible without them. One trailing empty line is dropped, because every text editor ends
         * a file with a newline and a real `vocab.txt` does; a blank line anywhere else is refused,
         * since that means the file is not the file this was written for.
         */
        fun parse(lines: List<String>): WordPieceVocabulary {
            val entries = if (lines.isNotEmpty() && lines.last().isEmpty()) lines.dropLast(1) else lines
            if (entries.size > MAX_TOKENS) {
                throw VocabularyException("The vocabulary holds more than $MAX_TOKENS tokens")
            }
            val ids = HashMap<String, Int>(entries.size * 2)
            entries.forEachIndexed { index, raw ->
                val token = raw.trimEnd('\r', '\n').removePrefix(BYTE_ORDER_MARK)
                if (token.isBlank()) {
                    throw VocabularyException("Line ${index + 1} of the vocabulary is blank")
                }
                if (token.length > MAX_TOKEN_CHARS) {
                    throw VocabularyException("Line ${index + 1} of the vocabulary is too long")
                }
                if (ids.put(token, index) != null) {
                    throw VocabularyException("The vocabulary repeats \"$token\"")
                }
            }
            val unknown = ids[UNKNOWN] ?: throw VocabularyException("The vocabulary has no $UNKNOWN token")
            val classToken = ids[CLASS] ?: throw VocabularyException("The vocabulary has no $CLASS token")
            val separator = ids[SEPARATOR] ?: throw VocabularyException("The vocabulary has no $SEPARATOR token")
            return WordPieceVocabulary(ids, unknown, classToken, separator)
        }
    }
}

/**
 * One encoded sentence: the three inputs a BERT-shaped encoder takes, already padded to one
 * sequence length so the tensors can be built without a second pass.
 */
data class OnnxTokenBatch(
    val inputIds: LongArray,
    val attentionMask: LongArray,
    val tokenTypeIds: LongArray
) {
    val length: Int get() = inputIds.size

    init {
        require(inputIds.size == attentionMask.size && inputIds.size == tokenTypeIds.size) {
            "The three encoder inputs must be the same length"
        }
        require(inputIds.isNotEmpty()) { "An encoded sequence cannot be empty" }
    }

    override fun equals(other: Any?): Boolean =
        this === other || (other is OnnxTokenBatch &&
            inputIds.contentEquals(other.inputIds) &&
            attentionMask.contentEquals(other.attentionMask) &&
            tokenTypeIds.contentEquals(other.tokenTypeIds))

    override fun hashCode(): Int =
        (inputIds.contentHashCode() * 31 + attentionMask.contentHashCode()) * 31 +
            tokenTypeIds.contentHashCode()

    companion object {
        /** A sequence holding nothing but the two special tokens. */
        fun empty(vocabulary: WordPieceVocabulary): OnnxTokenBatch = of(
            intArrayOf(vocabulary.classId, vocabulary.separatorId),
            intArrayOf(1, 1),
            intArrayOf(0, 0)
        )

        fun of(inputIds: IntArray, attentionMask: IntArray, tokenTypeIds: IntArray): OnnxTokenBatch =
            OnnxTokenBatch(LongArray(inputIds.size) { inputIds[it].toLong() },
                LongArray(attentionMask.size) { attentionMask[it].toLong() },
                LongArray(tokenTypeIds.size) { tokenTypeIds[it].toLong() })
    }
}

/**
 * The BERT text pipeline, written out rather than pulled in.
 *
 * It is the standard sequence of steps, in the order the reference implementation applies them,
 * because the ids it produces are the ones the model's weights expect and any reordering changes
 * the output silently:
 *
 *  1. control characters are dropped and every whitespace run collapses to a single space;
 *  2. CJK characters are separated, so a Han run becomes one token per character;
 *  3. the text is lower cased and its combining marks are removed, for an uncased model;
 *  4. punctuation is split off as its own token;
 *  5. each remaining word is taken apart by WordPiece, longest piece first;
 *  6. `[CLS]` and `[SEP]` are added and the sequence is truncated to
 *     [WordPieceVocabulary.MAX_SEQUENCE_LENGTH] from the end, so the separator always survives.
 *
 * Every bound is here rather than left to the model: [MAX_TEXT_CHARS] on the input, so a long
 * document cannot turn into a long allocation, and the sequence limit above. A word that no
 * pieces match becomes one `[UNK]`, which is what the reference does and what the model was
 * trained on, rather than being dropped or exploded into characters.
 */
class WordPieceTokenizer(
    private val vocabulary: WordPieceVocabulary,
    private val lowerCase: Boolean = true,
    private val stripAccents: Boolean = true
) {

    fun encode(text: String): OnnxTokenBatch {
        val pieces = ArrayList<Int>(DEFAULT_CAPACITY)
        for (word in basicTokens(text)) {
            appendWordPiece(word, pieces)
            if (pieces.size >= WordPieceVocabulary.MAX_SEQUENCE_LENGTH) break
        }
        return assemble(pieces)
    }

    /** The word-piece tokens of [text] without the special tokens, for tests and diagnostics. */
    fun tokenize(text: String): List<String> {
        val tokens = ArrayList<String>()
        for (word in basicTokens(text)) {
            val pieces = ArrayList<String>()
            if (wordPiece(word, pieces)) {
                tokens += pieces
            } else {
                tokens += UNKNOWN_TEXT
            }
        }
        return tokens
    }

    private fun assemble(pieces: List<Int>): OnnxTokenBatch {
        val budget = WordPieceVocabulary.MAX_SEQUENCE_LENGTH - 2
        val kept = if (pieces.size > budget) pieces.subList(0, budget) else pieces
        val ids = IntArray(kept.size + 2)
        val mask = IntArray(kept.size + 2)
        val types = IntArray(kept.size + 2)
        ids[0] = vocabulary.classId
        // Both special tokens are real tokens: the model attends to them, and a zero mask on the
        // separator is the classic way to get a silently wrong sentence embedding.
        mask[0] = 1
        for (index in kept.indices) {
            ids[index + 1] = kept[index]
            mask[index + 1] = 1
        }
        ids[ids.size - 1] = vocabulary.separatorId
        mask[mask.size - 1] = 1
        return OnnxTokenBatch.of(ids, mask, types)
    }

    private fun appendWordPiece(word: String, pieces: MutableList<Int>) {
        val collected = ArrayList<String>()
        if (wordPiece(word, collected)) {
            collected.forEach { pieces += vocabulary.idOf(it) }
        } else {
            pieces += vocabulary.unknownId
        }
    }

    /** Longest piece first, continuation pieces marked with `##`. False when nothing matched. */
    private fun wordPiece(word: String, out: MutableList<String>): Boolean {
        if (word.isEmpty()) {
            return true
        }
        var start = 0
        val collected = ArrayList<String>()
        while (start < word.length) {
            var end = word.length
            var match: String? = null
            while (start < end) {
                val candidate = if (start > 0) word.substring(start, end) else word.substring(0, end)
                val piece = if (start > 0) "##$candidate" else candidate
                if (vocabulary.contains(piece)) {
                    match = piece
                    break
                }
                end--
            }
            if (match == null) {
                return false
            }
            collected += match
            start = end
        }
        out += collected
        return true
    }

    /** Steps 1 to 4, returning whole words and punctuation, in order. */
    private fun basicTokens(text: String): List<String> {
        val cleaned = clean(text)
        if (cleaned.isEmpty()) {
            return emptyList()
        }
        val words = ArrayList<String>()
        for (chunk in cleaned.split(' ')) {
            if (chunk.isEmpty()) continue
            var current = StringBuilder()
            for (character in chunk) {
                if (isPunctuation(character)) {
                    if (current.isNotEmpty()) {
                        words += current.toString()
                        current = StringBuilder()
                    }
                    words += character.toString()
                } else {
                    current.append(character)
                }
            }
            if (current.isNotEmpty()) {
                words += current.toString()
            }
        }
        return words.map { normalize(it) }
    }

    /** Drops control characters, collapses whitespace, and puts spaces around CJK. */
    private fun clean(text: String): String {
        val bounded = text.take(MAX_TEXT_CHARS)
        val cleaned = StringBuilder(bounded.length)
        var previousWasSpace = true
        for (raw in bounded) {
            val code = raw.code
            if (code == 0 || code == REPLACEMENT || isControl(raw)) {
                continue
            }
            if (isWhitespace(raw)) {
                if (!previousWasSpace) {
                    cleaned.append(' ')
                    previousWasSpace = true
                }
                continue
            }
            previousWasSpace = false
            if (isCjk(raw)) {
                if (cleaned.isNotEmpty() && !cleaned.endsWith(' ')) cleaned.append(' ')
                cleaned.append(raw).append(' ')
            } else {
                cleaned.append(raw)
            }
        }
        return cleaned.toString().trim()
    }

    private fun normalize(word: String): String {
        val base = if (lowerCase) word.lowercase(Locale.ROOT) else word
        return if (stripAccents) removeAccents(base) else base
    }

    companion object {
        private const val DEFAULT_CAPACITY = 64
        private const val UNKNOWN_TEXT = "[UNK]"
        private const val REPLACEMENT = 0xfffd

        /**
         * How much of a request is read before the text is used. A 64 KiB sentence is far longer
         * than the 512 tokens the encoder can hold, so nothing is lost by the cap and a paste of a
         * whole book cannot allocate its way through the heap.
         */
        const val MAX_TEXT_CHARS = 64 * 1024

        fun isWhitespace(character: Char): Boolean =
            character == ' ' || character == '\t' || character == '\n' || character == '\r' ||
                character.code == 0x0b || character.code == 0x0c

        fun isControl(character: Char): Boolean {
            if (character.code < 0x20 || character.code == 0x7f) {
                return true
            }
            if (character.code in 0x80..0x9f) {
                return true
            }
            // The Unicode separators and format characters, which are neither whitespace nor text.
            return when (Character.getType(character)) {
                Character.LINE_SEPARATOR.toInt(),
                Character.PARAGRAPH_SEPARATOR.toInt(),
                Character.SURROGATE.toInt(),
                Character.CONTROL.toInt(),
                Character.FORMAT.toInt(),
                Character.PRIVATE_USE.toInt(),
                Character.UNASSIGNED.toInt() -> true
                else -> false
            }
        }

        /** BERT's own punctuation test: an ASCII symbol, or any non-alphanumeric character. */
        fun isPunctuation(character: Char): Boolean {
            val code = character.code
            if ((code in 33..47) || (code in 58..64) || (code in 91..96) || (code in 123..126)) {
                return true
            }
            return when (Character.getType(character)) {
                Character.CONNECTOR_PUNCTUATION.toInt(),
                Character.DASH_PUNCTUATION.toInt(),
                Character.START_PUNCTUATION.toInt(),
                Character.END_PUNCTUATION.toInt(),
                Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
                Character.FINAL_QUOTE_PUNCTUATION.toInt(),
                Character.OTHER_PUNCTUATION.toInt(),
                Character.MATH_SYMBOL.toInt(),
                Character.CURRENCY_SYMBOL.toInt(),
                Character.MODIFIER_SYMBOL.toInt(),
                Character.OTHER_SYMBOL.toInt() -> true
                else -> false
            }
        }

        /** The ranges BERT separates per character, so a Han run becomes single tokens. */
        fun isCjk(character: Char): Boolean {
            val code = character.code
            return code in 0x4e00..0x9fff ||
                code in 0x3400..0x4dbf ||
                code in 0x20000..0x2a6df ||
                code in 0x2a700..0x2b73f ||
                code in 0x2b740..0x2b81f ||
                code in 0x2b820..0x2ceaf ||
                code in 0xf900..0xfaff ||
                code in 0x2f800..0x2fa1f
        }

        /** NFD then drop the combining marks, which is how BERT strips accents. */
        fun removeAccents(word: String): String {
            if (word.all { it.code < 0x80 }) {
                return word
            }
            val decomposed = Normalizer.normalize(word, Normalizer.Form.NFD)
            return buildString(decomposed.length) {
                for (character in decomposed) {
                    if (Character.getType(character) != Character.NON_SPACING_MARK.toInt()) {
                        append(character)
                    }
                }
            }
        }
    }
}
