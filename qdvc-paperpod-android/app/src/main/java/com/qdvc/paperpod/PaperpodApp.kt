package com.qdvc.paperpod

import android.app.Application
import android.content.Context
import com.qdvc.paperpod.data.PayloadRepository
import com.qdvc.paperpod.text.FontRegistry

class PaperpodApp : Application() {

    private lateinit var repo: PayloadRepository

    override fun onCreate() {
        super.onCreate()
        instance = this
        repo = PayloadRepository(this)
        repo.load()
        FontRegistry.load(repo.root)
    }

    companion object {
        private lateinit var instance: PaperpodApp

        fun repository(context: Context): PayloadRepository {
            val app = (context.applicationContext as? PaperpodApp) ?: instance
            return app.repo
        }
    }
}
