package com.seed.app.runtime

import android.os.Process
import android.system.Os
import android.system.OsConstants
import java.io.File

/** Production-only adapter; JVM tests exercise the ownership policy with injected I/O. */
internal fun androidOwnedRuntimeProcessFactory(
    receiptRoot: File,
    onFailure: (Throwable) -> Unit,
): OwnedRuntimeProcessFactory = synchronized(AndroidRuntimeOwnership.factories) {
    AndroidRuntimeOwnership.factories.getOrPut(receiptRoot.canonicalPath) {
        OwnedRuntimeProcessFactory(
            receiptRoot = receiptRoot,
            parentPid = Process.myPid(),
            uid = Process.myUid(),
            inspect = { inspectProcIdentity(it) },
            signal = { pid, signal ->
                require(pid > 1)
                Os.kill(pid, when (signal) {
                    OwnedProcessSignal.QUIT -> OsConstants.SIGQUIT
                    OwnedProcessSignal.CONTINUE -> OsConstants.SIGCONT
                })
            },
            onFailure = onFailure,
            cleanupReady = ::hasQuitCleanupHandler,
        )
    }
}

/** Preserve outstanding launch ownership across service recreation in the same app process. */
private object AndroidRuntimeOwnership {
    val factories = mutableMapOf<String, OwnedRuntimeProcessFactory>()
}
