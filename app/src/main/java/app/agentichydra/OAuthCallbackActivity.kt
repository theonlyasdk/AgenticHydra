package app.agentichydra

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import app.agentichydra.mcp.McpAuth
import app.agentichydra.mcp.OAuthCallback

class OAuthCallbackActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data
        val code = uri?.getQueryParameter("code")
        val state = uri?.getQueryParameter("state")
        val error = uri?.getQueryParameter("error")
        McpAuth.deliverCallback(OAuthCallback(code, state, error))
        finish()
    }

    companion object {
        fun intent(context: android.content.Context): Intent =
            Intent(context, OAuthCallbackActivity::class.java)
    }
}