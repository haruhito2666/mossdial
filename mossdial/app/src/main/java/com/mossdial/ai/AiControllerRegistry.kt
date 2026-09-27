package com.mossdial.ai

/**
 * The one [AiController] the process shares.
 *
 * The AI tab owns the controller: it loads a model into it, and it releases it when the tab goes
 * away. The local AI API is a separate listener that has to answer with whatever that controller
 * has loaded, so it looks the controller up here instead of building a second one. That is the
 * whole point of the registry: a second controller would mean a second copy of the weights in
 * memory and a second llama.cpp session, and the API would answer with a different model from the
 * one on screen.
 *
 * Publication is a single volatile reference rather than a list, because there is only ever one
 * owner. [detach] takes the controller to compare against, so a tab that is disposed after another
 * one has already taken over cannot clear the live entry.
 */
object AiControllerRegistry {
    @Volatile
    private var controller: AiController? = null

    /** Makes [instance] the controller the API answers with. */
    fun attach(instance: AiController) {
        controller = instance
    }

    /** Clears the entry, but only when it is still [instance]. */
    fun detach(instance: AiController) {
        if (controller === instance) controller = null
    }

    /** The registered controller, or null when the AI tab is not open in this process. */
    fun current(): AiController? = controller

    fun isAttached(instance: AiController): Boolean = controller === instance
}
