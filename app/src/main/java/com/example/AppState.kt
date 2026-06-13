package com.example

object AppState {
    @Volatile var toggleSession: (() -> Unit)? = null
}
