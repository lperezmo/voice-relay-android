package com.lperezmo.voicerelay

/**
 * At most [max] calls in any [windowMs] milliseconds. In memory only, so it resets
 * when the app process restarts.
 *
 * Used by [FixGrammarActivity]: the "Fix grammar" selection menu entry is an exported
 * activity, so any installed app can send it text, and each request spends the
 * user's OpenAI key. A person fixing selections never gets near the limit.
 */
class RateLimiter(private val max: Int, private val windowMs: Long) {
    private val calls = ArrayDeque<Long>()

    @Synchronized
    fun tryAcquire(now: Long): Boolean {
        while (calls.isNotEmpty() && now - calls.first() >= windowMs) calls.removeFirst()
        if (calls.size >= max) return false
        calls.addLast(now)
        return true
    }
}
