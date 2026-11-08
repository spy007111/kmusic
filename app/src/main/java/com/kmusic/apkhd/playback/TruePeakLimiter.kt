package com.kmusic.apkhd.playback

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/** Finite windowed-sinc estimate, not a guarantee of continuous or post-codec true peak. */
internal object TruePeakInterpolation {
    const val HALF = 12
    const val TAPS = HALF * 2
    val phases: Array<DoubleArray> = kernels(4)
    val feedbackPhases: Array<DoubleArray> = kernels(8)

    fun kernels(factor: Int): Array<DoubleArray> = Array(factor - 1) { phase ->
        val fraction = (phase + 1.0) / factor
        val taps = DoubleArray(TAPS) { k ->
            val d = k - HALF + 1.0 - fraction
            val sinc = if (d == 0.0) 1.0 else sin(PI * d) / (PI * d)
            if (abs(d) >= HALF) 0.0 else sinc * (0.5 + 0.5 * cos(PI * d / HALF))
        }
        val sum = taps.sum()
        for (k in taps.indices) taps[k] /= sum
        taps
    }
}

/**
 * Optional conservative linked-channel protection. Four milliseconds of fixed stream delay.
 * Input 4x estimates drive linked lookahead gain. A bounded feedback pass checks the
 * reconstructed gain-modulated window, preserving cancellation in normal operation. An absolute
 * contribution bound is reserved for unresolved feedback. Finite-kernel estimation is experimental.
 * One frame in/out scratch array is owned by the caller. No allocations in push/drain.
 */
class TruePeakLimiter(val sampleRate: Int, val channels: Int) {
    val latencyFrames = maxOf((sampleRate * 0.004).toInt(), TruePeakInterpolation.TAPS)
    private val capacity = latencyFrames + TruePeakInterpolation.TAPS + 2
    private val signal = FloatArray(capacity * channels)
    private val window = DoubleArray(TruePeakInterpolation.TAPS * channels)
    private val predictedWindow = DoubleArray(TruePeakInterpolation.TAPS * channels)
    private val caps = DoubleArray(capacity) { 1.0 }
    private val protected = BooleanArray(capacity)
    private val release = exp(-1.0 / (sampleRate * 0.075))
    private var enabled = true
    private var ceiling = ceilingLinear(-1f)
    private var appended = 0L
    private var accepted = 0L
    private var emitted = 0L
    private var finishing = false
    private var envelope = 1.0
    var lastEstimatedPeak: Double = 0.0
        private set
    var maximumEstimatedPeak: Double = 0.0
        private set
    val gainReductionDb: Float get() = (-20.0 * kotlin.math.log10(envelope.coerceAtLeast(1e-12))).toFloat()
    val pendingFrames: Int get() = (accepted - emitted).toInt()

    init { require(sampleRate > 0 && channels > 0) }

    fun configure(enabled: Boolean, ceilingDb: Float) {
        this.enabled = enabled
        ceiling = ceilingLinear(ceilingDb)
    }

    fun push(input: FloatArray, output: FloatArray): Boolean {
        check(!finishing) { "Flush/reset before input after EOS" }
        require(input.size >= channels && output.size >= channels)
        accepted++
        return append(input, output, false)
    }

    /** Returns exactly the remaining real frames, never the zero padding used by the detector. */
    fun drain(output: FloatArray): Boolean {
        finishing = true
        if (emitted >= accepted) return false
        while (!append(null, output, true)) { }
        return true
    }

    fun reset() {
        signal.fill(0f); caps.fill(1.0); protected.fill(false)
        appended = 0; accepted = 0; emitted = 0; finishing = false
        envelope = 1.0; lastEstimatedPeak = 0.0; maximumEstimatedPeak = 0.0
    }

    private fun append(input: FloatArray?, output: FloatArray, padding: Boolean): Boolean {
        val time = appended++
        val slot = (time % capacity).toInt()
        caps[slot] = 1.0
        protected[slot] = !padding && enabled
        var samplePeak = 0.0
        for (ch in 0 until channels) {
            val raw = input?.get(ch) ?: 0f
            val x = if (raw.isFinite()) raw else 0f
            signal[slot * channels + ch] = x
            samplePeak = maxOf(samplePeak, abs(x.toDouble()))
        }
        if (protected[slot] && samplePeak > ceiling) caps[slot] = ceiling / samplePeak
        var estimate = samplePeak
        for (k in 0 until TruePeakInterpolation.TAPS) {
            val index = time - TruePeakInterpolation.TAPS + 1 + k
            for (ch in 0 until channels) window[k * channels + ch] =
                if (index >= 0) signal[(index % capacity).toInt() * channels + ch].toDouble() else 0.0
        }
        for (ch in 0 until channels) {
            for (kernel in TruePeakInterpolation.feedbackPhases) {
                var reconstructed = 0.0
                for (k in kernel.indices) reconstructed += kernel[k] * window[k * channels + ch]
                estimate = maxOf(estimate, abs(reconstructed))
            }
        }
        lastEstimatedPeak = estimate
        maximumEstimatedPeak = maxOf(maximumEstimatedPeak, estimate)
        if (estimate > ceiling * 0.99) {
            val cap = (ceiling * 0.99 / estimate).coerceIn(0.0, 1.0)
            for (k in 0 until TruePeakInterpolation.TAPS) {
                val index = time - k
                if (index >= emitted && index >= 0) {
                    val i = (index % capacity).toInt()
                    if (protected[i]) caps[i] = minOf(caps[i], cap)
                }
            }
        }
        val outputTime = time - latencyFrames
        if (outputTime < emitted || outputTime < 0 || emitted >= accepted) return false
        check(outputTime == emitted)
        val outSlot = (outputTime % capacity).toInt()
        if (protected[outSlot]) correctOutputWindow(outputTime)
        val target = if (protected[outSlot]) caps[outSlot] else 1.0
        envelope = if (target < envelope) target else minOf(target, 1.0 - (1.0 - envelope) * release)
        for (ch in 0 until channels) output[ch] = (signal[outSlot * channels + ch] * envelope).toFloat()
        emitted++
        return true
    }

    private fun correctOutputWindow(first: Long) {
        // All future samples influencing the first output sample are present before emission.
        for (iteration in 0 until 4) {
            var peak = 0.0
            var bound = 0.0
            var predicted = envelope
            for (k in 0 until TruePeakInterpolation.TAPS) {
                val slot = ((first + k) % capacity).toInt()
                val target = if (protected[slot]) caps[slot] else 1.0
                predicted = if (target < predicted) target else minOf(target, 1.0 - (1.0 - predicted) * release)
                for (ch in 0 until channels) predictedWindow[k * channels + ch] = signal[slot * channels + ch] * predicted
            }
            for (ch in 0 until channels) for (kernel in TruePeakInterpolation.feedbackPhases) {
                var y = 0.0
                var absolute = 0.0
                for (k in kernel.indices) {
                    val term = predictedWindow[k * channels + ch] * kernel[k]
                    y += term
                    absolute += abs(term)
                }
                peak = maxOf(peak, abs(y))
                bound = maxOf(bound, absolute)
            }
            if (peak <= ceiling * 0.995) return
            val ratio = if (iteration < 3) ceiling * 0.99 / peak else ceiling * 0.99 / bound
            for (k in 0 until TruePeakInterpolation.TAPS) {
                val slot = ((first + k) % capacity).toInt()
                if (protected[slot]) caps[slot] *= ratio.coerceIn(0.0, 1.0)
            }
        }
    }

    companion object {
        fun sanitizeCeiling(db: Float): Float = if (db.isFinite()) db.coerceIn(-12f, -0.5f) else -1f
        private fun ceilingLinear(db: Float): Double = 10.0.pow(sanitizeCeiling(db) / 20.0)
    }
}
