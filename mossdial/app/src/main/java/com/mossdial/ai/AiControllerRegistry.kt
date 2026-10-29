package com.mossdial.ai

/**
 * The one [AiController] the process shares.
 *
 * The controller is built once by [AiController.shared] and outlives the AI tab: the local AI API
 * is a separate listener that answers with whatever that controller has loaded, and rebuilding the
 * controller per tab visit would mean reloading the native runtimes, re-reading the model list and
 * unloading the model every time the tab closed. That is the whole point of the registry: a second
 * controller would mean a second copy of the weights in memory and a second llama.cpp session, and
 * the API would answer with a different model from the one on screen.
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
