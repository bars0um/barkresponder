package com.codeoptimus.barkresponder

import android.content.res.AssetManager
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer

/**
 * Google's YAMNet sound classifier (Apache 2.0), run on the phone's CPU with
 * LiteRT. It takes 15,600 floats (0.975 s at 16 kHz, -1 to 1) and returns
 * 521 class scores. Not thread-safe: call it from one thread, and [close] it
 * on that thread too.
 */
class YamnetClassifier(assets: AssetManager) : AutoCloseable {
    // We create the Environment ourselves: when CompiledModel.create makes its
    // own and then fails, litert-api 2.3.0 never closes it. Each step that
    // fails closes what the steps before it opened.
    private val env: Environment = Environment.create()
    private val model: CompiledModel
    private val inputs: List<TensorBuffer>
    private val outputs: List<TensorBuffer>

    init {
        val opened = mutableListOf<AutoCloseable>(env)
        try {
            model = CompiledModel.create(assets, MODEL_ASSET, CompiledModel.Options(Accelerator.CPU), env)
            opened.add(0, model)
            inputs = model.createInputBuffers()
            opened.addAll(0, inputs)
            outputs = model.createOutputBuffers()
        } catch (t: Throwable) {
            opened.forEach { runCatching { it.close() } }
            throw t
        }
    }

    fun classify(waveform: FloatArray): FloatArray {
        require(waveform.size == Resampler.MODEL_SAMPLES) { "expected ${Resampler.MODEL_SAMPLES} samples, got ${waveform.size}" }
        inputs[0].writeFloat(waveform)
        model.run(inputs, outputs)
        return outputs[0].readFloat()
    }

    override fun close() {
        inputs.forEach { it.close() }
        outputs.forEach { it.close() }
        model.close()
        env.close()
    }

    companion object {
        const val MODEL_ASSET = "yamnet.tflite"
        /** "Bark" in yamnet_class_map.csv. */
        const val BARK = 70
    }
}
