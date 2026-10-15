package app.gifitup.mls

import app.gifitup.shared.data.crypto.MlsClientFactory

actual suspend fun platformMlsClientFactory(): MlsClientFactory = UniffiMlsClientFactory
