package com.cpu.seamlessloopmobile.data.sync

/** Canonicalizes a snapshot immediately before writing the official cloud protocol. */
fun SyncSnapshot.prepareV2Egress(): SyncSnapshot =
    canonicalized().copy(schemaVersion = SYNC_SCHEMA_VERSION_V2)
