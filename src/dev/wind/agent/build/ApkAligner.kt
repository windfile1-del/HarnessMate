package dev.wind.agent.build

import dev.wind.agent.CancellationToken
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Collections
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

data class ApkAlignmentRequest(
    val inputApk: File,
    val outputApk: File,
    val alignmentBytes: Int = 4,
    val maxApkBytes: Long = 768L * 1024L * 1024L,
    val maxEntries: Int = 32_768,
    val timeoutMs: Long = 120_000L
)

class ApkAlignmentResult(
    val outcome: BuildOutcome,
    diagnostics: List<BuildDiagnostic> = emptyList(),
    artifacts: List<BuildArtifact> = emptyList(),
    val durationMs: Long = 0L
) {
    val diagnostics: List<BuildDiagnostic> = Collections.unmodifiableList(diagnostics.toList())
    val artifacts: List<BuildArtifact> = Collections.unmodifiableList(artifacts.toList())

    init {
        require(durationMs >= 0L) { "APK_ALIGNMENT_DURATION_INVALID" }
        if (outcome == BuildOutcome.SUCCESS) {
            require(this.diagnostics.none { it.severity == BuildDiagnosticSeverity.ERROR }) {
                "APK_ALIGNMENT_SUCCESS_WITH_ERROR"
            }
            require(this.artifacts.size == 1 && this.artifacts.single().kind == "android-aligned-unsigned-apk") {
                "APK_ALIGNMENT_SUCCESS_ARTIFACT_INVALID"
            }
        }
    }
}

data class ApkAlignmentInvocation(
    val inputApk: File,
    val outputApk: File,
    val alignmentBytes: Int,
    val timeoutMs: Long
)

data class ApkAlignmentInvocationResult(
    val success: Boolean,
    val diagnostics: String = ""
)

interface ApkAlignmentProvider {
    val available: Boolean

    fun align(invocation: ApkAlignmentInvocation, token: CancellationToken): ApkAlignmentInvocationResult
}

data class ApkAlignmentEntryFingerprint(
    val name: String,
    val method: Int,
    val uncompressedSize: Long,
    val sha256: String
)

class ApkAlignmentVerificationResult(
    val outcome: BuildOutcome,
    val aligned: Boolean,
    diagnostics: List<BuildDiagnostic> = emptyList(),
    entries: List<ApkAlignmentEntryFingerprint> = emptyList(),
    val apkSigningBlockPresent: Boolean = false
) {
    val diagnostics: List<BuildDiagnostic> = Collections.unmodifiableList(diagnostics.toList())
    val entries: List<ApkAlignmentEntryFingerprint> = Collections.unmodifiableList(entries.toList())
}

/** Reads ZIP offsets independently of zipalign and checks STORED payload alignment. */
class ApkAlignmentVerifier {
    fun verify(
        file: File,
        alignmentBytes: Int = 4,
        maxApkBytes: Long = DEFAULT_MAX_APK_BYTES,
        maxEntries: Int = DEFAULT_MAX_ENTRIES,
        token: CancellationToken = CancellationToken()
    ): ApkAlignmentVerificationResult {
        return try {
            if (alignmentBytes <= 0 || (alignmentBytes and (alignmentBytes - 1)) != 0 ||
                maxApkBytes <= 0L || maxEntries <= 0
            ) {
                problem(BuildOutcome.FAILED, "APK_ALIGNMENT_INPUT_INVALID", "Verifier limits or alignment are invalid")
            }
            token.throwIfCancelled()
            val path = safeFilePath(file, "APK_ALIGNMENT_OUTPUT_INVALID")
            validateOrdinaryFile(path, "APK_ALIGNMENT_OUTPUT_INVALID", maxApkBytes)
            FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
                val parsed = parseZip(channel, channel.size(), alignmentBytes, maxEntries, token)
                val fingerprints = fingerprintEntries(path, parsed.entries, maxApkBytes, token)
                val aligned = parsed.aligned
                ApkAlignmentVerificationResult(
                    BuildOutcome.SUCCESS,
                    aligned,
                    diagnostics = listOf(info(
                        if (aligned) "APK_ALIGNMENT_VERIFIED" else "APK_ALIGNMENT_NOT_ALIGNED",
                        if (aligned) "Every STORED non-directory payload is aligned to $alignmentBytes bytes"
                        else "At least one STORED non-directory payload is not aligned to $alignmentBytes bytes"
                    )),
                    entries = fingerprints,
                    apkSigningBlockPresent = parsed.signingBlockPresent
                )
            }
        } catch (cancelled: CancellationToken.CancelledException) {
            verificationFailure(BuildOutcome.CANCELLED, "BUILD_CANCELLED", "APK verification was cancelled")
        } catch (failure: ApkAlignmentProblem) {
            verificationFailure(failure.outcome, failure.code, failure.message ?: failure.code, failure.path)
        } catch (error: Exception) {
            verificationFailure(
                BuildOutcome.FAILED,
                "APK_ALIGNMENT_OUTPUT_INVALID",
                "Could not verify APK ZIP structure: ${error.message ?: error.javaClass.simpleName}",
                file.toPath()
            )
        }
    }

    private data class RawEntry(
        val name: String,
        val rawName: ByteArray,
        val method: Int,
        val flags: Int,
        val crc: Long,
        val compressedSize: Long,
        val uncompressedSize: Long,
        val localHeaderOffset: Long,
        var dataOffset: Long = 0L,
        var recordEnd: Long = 0L
    )

    private data class ParsedZip(
        val entries: List<RawEntry>,
        val aligned: Boolean,
        val signingBlockPresent: Boolean
    )

    private fun parseZip(
        channel: FileChannel,
        fileSize: Long,
        alignmentBytes: Int,
        maxEntries: Int,
        token: CancellationToken
    ): ParsedZip {
        if (fileSize < EOCD_MIN_BYTES) problem(BuildOutcome.FAILED, "APK_ALIGNMENT_OUTPUT_INVALID", "ZIP is shorter than an EOCD")
        val searchStart = maxOf(0L, fileSize - EOCD_MIN_BYTES - MAX_EOCD_COMMENT_BYTES)
        val tailLength = (fileSize - searchStart).toInt()
        val tail = readAt(channel, searchStart, tailLength, fileSize)
        var lastFailure: ApkAlignmentProblem? = null
        for (localOffset in tail.size - EOCD_MIN_BYTES downTo 0) {
            token.throwIfCancelled()
            if (u32(tail, localOffset) != EOCD_SIGNATURE) continue
            val commentLength = u16(tail, localOffset + 20)
            if (localOffset + EOCD_MIN_BYTES + commentLength != tail.size) continue
            val eocdOffset = searchStart + localOffset
            try {
                return parseEocdCandidate(
                    channel, fileSize, tail, localOffset, eocdOffset, alignmentBytes, maxEntries, token
                )
            } catch (failure: ApkAlignmentProblem) {
                lastFailure = failure
            }
        }
        throw lastFailure ?: ApkAlignmentProblem(
            BuildOutcome.FAILED,
            "APK_ALIGNMENT_OUTPUT_INVALID",
            "ZIP EOCD with a comment ending at EOF was not found"
        )
    }

    private fun parseEocdCandidate(
        channel: FileChannel,
        fileSize: Long,
        tail: ByteArray,
        eocdIndex: Int,
        eocdOffset: Long,
        alignmentBytes: Int,
        maxEntries: Int,
        token: CancellationToken
    ): ParsedZip {
        val diskNumber = u16(tail, eocdIndex + 4)
        val centralDisk = u16(tail, eocdIndex + 6)
        val diskEntryCount = u16(tail, eocdIndex + 8)
        val entryCount = u16(tail, eocdIndex + 10)
        val centralSize = u32(tail, eocdIndex + 12)
        val centralOffset = u32(tail, eocdIndex + 16)

        if (diskNumber != 0 || centralDisk != 0 || diskEntryCount != entryCount) {
            problem(BuildOutcome.UNSUPPORTED, "APK_ALIGNMENT_ZIP_UNSUPPORTED", "Multi-disk ZIP archives are unsupported")
        }
        if (entryCount == ZIP16_SENTINEL || centralSize == ZIP32_SENTINEL || centralOffset == ZIP32_SENTINEL) {
            problem(BuildOutcome.UNSUPPORTED, "APK_ALIGNMENT_ZIP_UNSUPPORTED", "ZIP64 archives are unsupported")
        }
        if (eocdOffset >= ZIP64_LOCATOR_BYTES) {
            val locator = readAt(channel, eocdOffset - ZIP64_LOCATOR_BYTES, ZIP64_LOCATOR_BYTES, fileSize)
            if (u32(locator, 0) == ZIP64_LOCATOR_SIGNATURE) {
                problem(BuildOutcome.UNSUPPORTED, "APK_ALIGNMENT_ZIP_UNSUPPORTED", "ZIP64 locator is unsupported")
            }
        }
        if (entryCount > maxEntries) {
            problem(BuildOutcome.RESOURCE_LIMIT, "APK_ALIGNMENT_INPUT_LIMIT", "ZIP entry count exceeds $maxEntries")
        }
        val centralEnd = checkedAdd(centralOffset, centralSize, "central directory end")
        if (centralEnd != eocdOffset || centralEnd > fileSize) {
            problem(BuildOutcome.FAILED, "APK_ALIGNMENT_OUTPUT_INVALID", "Central directory bounds do not meet the EOCD")
        }

        val entries = readCentralDirectory(channel, centralOffset, centralSize, entryCount, eocdOffset, fileSize, token)
        val signingBlock = inspectSigningBlock(channel, centralOffset, fileSize)
        validateLocalHeaders(channel, entries, centralOffset, signingBlock.startOffset ?: centralOffset, fileSize, alignmentBytes, token)
        return ParsedZip(entries, entries.all { it.method != ZipEntry.STORED || isZeroDirectory(it) || it.dataOffset % alignmentBytes == 0L }, signingBlock.present)
    }

    private fun readCentralDirectory(
        channel: FileChannel,
        centralOffset: Long,
        centralSize: Long,
        entryCount: Int,
        eocdOffset: Long,
        fileSize: Long,
        token: CancellationToken
    ): List<RawEntry> {
        val centralEnd = checkedAdd(centralOffset, centralSize, "central directory end")
        if (centralOffset < 0L || centralEnd > eocdOffset || centralEnd > fileSize) {
            problem(BuildOutcome.FAILED, "APK_ALIGNMENT_OUTPUT_INVALID", "Central directory is outside the ZIP bounds")
        }
        val entries = ArrayList<RawEntry>(entryCount)
        val names = HashSet<String>(entryCount * 2)
        var cursor = centralOffset
        repeat(entryCount) {
            token.throwIfCancelled()
            val header = readAt(channel, cursor, CENTRAL_HEADER_BYTES, fileSize)
            if (u32(header, 0) != CENTRAL_SIGNATURE) {
                problem(BuildOutcome.FAILED, "APK_ALIGNMENT_OUTPUT_INVALID", "Malformed central-directory header")
            }
            val flags = u16(header, 8)
            validateFlagsAndMethod(flags, u16(header, 10))
            val method = u16(header, 10)
            val crc = u32(header, 16)
            val compressedSize = u32(header, 20)
            val uncompressedSize = u32(header, 24)
            val nameLength = u16(header, 28)
            val extraLength = u16(header, 30)
            val commentLength = u16(header, 32)
            val diskStart = u16(header, 34)
            val localOffset = u32(header, 42)
            if (diskStart != 0) problem(BuildOutcome.UNSUPPORTED, "APK_ALIGNMENT_ZIP_UNSUPPORTED", "Split central-directory entries are unsupported")
            if (compressedSize == ZIP32_SENTINEL || uncompressedSize == ZIP32_SENTINEL || localOffset == ZIP32_SENTINEL) {
                problem(BuildOutcome.UNSUPPORTED, "APK_ALIGNMENT_ZIP_UNSUPPORTED", "ZIP64 entry fields are unsupported")
            }
            if (nameLength <= 0) problem(BuildOutcome.FAILED, "APK_ALIGNMENT_OUTPUT_INVALID", "ZIP entry name is empty")
            val variableLength = nameLength.toLong() + extraLength.toLong() + commentLength.toLong()
            val next = checkedAdd(checkedAdd(cursor, CENTRAL_HEADER_BYTES.toLong(), "central header"), variableLength, "central entry")
            if (next > centralEnd) problem(BuildOutcome.FAILED, "APK_ALIGNMENT_OUTPUT_INVALID", "Central entry exceeds declared directory bounds")
            val variable = readAt(channel, cursor + CENTRAL_HEADER_BYTES, variableLength.toInt(), fileSize)
            val rawName = variable.copyOfRange(0, nameLength)
            val extra = variable.copyOfRange(nameLength, nameLength + extraLength)
            validateExtraFields(extra, "central directory")
            val name = decodeZipName(rawName, flags)
            if (!isSafeZipName(name)) problem(BuildOutcome.FAILED, "APK_ALIGNMENT_OUTPUT_INVALID", "Unsafe ZIP entry name: $name")
            if (!names.add(name)) problem(BuildOutcome.FAILED, "APK_ALIGNMENT_OUTPUT_INVALID", "Duplicate ZIP entry name: $name")
            if (method == ZipEntry.STORED && compressedSize != uncompressedSize) {
                problem(BuildOutcome.FAILED, "APK_ALIGNMENT_OUTPUT_INVALID", "STORED entry sizes differ: $name")
            }
            entries += RawEntry(name, rawName, method, flags, crc, compressedSize, uncompressedSize, localOffset)
            cursor = next
        }
        if (cursor != centralEnd) {
            problem(BuildOutcome.UNSUPPORTED, "APK_ALIGNMENT_ZIP_UNSUPPORTED", "Central-directory digital signatures or trailing records are unsupported")
        }
        return entries
    }

    private data class SigningBlockInfo(val present: Boolean, val startOffset: Long?)

    private fun inspectSigningBlock(channel: FileChannel, centralOffset: Long, fileSize: Long): SigningBlockInfo {
        if (centralOffset < APK_SIG_MAGIC.size) return SigningBlockInfo(false, null)
        val magicOffset = centralOffset - APK_SIG_MAGIC.size
        if (!readAt(channel, magicOffset, APK_SIG_MAGIC.size, fileSize).contentEquals(APK_SIG_MAGIC)) {
            return SigningBlockInfo(false, null)
        }
        if (centralOffset < APK_SIG_FOOTER_BYTES) return SigningBlockInfo(true, null)
        val footerOffset = centralOffset - APK_SIG_FOOTER_BYTES
        val footer = readAt(channel, footerOffset, APK_SIG_FOOTER_BYTES, fileSize)
        val trailingSize = u64OrNull(footer, 0) ?: return SigningBlockInfo(true, null)
        if (trailingSize < APK_SIG_MIN_SIZE || trailingSize > centralOffset - 8L) return SigningBlockInfo(true, null)
        val startOffset = centralOffset - trailingSize - 8L
        val leading = readAt(channel, startOffset, 8, fileSize)
        return if (u64OrNull(leading, 0) == trailingSize) SigningBlockInfo(true, startOffset) else SigningBlockInfo(true, null)
    }

    private fun validateLocalHeaders(
        channel: FileChannel,
        entries: List<RawEntry>,
        centralOffset: Long,
        recordBoundary: Long,
        fileSize: Long,
        alignmentBytes: Int,
        token: CancellationToken
    ) {
        val localOffsets = HashSet<Long>(entries.size * 2)
        for (entry in entries) {
            token.throwIfCancelled()
            if (!localOffsets.add(entry.localHeaderOffset)) {
                problem(BuildOutcome.FAILED, "APK_ALIGNMENT_OUTPUT_INVALID", "Multiple central entries reference one local header")
            }
            if (entry.localHeaderOffset < 0L || entry.localHeaderOffset >= recordBoundary) {
                problem(BuildOutcome.FAILED, "APK_ALIGNMENT_OUTPUT_INVALID", "Local header offset is outside the data region: ${entry.name}")
            }
            val header = readAt(channel, entry.localHeaderOffset, LOCAL_HEADER_BYTES, fileSize)
            if (u32(header, 0) != LOCAL_SIGNATURE) {
                problem(BuildOutcome.FAILED, "APK_ALIGNMENT_OUTPUT_INVALID", "Malformed local-file header: ${entry.name}")
            }
            val localFlags = u16(header, 6)
            val localMethod = u16(header, 8)
            validateFlagsAndMethod(localFlags, localMethod)
            if (localFlags != entry.flags || localMethod != entry.method) {
                problem(BuildOutcome.FAILED, "APK_ALIGNMENT_OUTPUT_INVALID", "Local and central flags/method differ: ${entry.name}")
            }
            val localCrc = u32(header, 14)
            val localCompressed = u32(header, 18)
            val localUncompressed = u32(header, 22)
            if (localCompressed == ZIP32_SENTINEL || localUncompressed == ZIP32_SENTINEL) {
                problem(BuildOutcome.UNSUPPORTED, "APK_ALIGNMENT_ZIP_UNSUPPORTED", "Local ZIP64 fields are unsupported")
            }
            val nameLength = u16(header, 26)
            val extraLength = u16(header, 28)
            val variableLength = nameLength.toLong() + extraLength.toLong()
            val dataOffset = checkedAdd(
                checkedAdd(entry.localHeaderOffset, LOCAL_HEADER_BYTES.toLong(), "local header"),
                variableLength,
                "entry payload"
            )
            if (dataOffset > recordBoundary) problem(BuildOutcome.FAILED, "APK_ALIGNMENT_OUTPUT_INVALID", "Local header fields exceed the data region: ${entry.name}")
            val variable = readAt(channel, entry.localHeaderOffset + LOCAL_HEADER_BYTES, variableLength.toInt(), fileSize)
            val rawLocalName = variable.copyOfRange(0, nameLength)
            if (!rawLocalName.contentEquals(entry.rawName)) {
                problem(BuildOutcome.FAILED, "APK_ALIGNMENT_OUTPUT_INVALID", "Local and central filenames differ: ${entry.name}")
            }
            // Android's zipalign appends 1-3 zero bytes to the local-header extra
            // area (without wrapping them in a ZIP extra-field TLV) to align STORED
            // payloads. Accept only that narrow form, and only when it actually
            // makes this payload 4-byte aligned.
            validateExtraFields(
                variable.copyOfRange(nameLength, nameLength + extraLength),
                "local header",
                allowZipalignPadding = entry.method == ZipEntry.STORED && alignmentBytes == 4 &&
                    dataOffset % alignmentBytes == 0L
            )

            val hasDescriptor = entry.flags and DATA_DESCRIPTOR_FLAG != 0
            if (hasDescriptor) {
                if ((localCrc != 0L && localCrc != entry.crc) ||
                    (localCompressed != 0L && localCompressed != entry.compressedSize) ||
                    (localUncompressed != 0L && localUncompressed != entry.uncompressedSize)
                ) {
                    problem(BuildOutcome.FAILED, "APK_ALIGNMENT_OUTPUT_INVALID", "Descriptor entry local sizes conflict with its central record: ${entry.name}")
                }
            } else if (localCrc != entry.crc || localCompressed != entry.compressedSize || localUncompressed != entry.uncompressedSize) {
                problem(BuildOutcome.FAILED, "APK_ALIGNMENT_OUTPUT_INVALID", "Local and central CRC/sizes differ: ${entry.name}")
            }

            val dataEnd = checkedAdd(dataOffset, entry.compressedSize, "entry compressed data")
            if (dataEnd > recordBoundary) {
                problem(BuildOutcome.FAILED, "APK_ALIGNMENT_OUTPUT_INVALID", "Entry data overlaps central directory or signing block: ${entry.name}")
            }
            entry.dataOffset = dataOffset
            entry.recordEnd = if (hasDescriptor) descriptorEnd(channel, dataEnd, entry, recordBoundary, fileSize) else dataEnd
            if (entry.recordEnd > recordBoundary) {
                problem(BuildOutcome.FAILED, "APK_ALIGNMENT_OUTPUT_INVALID", "Entry descriptor overlaps central directory or signing block: ${entry.name}")
            }
            if (entry.method == ZipEntry.STORED && !isZeroDirectory(entry) && dataOffset % alignmentBytes != 0L) {
                // Keep parsing all entries; the final aligned flag is derived after structure checks.
            }
        }
        val ordered = entries.sortedBy { it.localHeaderOffset }
        for (index in 0 until ordered.size - 1) {
            if (ordered[index].recordEnd > ordered[index + 1].localHeaderOffset) {
                problem(BuildOutcome.FAILED, "APK_ALIGNMENT_OUTPUT_INVALID", "ZIP local entry records overlap")
            }
        }
        if (ordered.lastOrNull()?.recordEnd?.let { it > centralOffset } == true) {
            problem(BuildOutcome.FAILED, "APK_ALIGNMENT_OUTPUT_INVALID", "ZIP local entry records overlap central directory")
        }
    }

    private fun descriptorEnd(
        channel: FileChannel,
        dataEnd: Long,
        entry: RawEntry,
        recordBoundary: Long,
        fileSize: Long
    ): Long {
        val noSignatureEnd = checkedAdd(dataEnd, DATA_DESCRIPTOR_BYTES.toLong(), "data descriptor")
        val signatureEnd = checkedAdd(noSignatureEnd, 4L, "signed data descriptor")
        val noSignatureValid = noSignatureEnd <= recordBoundary && runCatching {
            val data = readAt(channel, dataEnd, DATA_DESCRIPTOR_BYTES, fileSize)
            u32(data, 0) == entry.crc && u32(data, 4) == entry.compressedSize && u32(data, 8) == entry.uncompressedSize
        }.getOrNull() ?: false
        val signatureValid = signatureEnd <= recordBoundary && runCatching {
            val data = readAt(channel, dataEnd, DATA_DESCRIPTOR_BYTES + 4, fileSize)
            u32(data, 0) == DATA_DESCRIPTOR_SIGNATURE && u32(data, 4) == entry.crc &&
                u32(data, 8) == entry.compressedSize && u32(data, 12) == entry.uncompressedSize
        }.getOrNull() ?: false
        if (signatureValid) return signatureEnd
        if (noSignatureValid) return noSignatureEnd
        problem(BuildOutcome.FAILED, "APK_ALIGNMENT_OUTPUT_INVALID", "Invalid or truncated data descriptor: ${entry.name}")
    }

    private fun fingerprintEntries(
        path: Path,
        rawEntries: List<RawEntry>,
        maxApkBytes: Long,
        token: CancellationToken
    ): List<ApkAlignmentEntryFingerprint> {
        val rawByName = rawEntries.associateBy { it.name }
        val seen = HashSet<String>(rawEntries.size * 2)
        val fingerprints = ArrayList<ApkAlignmentEntryFingerprint>(rawEntries.size)
        var totalUncompressed = 0L
        try {
            ZipFile(path.toFile()).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    token.throwIfCancelled()
                    val entry = entries.nextElement()
                    val raw = rawByName[entry.name]
                        ?: problem(BuildOutcome.FAILED, "APK_ALIGNMENT_OUTPUT_INVALID", "ZipFile and binary parser disagree on entry name: ${entry.name}")
                    if (!seen.add(entry.name)) {
                        problem(BuildOutcome.FAILED, "APK_ALIGNMENT_OUTPUT_INVALID", "Duplicate ZIP entry: ${entry.name}")
                    }
                    if (entry.method != raw.method || entry.size != raw.uncompressedSize ||
                        entry.compressedSize != raw.compressedSize || entry.crc != raw.crc
                    ) {
                        problem(BuildOutcome.FAILED, "APK_ALIGNMENT_OUTPUT_INVALID", "ZipFile metadata differs from ZIP binary directory: ${entry.name}")
                    }
                    if (entry.size < 0L || entry.size > maxApkBytes - totalUncompressed) {
                        problem(BuildOutcome.RESOURCE_LIMIT, "APK_ALIGNMENT_INPUT_LIMIT", "Total uncompressed APK bytes exceed $maxApkBytes")
                    }
                    val measured = zip.getInputStream(entry).use { digestBounded(it, token, maxApkBytes - totalUncompressed) }
                    if (measured.size != raw.uncompressedSize || measured.crc != raw.crc) {
                        problem(BuildOutcome.FAILED, "APK_ALIGNMENT_OUTPUT_INVALID", "ZIP payload checksum or size differs: ${entry.name}")
                    }
                    totalUncompressed += measured.size
                    fingerprints += ApkAlignmentEntryFingerprint(entry.name, entry.method, measured.size, measured.sha256)
                }
            }
        } catch (failure: ApkAlignmentProblem) {
            throw failure
        } catch (cancelled: CancellationToken.CancelledException) {
            throw cancelled
        } catch (error: Exception) {
            problem(BuildOutcome.FAILED, "APK_ALIGNMENT_OUTPUT_INVALID", "Could not read ZIP payloads: ${error.message ?: error.javaClass.simpleName}", path)
        }
        if (seen.size != rawEntries.size) {
            problem(BuildOutcome.FAILED, "APK_ALIGNMENT_OUTPUT_INVALID", "ZipFile and binary parser entry counts differ", path)
        }
        return fingerprints.sortedBy { it.name }
    }

    private data class StreamMeasurement(val size: Long, val crc: Long, val sha256: String)

    private fun digestBounded(input: InputStream, token: CancellationToken, remaining: Long): StreamMeasurement {
        val digest = MessageDigest.getInstance("SHA-256")
        val crc = CRC32()
        val buffer = ByteArray(STREAM_BUFFER_BYTES)
        var size = 0L
        while (true) {
            token.throwIfCancelled()
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            if (read.toLong() > remaining - size) {
                problem(BuildOutcome.RESOURCE_LIMIT, "APK_ALIGNMENT_INPUT_LIMIT", "Uncompressed APK contents exceed configured size limit")
            }
            size += read
            digest.update(buffer, 0, read)
            crc.update(buffer, 0, read)
        }
        return StreamMeasurement(size, crc.value, digest.digest().toHex())
    }

    private fun validateFlagsAndMethod(flags: Int, method: Int) {
        if (flags and ENCRYPTED_FLAGS_MASK != 0) {
            problem(BuildOutcome.UNSUPPORTED, "APK_ALIGNMENT_ZIP_UNSUPPORTED", "Encrypted ZIP entries are unsupported")
        }
        if (flags and SUPPORTED_FLAGS_MASK.inv() != 0) {
            problem(BuildOutcome.UNSUPPORTED, "APK_ALIGNMENT_ZIP_UNSUPPORTED", "ZIP entry uses unsupported general-purpose flags")
        }
        if (method != ZipEntry.STORED && method != ZipEntry.DEFLATED) {
            problem(BuildOutcome.UNSUPPORTED, "APK_ALIGNMENT_ZIP_UNSUPPORTED", "Only STORED and DEFLATED ZIP entries are supported")
        }
        if (method == ZipEntry.STORED && flags and DEFLATE_OPTION_FLAGS != 0) {
            problem(BuildOutcome.UNSUPPORTED, "APK_ALIGNMENT_ZIP_UNSUPPORTED", "STORED entry contains DEFLATED-only option flags")
        }
    }

    private fun validateExtraFields(extra: ByteArray, label: String, allowZipalignPadding: Boolean = false) {
        var offset = 0
        while (offset < extra.size) {
            if (extra.size - offset < 4) {
                val trailing = extra.copyOfRange(offset, extra.size)
                if (allowZipalignPadding && trailing.size <= 3 && trailing.all { it == 0.toByte() }) return
                problem(BuildOutcome.FAILED, "APK_ALIGNMENT_OUTPUT_INVALID", "Truncated ZIP extra field in $label")
            }
            val fieldId = u16(extra, offset)
            val fieldLength = u16(extra, offset + 2)
            if (fieldId == ZIP64_EXTRA_ID) {
                problem(BuildOutcome.UNSUPPORTED, "APK_ALIGNMENT_ZIP_UNSUPPORTED", "ZIP64 extra fields are unsupported")
            }
            val next = offset.toLong() + 4L + fieldLength
            if (next > extra.size) {
                problem(BuildOutcome.FAILED, "APK_ALIGNMENT_OUTPUT_INVALID", "ZIP extra field length exceeds its header")
            }
            offset = next.toInt()
        }
    }

    private fun decodeZipName(raw: ByteArray, flags: Int): String = try {
        val charset = if (flags and UTF8_FLAG != 0) StandardCharsets.UTF_8 else CP437
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(raw))
            .toString()
    } catch (error: Exception) {
        problem(BuildOutcome.FAILED, "APK_ALIGNMENT_OUTPUT_INVALID", "ZIP entry name has invalid character encoding")
    }

    private fun isSafeZipName(name: String): Boolean {
        if (name.isEmpty() || '\u0000' in name || '\\' in name || name.startsWith('/')) return false
        if (name.length >= 2 && name[0].isLetter() && name[1] == ':') return false
        val body = if (name.endsWith('/')) name.dropLast(1) else name
        if (body.isEmpty() || body.endsWith('/')) return false
        return body.split('/').none { it.isEmpty() || it == "." || it == ".." }
    }

    private fun isZeroDirectory(entry: RawEntry): Boolean = entry.name.endsWith('/') && entry.uncompressedSize == 0L

    private fun readAt(channel: FileChannel, position: Long, length: Int, fileSize: Long): ByteArray {
        if (position < 0L || length < 0 || position > fileSize || length.toLong() > fileSize - position) {
            problem(BuildOutcome.FAILED, "APK_ALIGNMENT_OUTPUT_INVALID", "ZIP structure points outside the APK bounds")
        }
        val buffer = ByteBuffer.allocate(length)
        var offset = position
        while (buffer.hasRemaining()) {
            val read = channel.read(buffer, offset)
            if (read < 0) problem(BuildOutcome.FAILED, "APK_ALIGNMENT_OUTPUT_INVALID", "Unexpected EOF in ZIP structure")
            if (read == 0) continue
            offset += read
        }
        return buffer.array()
    }

    private fun checkedAdd(first: Long, second: Long, label: String): Long {
        if (first < 0L || second < 0L || second > Long.MAX_VALUE - first) {
            problem(BuildOutcome.FAILED, "APK_ALIGNMENT_OUTPUT_INVALID", "ZIP $label offset overflow")
        }
        return first + second
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun u32(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xffL) or
            ((bytes[offset + 1].toLong() and 0xffL) shl 8) or
            ((bytes[offset + 2].toLong() and 0xffL) shl 16) or
            ((bytes[offset + 3].toLong() and 0xffL) shl 24)

    private fun u64OrNull(bytes: ByteArray, offset: Int): Long? {
        val low = u32(bytes, offset)
        val high = u32(bytes, offset + 4)
        if (high > 0x7fff_ffffL) return null
        return (high shl 32) or low
    }

    private fun verificationFailure(
        outcome: BuildOutcome,
        code: String,
        message: String,
        path: Path? = null
    ) = ApkAlignmentVerificationResult(
        outcome,
        aligned = false,
        diagnostics = listOf(diagnostic(code, message, path))
    )

    private fun info(code: String, message: String) = BuildDiagnostic(
        BuildDiagnosticSeverity.INFO, code, message, BuildDiagnosticSource.PACKAGING
    )

    private fun safeFilePath(file: File, code: String): Path =
        try {
            val absolute = file.toPath().toAbsolutePath()
            if (absolute.any { it.toString() == ".." }) {
                problem(BuildOutcome.FAILED, code, "APK path must not contain parent traversal", absolute)
            }
            val path = absolute.normalize()
            inspectComponents(path, allowMissingLeaf = false, allowMissingParents = false, code = code)
            path
        } catch (failure: ApkAlignmentProblem) {
            throw failure
        } catch (error: Exception) {
            problem(BuildOutcome.FAILED, code, "APK path is invalid: ${error.message ?: error.javaClass.simpleName}", file.toPath())
        }

    private fun validateOrdinaryFile(path: Path, code: String, maxBytes: Long) {
        inspectComponents(path, allowMissingLeaf = false, allowMissingParents = false, code = code)
        val attrs = readAttributes(path, code)
        if (attrs.isSymbolicLink || !attrs.isRegularFile || attrs.size() <= 0L) {
            problem(BuildOutcome.FAILED, code, "APK must be an ordinary, non-empty file", path)
        }
        if (attrs.size() > maxBytes) {
            problem(BuildOutcome.RESOURCE_LIMIT, "APK_ALIGNMENT_INPUT_LIMIT", "APK size exceeds $maxBytes bytes", path)
        }
    }

    private fun readAttributes(path: Path, code: String): BasicFileAttributes = try {
        Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    } catch (error: Exception) {
        problem(BuildOutcome.FAILED, code, "APK path is missing or unreadable: $path", path)
    }

    private fun inspectComponents(path: Path, allowMissingLeaf: Boolean, allowMissingParents: Boolean, code: String) {
        val root = path.root ?: problem(BuildOutcome.FAILED, code, "APK path has no filesystem root", path)
        var current = root
        for (component in root.relativize(path)) {
            current = current.resolve(component)
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                val isLeaf = current == path
                if ((isLeaf && allowMissingLeaf) || (!isLeaf && allowMissingParents)) continue
                problem(BuildOutcome.FAILED, code, "APK path component does not exist: $current", current)
            }
            val attrs = readAttributes(current, code)
            if (attrs.isSymbolicLink) problem(BuildOutcome.FAILED, code, "APK path contains a symbolic link: $current", current)
            if (current != path && !attrs.isDirectory) problem(BuildOutcome.FAILED, code, "APK path parent is not a directory: $current", current)
        }
    }

    private fun diagnostic(code: String, message: String, path: Path? = null) = BuildDiagnostic(
        BuildDiagnosticSeverity.ERROR, code, message, BuildDiagnosticSource.PACKAGING, path?.toString()
    )

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun problem(outcome: BuildOutcome, code: String, message: String, path: Path? = null): Nothing =
        throw ApkAlignmentProblem(outcome, code, message, path)

    private class ApkAlignmentProblem(
        val outcome: BuildOutcome,
        val code: String,
        override val message: String,
        val path: Path? = null
    ) : Exception(message)

    companion object {
        const val DEFAULT_MAX_APK_BYTES = 768L * 1024L * 1024L
        const val DEFAULT_MAX_ENTRIES = 32_768
        private const val STREAM_BUFFER_BYTES = 32 * 1024
        private const val EOCD_MIN_BYTES = 22
        private const val MAX_EOCD_COMMENT_BYTES = 65_535
        private const val ZIP64_LOCATOR_BYTES = 20
        private const val CENTRAL_HEADER_BYTES = 46
        private const val LOCAL_HEADER_BYTES = 30
        private const val DATA_DESCRIPTOR_BYTES = 12
        private const val EOCD_SIGNATURE = 0x06054b50L
        private const val CENTRAL_SIGNATURE = 0x02014b50L
        private const val LOCAL_SIGNATURE = 0x04034b50L
        private const val ZIP64_LOCATOR_SIGNATURE = 0x07064b50L
        private const val DATA_DESCRIPTOR_SIGNATURE = 0x08074b50L
        private const val ZIP16_SENTINEL = 0xffff
        private const val ZIP32_SENTINEL = 0xffff_ffffL
        private const val ZIP64_EXTRA_ID = 0x0001
        private const val UTF8_FLAG = 1 shl 11
        private const val DATA_DESCRIPTOR_FLAG = 1 shl 3
        private const val ENCRYPTED_FLAGS_MASK = (1 shl 0) or (1 shl 6) or (1 shl 13)
        private const val DEFLATE_OPTION_FLAGS = (1 shl 1) or (1 shl 2)
        private const val SUPPORTED_FLAGS_MASK = DEFLATE_OPTION_FLAGS or DATA_DESCRIPTOR_FLAG or UTF8_FLAG
        private const val APK_SIG_FOOTER_BYTES = 24
        private const val APK_SIG_MIN_SIZE = 24L
        private val APK_SIG_MAGIC = "APK Sig Block 42".toByteArray(StandardCharsets.US_ASCII)
        private val CP437 = java.nio.charset.Charset.forName("IBM437")
    }
}

/** Explicitly injected host-side proof provider; not the eventual in-app provider. */
class HostZipalignProvider(private val executable: File) : ApkAlignmentProvider {
    override val available: Boolean
        get() = runCatching { resolveExecutable() != null }.getOrNull() ?: false

    override fun align(invocation: ApkAlignmentInvocation, token: CancellationToken): ApkAlignmentInvocationResult {
        if (token.isCancelled) return ApkAlignmentInvocationResult(false, "cancelled")
        if (invocation.alignmentBytes != 4 || invocation.timeoutMs <= 0L) {
            return ApkAlignmentInvocationResult(false, "AP2A requires 4-byte alignment and a positive timeout")
        }
        if (invocation.inputApk.path.isBlank() || invocation.outputApk.path.isBlank() ||
            '\u0000' in invocation.inputApk.path || '\u0000' in invocation.outputApk.path
        ) {
            return ApkAlignmentInvocationResult(false, "input or output path is invalid")
        }
        val executablePath = try { resolveExecutable() } catch (error: Exception) {
            return ApkAlignmentInvocationResult(false, "zipalign executable is missing or unsafe: ${error.message}")
        } ?: return ApkAlignmentInvocationResult(false, "zipalign executable is missing or unsafe")
        val inputPath = try { validateInput(invocation.inputApk) } catch (error: Exception) {
            return ApkAlignmentInvocationResult(false, "zipalign input is invalid: ${error.message}")
        }
        val outputPath = try { validateOutput(invocation.outputApk) } catch (error: Exception) {
            return ApkAlignmentInvocationResult(false, "zipalign output is invalid: ${error.message}")
        }
        if (inputPath == outputPath || inputPath.startsWith(outputPath) || outputPath.startsWith(inputPath)) {
            return ApkAlignmentInvocationResult(false, "zipalign input and output overlap")
        }
        if (Files.exists(outputPath, LinkOption.NOFOLLOW_LINKS) &&
            (Files.isSymbolicLink(outputPath) || !Files.isRegularFile(outputPath, LinkOption.NOFOLLOW_LINKS))
        ) {
            return ApkAlignmentInvocationResult(false, "zipalign output is not an ordinary file")
        }

        val command = listOf(
            executablePath.toString(), "-f", "4", inputPath.toString(), outputPath.toString()
        )
        var process: Process? = null
        var registration: CancellationToken.Registration? = null
        var stdout: BoundedLogReader? = null
        var stderr: BoundedLogReader? = null
        try {
            process = ProcessBuilder(command).directory(inputPath.parent.toFile()).start()
            val active = process
            stdout = BoundedLogReader(active.inputStream, "zipalign-stdout")
            stderr = BoundedLogReader(active.errorStream, "zipalign-stderr")
            stdout.start()
            stderr.start()
            runCatching { active.outputStream.close() }
            registration = token.onCancel(Runnable { runCatching { active.destroy() } })

            val started = System.nanoTime()
            val timeoutNanos = timeoutNanos(invocation.timeoutMs)
            while (exitValueOrNull(active) == null) {
                if (token.isCancelled) {
                    terminate(active)
                    joinReaders(stdout, stderr)
                    return ApkAlignmentInvocationResult(false, "cancelled\n${formatLogs(stdout, stderr)}")
                }
                if (System.nanoTime() - started >= timeoutNanos) {
                    terminate(active)
                    joinReaders(stdout, stderr)
                    return ApkAlignmentInvocationResult(false, "zipalign timed out after ${invocation.timeoutMs} ms\n${formatLogs(stdout, stderr)}")
                }
                try {
                    Thread.sleep(PROCESS_POLL_MILLIS)
                } catch (interrupted: InterruptedException) {
                    terminate(active)
                    Thread.currentThread().interrupt()
                    joinReaders(stdout, stderr)
                    return ApkAlignmentInvocationResult(false, "zipalign wait was interrupted\n${formatLogs(stdout, stderr)}")
                }
            }
            val exitCode = exitValueOrNull(active) ?: -1
            joinReaders(stdout, stderr)
            val logs = formatLogs(stdout, stderr)
            return ApkAlignmentInvocationResult(exitCode == 0, "exit=$exitCode${if (logs.isBlank()) "" else "\n$logs"}")
        } catch (error: Exception) {
            process?.let { active -> if (exitValueOrNull(active) == null) runCatching { terminate(active) } }
            joinReaders(stdout, stderr)
            return ApkAlignmentInvocationResult(false, "zipalign process failed: ${error.message ?: error.javaClass.simpleName}\n${formatLogs(stdout, stderr)}")
        } finally {
            registration?.close()
        }
    }

    private fun resolveExecutable(): Path? {
        val raw = securePath(executable, "zipalign executable")
        if (!Files.isRegularFile(raw, LinkOption.NOFOLLOW_LINKS)) return null
        return raw
    }

    private fun validateInput(file: File): Path {
        val path = securePath(file, "zipalign input")
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) <= 0L) {
            throw IOException("input is not an ordinary non-empty file")
        }
        return path
    }

    private fun validateOutput(file: File): Path = securePath(file, "zipalign output")

    private fun securePath(file: File, label: String): Path {
        val absolute = file.toPath().toAbsolutePath()
        if (absolute.any { it.toString() == ".." }) throw IOException("$label contains parent traversal")
        val normalized = absolute.normalize()
        rejectSymlinks(normalized, label)
        return normalized
    }

    private fun rejectSymlinks(path: Path, label: String) {
        val root = path.root ?: throw IOException("$label has no filesystem root")
        var current = root
        for (component in root.relativize(path)) {
            current = current.resolve(component)
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                val attrs = Files.readAttributes(current, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                if (attrs.isSymbolicLink) throw IOException("$label contains a symbolic link: $current")
                if (current != path && !attrs.isDirectory) throw IOException("$label parent is not a directory: $current")
            }
        }
    }

    private class BoundedLogReader(private val input: InputStream, name: String) : Thread(name) {
        private val captured = ByteArrayOutputStream(MAX_LOG_BYTES)
        @Volatile private var failed: String? = null
        @Volatile private var truncated = false

        init { isDaemon = true }

        override fun run() {
            try {
                input.use { stream ->
                    val buffer = ByteArray(4096)
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        val remaining = MAX_LOG_BYTES - captured.size()
                        if (remaining > 0) captured.write(buffer, 0, minOf(remaining, count))
                        if (count > remaining) truncated = true
                    }
                }
            } catch (error: Exception) {
                failed = error.message ?: error.javaClass.simpleName
            }
        }

        @Synchronized
        fun text(): String {
            val value = String(captured.toByteArray(), StandardCharsets.UTF_8)
            return buildString {
                append(value)
                if (truncated) append("\n[diagnostics truncated at 64 KiB]")
                failed?.let { append("\n[diagnostic stream ended: ").append(it).append(']') }
            }
        }
    }

    private fun formatLogs(stdout: BoundedLogReader?, stderr: BoundedLogReader?): String = buildString {
        val out = stdout?.text().orEmpty()
        val err = stderr?.text().orEmpty()
        if (out.isNotBlank()) append("stdout:\n").append(out)
        if (err.isNotBlank()) {
            if (isNotEmpty()) append('\n')
            append("stderr:\n").append(err)
        }
    }

    private fun joinReaders(stdout: BoundedLogReader?, stderr: BoundedLogReader?) {
        for (reader in listOfNotNull(stdout, stderr)) {
            try {
                reader.join(READER_JOIN_MILLIS)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
    }

    private fun terminate(process: Process) {
        if (exitValueOrNull(process) != null) return
        runCatching { process.destroy() }
        if (waitForExit(process, GRACEFUL_TERMINATION_MILLIS)) return
        runCatching { process.destroyForcibly() }
        if (!waitForExit(process, FORCEFUL_TERMINATION_MILLIS)) {
            throw IOException("zipalign did not exit after destroyForcibly()")
        }
    }

    private fun waitForExit(process: Process, timeoutMillis: Long): Boolean {
        val started = System.nanoTime()
        val budget = timeoutMillis * 1_000_000L
        while (exitValueOrNull(process) == null) {
            if (System.nanoTime() - started >= budget) return false
            try {
                Thread.sleep(TERMINATION_POLL_MILLIS)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                return exitValueOrNull(process) != null
            }
        }
        return true
    }

    private fun exitValueOrNull(process: Process): Int? = try {
        process.exitValue()
    } catch (_: IllegalThreadStateException) {
        null
    }

    private fun timeoutNanos(timeoutMillis: Long): Long {
        val bounded = minOf(timeoutMillis, Long.MAX_VALUE / 2_000_000L)
        return bounded * 1_000_000L
    }

    private companion object {
        const val MAX_LOG_BYTES = 64 * 1024
        const val PROCESS_POLL_MILLIS = 50L
        const val TERMINATION_POLL_MILLIS = 25L
        const val GRACEFUL_TERMINATION_MILLIS = 250L
        const val FORCEFUL_TERMINATION_MILLIS = 2_000L
        const val READER_JOIN_MILLIS = 1_000L
    }
}

/** Coordinates input contract, provider execution, independent verification and atomic publication. */
class ApkAligner(
    private val provider: ApkAlignmentProvider,
    private val verifier: ApkAlignmentVerifier = ApkAlignmentVerifier()
) {
    fun align(request: ApkAlignmentRequest, token: CancellationToken): ApkAlignmentResult {
        val started = System.nanoTime()
        var output: Path? = null
        var temporary: Path? = null
        var outputMayBeCleaned = false
        try {
            val input = checkedAbsolutePath(request.inputApk, "APK_ALIGNMENT_INPUT_INVALID", "APK input", allowMissing = true)
            output = outputPath(request.outputApk)
            validateNoOverlap(input, output)
            ensureOutputParent(output)
            removeStaleOutput(output)
            outputMayBeCleaned = true
            token.throwIfCancelled()

            validateRequest(request)
            validateInputFile(input, request.maxApkBytes)
            val inputVerification = verifier.verify(
                input.toFile(),
                alignmentBytes = request.alignmentBytes,
                maxApkBytes = request.maxApkBytes,
                maxEntries = request.maxEntries,
                token = token
            )
            token.throwIfCancelled()
            if (inputVerification.outcome != BuildOutcome.SUCCESS) {
                val first = inputVerification.diagnostics.firstOrNull()
                val code = when (inputVerification.outcome) {
                    BuildOutcome.RESOURCE_LIMIT -> "APK_ALIGNMENT_INPUT_LIMIT"
                    BuildOutcome.UNSUPPORTED -> "APK_ALIGNMENT_ZIP_UNSUPPORTED"
                    BuildOutcome.CANCELLED -> "BUILD_CANCELLED"
                    else -> "APK_ALIGNMENT_INPUT_INVALID"
                }
                fail(inputVerification.outcome, code, first?.message ?: "APK input ZIP is invalid", input)
            }
            if (inputVerification.apkSigningBlockPresent || inputVerification.entries.any { isV1SignatureName(it.name) }) {
                fail(BuildOutcome.FAILED, "APK_ALIGNMENT_SIGNED_INPUT", "Signed APK input is not accepted; alignment must precede signing", input)
            }
            validateApkLike(inputVerification.entries, output = false, path = input)

            if (!providerAvailable()) {
                fail(BuildOutcome.UNSUPPORTED, "APK_ALIGNER_UNAVAILABLE", "APK alignment provider is unavailable")
            }

            temporary = Files.createTempFile(output.parent, ".${output.fileName}.ap2-", ".tmp")
            // The provider must create the temporary output itself. This distinguishes a reported
            // success with no output from a valid (but possibly empty) existing placeholder.
            Files.delete(temporary)
            token.throwIfCancelled()
            val providerResult = try {
                provider.align(
                    ApkAlignmentInvocation(input.toFile(), temporary.toFile(), 4, request.timeoutMs),
                    token
                )
            } catch (error: Exception) {
                fail(BuildOutcome.FAILED, "APK_ALIGNMENT_FAILED", "Alignment provider failed: ${error.message ?: error.javaClass.simpleName}")
            }
            token.throwIfCancelled()
            if (!providerResult.success) {
                fail(BuildOutcome.FAILED, "APK_ALIGNMENT_FAILED", providerResult.diagnostics.ifBlank { "Alignment provider reported failure" })
            }
            validateTemporaryOutput(temporary, request.maxApkBytes)

            val outputVerification = verifier.verify(
                temporary.toFile(),
                alignmentBytes = 4,
                maxApkBytes = request.maxApkBytes,
                maxEntries = request.maxEntries,
                token = token
            )
            token.throwIfCancelled()
            if (outputVerification.outcome != BuildOutcome.SUCCESS) {
                val first = outputVerification.diagnostics.firstOrNull()
                val code = if (outputVerification.outcome == BuildOutcome.RESOURCE_LIMIT) {
                    "APK_ALIGNMENT_INPUT_LIMIT"
                } else {
                    "APK_ALIGNMENT_OUTPUT_INVALID"
                }
                fail(
                    if (outputVerification.outcome == BuildOutcome.RESOURCE_LIMIT) BuildOutcome.RESOURCE_LIMIT else BuildOutcome.FAILED,
                    code,
                    first?.message ?: "Alignment provider produced an invalid ZIP",
                    temporary
                )
            }
            if (outputVerification.apkSigningBlockPresent) {
                fail(BuildOutcome.FAILED, "APK_ALIGNMENT_OUTPUT_INVALID", "Alignment provider produced an APK Signing Block", temporary)
            }
            validateApkLike(outputVerification.entries, output = true, path = temporary)
            if (!outputVerification.aligned) {
                fail(BuildOutcome.FAILED, "APK_ALIGNMENT_OUTPUT_INVALID", "Provider output failed the independent 4-byte alignment check", temporary)
            }
            if (inputVerification.entries != outputVerification.entries) {
                fail(BuildOutcome.FAILED, "APK_ALIGNMENT_OUTPUT_INVALID", "Alignment changed APK entry names, methods, sizes, or uncompressed payload bytes", temporary)
            }
            token.throwIfCancelled()
            validateOutputPathStillSafe(output)
            if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
                fail(BuildOutcome.FAILED, "APK_ALIGNMENT_OUTPUT_INVALID", "A file appeared at the aligned APK output during assembly", output)
            }
            Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE)
            temporary = null
            validateOutputPathStillSafe(output)
            token.throwIfCancelled()

            // Publication is a trust boundary too. Re-verify the final path rather than
            // assuming the verified temporary remained untouched after the atomic move.
            val publishedVerification = verifier.verify(
                output.toFile(),
                alignmentBytes = 4,
                maxApkBytes = request.maxApkBytes,
                maxEntries = request.maxEntries,
                token = token
            )
            token.throwIfCancelled()
            if (publishedVerification.outcome != BuildOutcome.SUCCESS ||
                publishedVerification.apkSigningBlockPresent ||
                !publishedVerification.aligned ||
                publishedVerification.entries != inputVerification.entries
            ) {
                val first = publishedVerification.diagnostics.firstOrNull()
                fail(
                    if (publishedVerification.outcome == BuildOutcome.RESOURCE_LIMIT) {
                        BuildOutcome.RESOURCE_LIMIT
                    } else {
                        BuildOutcome.FAILED
                    },
                    if (publishedVerification.outcome == BuildOutcome.RESOURCE_LIMIT) {
                        "APK_ALIGNMENT_INPUT_LIMIT"
                    } else {
                        "APK_ALIGNMENT_OUTPUT_INVALID"
                    },
                    first?.message ?: "Published aligned APK failed final verification",
                    output
                )
            }
            validateApkLike(publishedVerification.entries, output = true, path = output)

            val artifact = BuildArtifact("android-aligned-unsigned-apk", output.toFile(), sha256(output, token))
            return ApkAlignmentResult(
                BuildOutcome.SUCCESS,
                diagnostics = listOf(info("APK_ALIGNMENT_VERIFIED", "Created aligned unsigned APK; no signing was performed")),
                artifacts = listOf(artifact),
                durationMs = elapsedMs(started)
            )
        } catch (cancelled: CancellationToken.CancelledException) {
            cleanup(output, temporary, outputMayBeCleaned)
            return result(started, BuildOutcome.CANCELLED, "BUILD_CANCELLED", "APK alignment was cancelled")
        } catch (failure: ApkAlignmentProblem) {
            cleanup(output, temporary, outputMayBeCleaned)
            return result(started, failure.outcome, failure.code, failure.message ?: failure.code, failure.path)
        } catch (error: Exception) {
            cleanup(output, temporary, outputMayBeCleaned)
            return result(
                started,
                BuildOutcome.FAILED,
                "APK_ALIGNMENT_FAILED",
                "APK alignment failed: ${error.message ?: error.javaClass.simpleName}"
            )
        }
    }

    private fun validateRequest(request: ApkAlignmentRequest) {
        if (request.alignmentBytes != 4) {
            fail(BuildOutcome.FAILED, "APK_ALIGNMENT_INPUT_INVALID", "AP2A supports ordinary 4-byte APK alignment only")
        }
        if (request.maxApkBytes <= 0L || request.maxEntries <= 0 || request.timeoutMs <= 0L) {
            fail(BuildOutcome.FAILED, "APK_ALIGNMENT_INPUT_INVALID", "APK alignment limits and timeout must be positive")
        }
    }

    private fun providerAvailable(): Boolean = try { provider.available } catch (_: Exception) { false }

    private fun validateApkLike(entries: List<ApkAlignmentEntryFingerprint>, output: Boolean, path: Path) {
        val names = entries.map { it.name }.toSet()
        val required = listOf("AndroidManifest.xml", "resources.arsc", "classes.dex")
        val missing = required.filter { it !in names }
        if (missing.isNotEmpty()) {
            fail(
                BuildOutcome.FAILED,
                if (output) "APK_ALIGNMENT_OUTPUT_INVALID" else "APK_ALIGNMENT_INPUT_INVALID",
                "APK is missing required root entries: ${missing.joinToString()}",
                path
            )
        }
        val v1Signature = entries.firstOrNull { isV1SignatureName(it.name) }
        if (v1Signature != null) {
            fail(
                BuildOutcome.FAILED,
                if (output) "APK_ALIGNMENT_OUTPUT_INVALID" else "APK_ALIGNMENT_SIGNED_INPUT",
                "APK contains v1 signature metadata: ${v1Signature.name}",
                path
            )
        }
        val nativeLibrary = entries.firstOrNull { isNativeLibraryName(it.name) }
        if (nativeLibrary != null) {
            fail(
                if (output) BuildOutcome.FAILED else BuildOutcome.UNSUPPORTED,
                if (output) "APK_ALIGNMENT_OUTPUT_INVALID" else "APK_ALIGNMENT_NATIVE_UNSUPPORTED",
                "AP2A does not accept APKs containing native libraries: ${nativeLibrary.name}",
                path
            )
        }
    }

    private fun isV1SignatureName(name: String): Boolean {
        val upper = name.uppercase()
        if (upper == "META-INF/MANIFEST.MF") return true
        if (!upper.startsWith("META-INF/") || upper.substring("META-INF/".length).contains('/')) return false
        return listOf(".SF", ".RSA", ".DSA", ".EC").any { upper.endsWith(it) }
    }

    private fun isNativeLibraryName(name: String): Boolean {
        val lower = name.lowercase()
        return lower.startsWith("lib/") && lower.endsWith(".so")
    }

    private fun validateInputFile(path: Path, maxBytes: Long) {
        inspectComponents(path, allowMissingLeaf = false, allowMissingParents = false, code = "APK_ALIGNMENT_INPUT_INVALID")
        val attrs = readAttributes(path, "APK_ALIGNMENT_INPUT_INVALID")
        if (attrs.isSymbolicLink || !attrs.isRegularFile || attrs.size() <= 0L) {
            fail(BuildOutcome.FAILED, "APK_ALIGNMENT_INPUT_INVALID", "Input APK must be an ordinary non-empty file", path)
        }
        if (attrs.size() > maxBytes) {
            fail(BuildOutcome.RESOURCE_LIMIT, "APK_ALIGNMENT_INPUT_LIMIT", "Input APK exceeds $maxBytes bytes", path)
        }
    }

    private fun validateTemporaryOutput(path: Path, maxBytes: Long) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            fail(BuildOutcome.FAILED, "APK_ALIGNMENT_OUTPUT_MISSING", "Provider reported success without creating an APK output", path)
        }
        inspectComponents(path, allowMissingLeaf = false, allowMissingParents = false, code = "APK_ALIGNMENT_OUTPUT_INVALID")
        val attrs = readAttributes(path, "APK_ALIGNMENT_OUTPUT_MISSING")
        if (attrs.isSymbolicLink || !attrs.isRegularFile || attrs.size() <= 0L) {
            fail(BuildOutcome.FAILED, "APK_ALIGNMENT_OUTPUT_MISSING", "Provider did not create a non-empty ordinary output APK", path)
        }
        if (attrs.size() > maxBytes) {
            fail(BuildOutcome.RESOURCE_LIMIT, "APK_ALIGNMENT_INPUT_LIMIT", "Aligned APK exceeds $maxBytes bytes", path)
        }
    }

    private fun outputPath(file: File): Path {
        val path = checkedAbsolutePath(file, "APK_ALIGNMENT_OUTPUT_INVALID", "APK output", allowMissing = true)
        if (path.root == path || path.fileName == null || path.parent == null) {
            fail(BuildOutcome.FAILED, "APK_ALIGNMENT_OUTPUT_INVALID", "APK output must not be a filesystem root", path)
        }
        inspectComponents(path, allowMissingLeaf = true, allowMissingParents = true, code = "APK_ALIGNMENT_OUTPUT_INVALID")
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            val attrs = readAttributes(path, "APK_ALIGNMENT_OUTPUT_INVALID")
            if (attrs.isSymbolicLink || !attrs.isRegularFile) {
                fail(BuildOutcome.FAILED, "APK_ALIGNMENT_OUTPUT_INVALID", "APK output must be an ordinary file", path)
            }
        }
        return path
    }

    private fun checkedAbsolutePath(file: File, code: String, label: String, allowMissing: Boolean): Path {
        val absolute = try { file.toPath().toAbsolutePath() } catch (error: Exception) {
            fail(BuildOutcome.FAILED, code, "$label path is invalid", file.toPath())
        }
        if (absolute.any { it.toString() == ".." }) {
            fail(BuildOutcome.FAILED, code, "$label path must not contain parent traversal", absolute)
        }
        val normalized = absolute.normalize()
        if (normalized.root == null) fail(BuildOutcome.FAILED, code, "$label has no filesystem root", normalized)
        inspectComponents(normalized, allowMissingLeaf = allowMissing, allowMissingParents = allowMissing, code = code)
        return normalized
    }

    private fun inspectComponents(path: Path, allowMissingLeaf: Boolean, allowMissingParents: Boolean, code: String) {
        val root = path.root ?: fail(BuildOutcome.FAILED, code, "Path has no filesystem root", path)
        var current = root
        for (component in root.relativize(path)) {
            current = current.resolve(component)
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                val isLeaf = current == path
                if ((isLeaf && allowMissingLeaf) || (!isLeaf && allowMissingParents)) continue
                fail(BuildOutcome.FAILED, code, "Path component does not exist: $current", current)
            }
            val attrs = readAttributes(current, code)
            if (attrs.isSymbolicLink) fail(BuildOutcome.FAILED, code, "Path contains a symbolic link: $current", current)
            if (current != path && !attrs.isDirectory) fail(BuildOutcome.FAILED, code, "Path parent is not a directory: $current", current)
        }
    }

    private fun ensureOutputParent(path: Path) {
        val parent = path.parent ?: fail(BuildOutcome.FAILED, "APK_ALIGNMENT_OUTPUT_INVALID", "APK output has no parent", path)
        val root = parent.root ?: fail(BuildOutcome.FAILED, "APK_ALIGNMENT_OUTPUT_INVALID", "APK output parent has no filesystem root", parent)
        var current = root
        try {
            for (component in root.relativize(parent)) {
                current = current.resolve(component)
                if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                    val attrs = readAttributes(current, "APK_ALIGNMENT_OUTPUT_INVALID")
                    if (attrs.isSymbolicLink || !attrs.isDirectory) {
                        fail(BuildOutcome.FAILED, "APK_ALIGNMENT_OUTPUT_INVALID", "APK output parent is unsafe: $current", current)
                    }
                } else {
                    Files.createDirectory(current)
                }
            }
        } catch (failure: ApkAlignmentProblem) {
            throw failure
        } catch (error: Exception) {
            fail(BuildOutcome.FAILED, "APK_ALIGNMENT_OUTPUT_INVALID", "Could not create APK output parent: ${error.message}", parent)
        }
        validateOutputPathStillSafe(path)
    }

    private fun validateNoOverlap(input: Path, output: Path) {
        if (input == output || input.startsWith(output) || output.startsWith(input)) {
            fail(BuildOutcome.FAILED, "APK_ALIGNMENT_OUTPUT_INVALID", "Aligned APK output overlaps its input", output)
        }
    }

    private fun removeStaleOutput(path: Path) {
        validateOutputPathStillSafe(path)
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) Files.delete(path)
    }

    private fun validateOutputPathStillSafe(path: Path) {
        inspectComponents(path.parent ?: fail(BuildOutcome.FAILED, "APK_ALIGNMENT_OUTPUT_INVALID", "APK output has no parent", path), false, false, "APK_ALIGNMENT_OUTPUT_INVALID")
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            val attrs = readAttributes(path, "APK_ALIGNMENT_OUTPUT_INVALID")
            if (attrs.isSymbolicLink || !attrs.isRegularFile) {
                fail(BuildOutcome.FAILED, "APK_ALIGNMENT_OUTPUT_INVALID", "APK output changed to a symlink or non-file", path)
            }
        }
    }

    private fun readAttributes(path: Path, code: String): BasicFileAttributes = try {
        Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    } catch (error: Exception) {
        fail(BuildOutcome.FAILED, code, "Could not inspect path $path: ${error.message ?: error.javaClass.simpleName}", path)
    }

    private fun cleanup(output: Path?, temporary: Path?, cleanOutput: Boolean) {
        if (temporary != null) runCatching { Files.deleteIfExists(temporary) }
        if (cleanOutput && output != null) runCatching {
            if (Files.exists(output, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(output) && Files.isRegularFile(output, LinkOption.NOFOLLOW_LINKS)) {
                Files.deleteIfExists(output)
            }
        }
    }

    private fun sha256(path: Path, token: CancellationToken): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                token.throwIfCancelled()
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun result(
        started: Long,
        outcome: BuildOutcome,
        code: String,
        message: String,
        path: Path? = null
    ) = ApkAlignmentResult(
        outcome,
        diagnostics = listOf(BuildDiagnostic(
            BuildDiagnosticSeverity.ERROR, code, message, BuildDiagnosticSource.PACKAGING, path?.toString()
        )),
        durationMs = elapsedMs(started)
    )

    private fun info(code: String, message: String) = BuildDiagnostic(
        BuildDiagnosticSeverity.INFO, code, message, BuildDiagnosticSource.PACKAGING
    )

    private fun elapsedMs(started: Long): Long = ((System.nanoTime() - started).coerceAtLeast(0L) / 1_000_000L)

    private fun fail(outcome: BuildOutcome, code: String, message: String, path: Path? = null): Nothing =
        throw ApkAlignmentProblem(outcome, code, message, path)

    private class ApkAlignmentProblem(
        val outcome: BuildOutcome,
        val code: String,
        override val message: String,
        val path: Path? = null
    ) : Exception(message)
}
