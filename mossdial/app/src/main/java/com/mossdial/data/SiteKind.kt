package com.mossdial.data

/**
 * The kind of site the hosted folder holds.
 *
 * The only thing this changes today is whether an extensionless route may fall back to the root
 * `index.html`. A plain static site should say so loudly with a 404; a single-page app needs the
 * fallback, because its routes exist only in JavaScript. Static exports from Next.js, Astro and
 * TanStack Start already emit real files for their routes, so they do not need the fallback either.
 */
enum class SiteKind(
    val id: String,
    val label: String,
    val summary: String,
    val spaFallback: Boolean
) {
    CUSTOM(
        id = "custom",
        label = "Custom",
        summary = "Plain HTML, CSS, JS and assets. Missing routes answer 404.",
        spaFallback = false
    ),
    SPA(
        id = "spa",
        label = "SPA",
        summary = "React, Vue, Svelte, Vite or TanStack SPA. Routes fall back to index.html.",
        spaFallback = true
    ),
    NEXT_EXPORT(
        id = "next-export",
        label = "Next.js export",
        summary = "Host the out/ folder from next.config output: 'export'.",
        spaFallback = false
    ),
    STATIC_EXPORT(
        id = "static-export",
        label = "Static export",
        summary = "Astro, TanStack Start export or any pre-rendered output. Missing routes 404.",
        spaFallback = false
    );

    companion object {
        /** The stored id, or the safe plain-static default for anything else. */
        fun fromId(value: String?): SiteKind =
            entries.firstOrNull { it.id == value?.trim() } ?: CUSTOM
    }
}
