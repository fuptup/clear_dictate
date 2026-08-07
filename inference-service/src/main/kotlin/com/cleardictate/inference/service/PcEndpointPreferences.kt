package com.cleardictate.inference.service

import android.content.Context

/**
 * Persists only the manually paired PC address and bearer token in this application's private storage.
 */
fun interface PcEndpointProvider
{
    fun load(): PcDictationEndpoint?
}

/**
 * Android private-storage implementation shared by the activity, keyboard, and inference process.
 */
class PcEndpointPreferences(context: Context) : PcEndpointProvider
{
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCE_FILE, Context.MODE_PRIVATE)

    override fun load(): PcDictationEndpoint?
    {
        val baseUrl = preferences.getString(BASE_URL_KEY, null)?.takeIf(String::isNotBlank) ?: return null
        val token = preferences.getString(AUTHORIZATION_TOKEN_KEY, null)?.takeIf(String::isNotBlank) ?: return null
        return runCatching { PcDictationEndpoint(baseUrl, token) }.getOrNull()
    }

    /**
     * Validates before committing both values atomically so the inference process never observes a partial pairing.
     */
    fun save(baseUrl: String, authorizationToken: String): PcDictationEndpoint
    {
        val endpoint = PcDictationEndpoint(baseUrl.trim(), authorizationToken.trim())
        check(
            preferences.edit()
                .putString(BASE_URL_KEY, endpoint.baseUrl)
                .putString(AUTHORIZATION_TOKEN_KEY, endpoint.authorizationToken)
                .commit()
        ) { "Android could not persist the paired PC endpoint." }
        return endpoint
    }

    private companion object
    {
        const val PREFERENCE_FILE = "pc_dictation_endpoint"
        const val BASE_URL_KEY = "base_url"
        const val AUTHORIZATION_TOKEN_KEY = "authorization_token"
    }
}
