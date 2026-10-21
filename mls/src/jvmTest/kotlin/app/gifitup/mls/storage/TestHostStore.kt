package app.gifitup.mls.storage

import java.io.File
import java.nio.file.Path

internal val move: (Path, Path) -> Unit = { from, to -> FileLocalStore.atomicMove(from, to) }

/** The real directory fsync, except on a Windows dev machine, where a directory can't be opened (#136). */
internal val hostSync: (Path) -> Unit =
    if (System.getProperty("os.name").startsWith("Windows")) { _ -> } else { dir -> FileLocalStore.forceDirectory(dir) }

/** The app's file store for the JVM tests: real moves, and the real sync wherever the host can do it. */
internal fun hostStore(dir: File) = FileLocalStore(dir, move, hostSync)
