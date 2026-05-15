package com.vocalmonitor.audio

/**
 * Generic undo/redo history for an effect's parameter snapshot.
 *
 * Each [push] saves the new state and discards any redo branch. [undo] /
 * [redo] move the cursor; [canUndo] / [canRedo] expose whether either is
 * available. Bounded by [maxSize] so a long session can't bloat memory.
 *
 * Thread-safety: caller is responsible for synchronizing access. In our
 * setup the ViewModel performs all mutations from the main thread.
 */
class EffectHistory<S>(initial: S, val maxSize: Int = 64) {

    private val states = mutableListOf(initial)
    private var index = 0

    fun current(): S = states[index]

    /** Push a new state; collapses redo branch and the previous state if equal. */
    fun push(state: S) {
        if (states[index] == state) return
        if (index < states.lastIndex) {
            for (i in states.lastIndex downTo index + 1) states.removeAt(i)
        }
        states.add(state)
        index++
        if (states.size > maxSize) {
            states.removeAt(0)
            index--
        }
    }

    fun undo(): S? {
        if (index <= 0) return null
        index--
        return states[index]
    }

    fun redo(): S? {
        if (index >= states.lastIndex) return null
        index++
        return states[index]
    }

    /** Replace history with a single fresh starting point. */
    fun reset(state: S) {
        states.clear()
        states.add(state)
        index = 0
    }

    val canUndo: Boolean get() = index > 0
    val canRedo: Boolean get() = index < states.lastIndex
    val depth: Int get() = states.size
    val cursor: Int get() = index
}
