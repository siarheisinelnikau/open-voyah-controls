package org.openvoyah.controls.platform.updates

import android.content.Context
import org.openvoyah.controls.core.updates.ApkUpdatePolicy
import org.openvoyah.controls.core.updates.ReleasePolicy
import org.openvoyah.controls.core.updates.ReleaseVersion
import org.openvoyah.controls.data.updates.UpdateException
import org.openvoyah.controls.data.updates.UpdateProblem
import java.io.StringReader
import java.io.StringWriter
import java.util.Properties

internal enum class UpdateReceiptStep { PREPARING, PREPARED, STAGING, ATTEMPTED, INSTALLED, CANCELLED, FAILED }
internal enum class UpdateSessionOutcome { NONE, SUCCESS, ABORTED, FAILURE }

internal data class UpdateInstallTarget(
    val packageName: String,
    val version: String,
    val versionCode: Long,
    val bytes: Long,
    val sha256: String,
    val signers: Set<String>,
    val sourcePath: String,
    val sourceIdentity: String,
    val originalPath: String,
    val originalVersion: String,
    val originalVersionCode: Long,
    val originalUpdatedAt: Long,
    val originalSigners: Set<String>,
)

/** Stored before pausing or committing. A callback is evidence to inspect, never an installation proof. */
internal data class UpdateInstallReceipt(
    val token: String,
    val target: UpdateInstallTarget?,
    val step: UpdateReceiptStep = UpdateReceiptStep.PREPARING,
    val sessionId: Int? = null,
    val attempted: Boolean = false,
    val approvalReceived: Boolean = false,
    val outcome: UpdateSessionOutcome = UpdateSessionOutcome.NONE,
    val problem: UpdateProblem? = null,
)

internal interface UpdateInstallJournal {
    fun read(): UpdateInstallReceipt?
    fun write(receipt: UpdateInstallReceipt)
    fun clear()
}

/** SharedPreferences commit uses Android's synchronous atomic-file persistence. This is outside settings archives. */
internal class SharedUpdateInstallJournal(context: Context) : UpdateInstallJournal {
    private val preferences = context.applicationContext.getSharedPreferences("self_update_install", Context.MODE_PRIVATE)

    override fun read(): UpdateInstallReceipt? = preferences.getString("receipt", null)?.let(UpdateReceiptCodec::decode)

    override fun write(receipt: UpdateInstallReceipt) {
        if (!preferences.edit().putString("receipt", UpdateReceiptCodec.encode(receipt)).commit()) {
            throw UpdateException(UpdateProblem.STORAGE)
        }
    }

    override fun clear() {
        if (!preferences.edit().remove("receipt").commit()) throw UpdateException(UpdateProblem.STORAGE)
    }
}

internal object UpdateReceiptCodec {
    private const val MAX_CHARACTERS = 32_768
    private val tokenPattern = Regex("[0-9a-f]{32}")

    fun encode(receipt: UpdateInstallReceipt): String {
        validate(receipt)
        val values = Properties().apply {
            setProperty("schema", "1")
            setProperty("token", receipt.token)
            setProperty("step", receipt.step.name)
            setProperty("session", receipt.sessionId?.toString().orEmpty())
            setProperty("attempted", receipt.attempted.toString())
            setProperty("approval", receipt.approvalReceived.toString())
            setProperty("outcome", receipt.outcome.name)
            setProperty("problem", receipt.problem?.name.orEmpty())
            setProperty("target", (receipt.target != null).toString())
            receipt.target?.let { target ->
                setProperty("package", target.packageName)
                setProperty("version", target.version)
                setProperty("versionCode", target.versionCode.toString())
                setProperty("bytes", target.bytes.toString())
                setProperty("sha256", target.sha256)
                setProperty("signers", target.signers.sorted().joinToString(","))
                setProperty("source", target.sourcePath)
                setProperty("sourceIdentity", target.sourceIdentity)
                setProperty("originalPath", target.originalPath)
                setProperty("originalVersion", target.originalVersion)
                setProperty("originalVersionCode", target.originalVersionCode.toString())
                setProperty("originalUpdatedAt", target.originalUpdatedAt.toString())
                setProperty("originalSigners", target.originalSigners.sorted().joinToString(","))
            }
        }
        return StringWriter().also { values.store(it, "Update installation receipt") }.toString()
            .also { if (it.length > MAX_CHARACTERS) throw UpdateException(UpdateProblem.STORAGE) }
    }

    fun decode(raw: String): UpdateInstallReceipt {
        try {
            require(raw.length in 1..MAX_CHARACTERS)
            val values = Properties().apply { load(StringReader(raw)) }
            fun text(key: String) = requireNotNull(values.getProperty(key))
            fun flag(key: String): Boolean = when (text(key)) {
                "true" -> true
                "false" -> false
                else -> error("Invalid flag")
            }
            require(text("schema") == "1")
            val target = if (!flag("target")) null else UpdateInstallTarget(
                text("package"), text("version"), text("versionCode").toLong(), text("bytes").toLong(),
                text("sha256"), text("signers").split(',').toSet(), text("source"), text("sourceIdentity"),
                text("originalPath"), text("originalVersion"), text("originalVersionCode").toLong(),
                text("originalUpdatedAt").toLong(), text("originalSigners").split(',').toSet(),
            )
            return UpdateInstallReceipt(text("token"), target, UpdateReceiptStep.valueOf(text("step")),
                text("session").takeIf { it.isNotEmpty() }?.toInt(), flag("attempted"), flag("approval"),
                UpdateSessionOutcome.valueOf(text("outcome")), text("problem").takeIf { it.isNotEmpty() }
                    ?.let(UpdateProblem::valueOf)).also(::validate)
        } catch (failure: UpdateException) {
            throw failure
        } catch (_: Exception) {
            throw UpdateException(UpdateProblem.STORAGE)
        }
    }

    private fun validate(receipt: UpdateInstallReceipt) {
        require(tokenPattern.matches(receipt.token))
        require(receipt.sessionId == null || receipt.sessionId >= 0)
        require(!receipt.attempted || receipt.sessionId != null)
        require(!receipt.approvalReceived || receipt.attempted)
        require(receipt.outcome == UpdateSessionOutcome.NONE || receipt.attempted)
        require(receipt.step !in setOf(UpdateReceiptStep.ATTEMPTED, UpdateReceiptStep.INSTALLED) || receipt.attempted)
        require(receipt.step !in setOf(UpdateReceiptStep.PREPARING, UpdateReceiptStep.PREPARED, UpdateReceiptStep.STAGING) || !receipt.attempted)
        val target = receipt.target
        if (target == null) {
            require(receipt.step == UpdateReceiptStep.CANCELLED && receipt.sessionId == null && !receipt.attempted)
            return
        }
        require(target.packageName == ApkUpdatePolicy.PACKAGE && ReleaseVersion.parse(target.version) != null)
        require(ReleaseVersion.parse(target.originalVersion) != null && target.originalVersionCode > 0)
        require(target.versionCode > target.originalVersionCode && target.bytes in 1..ReleasePolicy.MAX_APK_BYTES)
        require(ReleasePolicy.isSha256(target.sha256) && target.signers.size == 1 && target.signers.all(ReleasePolicy::isSha256))
        require(target.originalSigners == target.signers && target.originalUpdatedAt >= 0)
        require(target.sourcePath.length in 1..4096 && target.originalPath.length in 1..4096)
        require(target.sourceIdentity.length in 1..256)
    }
}
