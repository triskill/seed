package com.seed.app

import android.app.Application

/**
 * Application class. Phase 5.1 has nothing to do here; the
 * class is declared so the manifest can reference it (and
 * the `android:name` attribute is reserved for future use)
 * without us having to add it mid-phase. Phase 6 will wire
 * up the dependency container; Phase 8 will create the
 * notification channels here for the foreground service.
 */
class SeedApp : Application()
