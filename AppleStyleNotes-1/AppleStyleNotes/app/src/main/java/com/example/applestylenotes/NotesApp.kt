package com.example.applestylenotes

import android.app.Application

class NotesApp : Application() {
    lateinit var store: NotesStore
        private set

    override fun onCreate() {
        super.onCreate()
        store = NotesStore(this)
    }
}
