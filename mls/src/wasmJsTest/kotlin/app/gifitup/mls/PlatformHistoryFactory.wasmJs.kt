package app.gifitup.mls

import app.gifitup.shared.data.crypto.HistoryCipherFactory

actual suspend fun platformHistoryCipherFactory(): HistoryCipherFactory = loadWasmHistoryCipherFactory()
