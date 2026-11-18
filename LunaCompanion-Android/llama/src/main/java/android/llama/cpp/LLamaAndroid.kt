package android.llama.cpp

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.concurrent.thread

class LLamaAndroid {
    private val tag: String? = this::class.simpleName

    private val threadLocalState: ThreadLocal<State> = ThreadLocal.withInitial { State.Idle }

    private val runLoop: CoroutineDispatcher = Executors.newSingleThreadExecutor { runnable ->
        thread(start = false, name = "Llm-RunLoop") {
            System.loadLibrary("llama-android")
            log_to_android()
            backend_init(false)
            Log.d(tag, "llama.cpp backend initialized: ${system_info()}")
            runnable.run()
        }.apply {
            uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, exception ->
                Log.e(tag, "Unhandled exception on llama RunLoop", exception)
            }
        }
    }.asCoroutineDispatcher()

    private val nlen: Int = 512

    private external fun log_to_android()
    private external fun load_model(filename: String): Long
    private external fun free_model(model: Long)
    private external fun new_context(model: Long): Long
    private external fun free_context(context: Long)
    private external fun backend_init(numa: Boolean)
    private external fun backend_free()
    private external fun new_batch(nTokens: Int, embd: Int, nSeqMax: Int): Long
    private external fun free_batch(batch: Long)
    private external fun new_sampler(): Long
    private external fun free_sampler(sampler: Long)
    private external fun system_info(): String

    private external fun completion_init(
        context: Long,
        batch: Long,
        text: String,
        nLen: Int
    ): Int

    private external fun completion_loop(
        context: Long,
        batch: Long,
        sampler: Long,
        nLen: Int,
        ncur: IntVar
    ): String?

    private external fun kv_cache_clear(context: Long)

    suspend fun initializeBackend() {
        withContext(runLoop) {
            Log.i(tag, "Embedded llama.cpp engine ready")
        }
    }

    suspend fun load(pathToModel: String) {
        withContext(runLoop) {
            when (threadLocalState.get()) {
                is State.Loaded -> unloadInternal()
                is State.Idle -> Unit
                null -> Unit
            }

            val model = load_model(pathToModel)
            if (model == 0L) throw IllegalStateException("load_model() failed for $pathToModel")

            val context = new_context(model)
            if (context == 0L) {
                free_model(model)
                throw IllegalStateException("new_context() failed")
            }

            val batch = new_batch(512, 0, 1)
            if (batch == 0L) {
                free_context(context)
                free_model(model)
                throw IllegalStateException("new_batch() failed")
            }

            val sampler = new_sampler()
            if (sampler == 0L) {
                free_batch(batch)
                free_context(context)
                free_model(model)
                throw IllegalStateException("new_sampler() failed")
            }

            Log.i(tag, "Loaded model tensors: $pathToModel")
            threadLocalState.set(State.Loaded(model, context, batch, sampler, pathToModel))
        }
    }

    fun send(message: String, maxTokens: Int = nlen): Flow<String> = flow {
        when (val state = threadLocalState.get()) {
            is State.Loaded -> {
                kv_cache_clear(state.context)
                val ncur = IntVar(completion_init(state.context, state.batch, message, maxTokens))
                while (ncur.value <= maxTokens) {
                    val str = completion_loop(state.context, state.batch, state.sampler, maxTokens, ncur)
                    if (str == null) {
                        break
                    } else if (str.isNotEmpty()) {
                        emit(str)
                    }
                }
                kv_cache_clear(state.context)
            }
            else -> throw IllegalStateException("No GGUF model loaded")
        }
    }.flowOn(runLoop)

    suspend fun unload() {
        withContext(runLoop) {
            unloadInternal()
        }
    }

    suspend fun shutdownBackend() {
        withContext(runLoop) {
            unloadInternal()
            backend_free()
            Log.i(tag, "llama.cpp backend shut down cleanly; all native RAM freed.")
        }
    }

    private fun unloadInternal() {
        when (val state = threadLocalState.get()) {
            is State.Loaded -> {
                free_context(state.context)
                free_model(state.model)
                free_batch(state.batch)
                free_sampler(state.sampler)
                threadLocalState.set(State.Idle)
                Log.i(tag, "Unloaded model tensors: ${state.modelPath}")
            }
            else -> Unit
        }
    }

    fun isModelLoaded(): Boolean = threadLocalState.get() is State.Loaded

    private class IntVar(value: Int) {
        @Volatile
        var value: Int = value
            private set

        fun inc() {
            synchronized(this) {
                value += 1
            }
        }
    }

    private sealed interface State {
        data object Idle : State
        data class Loaded(
            val model: Long,
            val context: Long,
            val batch: Long,
            val sampler: Long,
            val modelPath: String
        ) : State
    }

    companion object {
        private val _instance: LLamaAndroid = LLamaAndroid()
        fun instance(): LLamaAndroid = _instance
    }
}
